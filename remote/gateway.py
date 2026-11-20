"""Loopback HTTP origin for the watch app, backed by the local Wiren Board MQTT broker."""

from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import secrets
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import BoundedSemaphore, Condition, Lock, Timer

from paho.mqtt import client as mqtt


LIGHTS = {
    f"{relay}/K{channel}": f"/devices/wb-mr6c_{relay}/controls/K{channel}"
    for relay in (101, 102) for channel in range(1, 7)
}
INDIVIDUAL_LIGHTS = LIGHTS.keys() - {"102/K4"}
STATE_TOPICS = {topic: light for light, topic in LIGHTS.items()}
VACATION = "/devices/example_home/controls/VacationActive"
ALL_OFF = "/devices/example_home/controls/AllOff/on"
SUBSCRIPTIONS = [(topic, 0) for topic in (VACATION, *STATE_TOPICS)]
MAX_BODY = 512
REQUEST_SECONDS = 8
PAIRING_SECONDS = 300
PAIRING_ATTEMPTS = 8


class ApiError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status = status
        self.code = code


def unique_keys(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate JSON key")
    return result


def parse_command(command):
    if isinstance(command, dict):
        if command.keys() == {"allOff"} and command["allOff"] is True:
            return None
        targets = command.get("lights")
        if (command.keys() == {"lights"} and isinstance(targets, dict) and targets
                and targets.keys() <= INDIVIDUAL_LIGHTS
                and all(type(value) is bool for value in targets.values())):
            return targets
    raise ApiError(400, "invalid_command")


class Pairing:
    def __init__(self, code, clock=time.monotonic):
        self.code = code
        self.clock = clock
        self.deadline = clock() + PAIRING_SECONDS
        self.remaining = PAIRING_ATTEMPTS
        self.lock = Lock()

    def redeem(self, code):
        with self.lock:
            if self.code is None or self.clock() >= self.deadline:
                self.code = None
                raise ApiError(410, "pairing_unavailable")
            if hmac.compare_digest(self.code, code):
                self.code = None
                return
            self.remaining -= 1
            if not self.remaining:
                self.code = None
                raise ApiError(429, "pairing_attempts_exhausted")
            raise ApiError(403, "incorrect_pairing_code")


class Controller:
    def __init__(self, client, timeout=3):
        self.client = client
        self.timeout = timeout
        self.changed = Condition()
        self.command_lock = Lock()
        self.generation = 0
        self.ready = False
        self.subscription = None
        self.vacation = None
        self.vacation_updated = False
        self.lights = dict.fromkeys(LIGHTS)
        self.updates = dict.fromkeys(LIGHTS, 0)
        client.on_connect = self.on_connect
        client.on_disconnect = self.on_disconnect
        client.on_subscribe = self.on_subscribe
        client.on_message = self.on_message

    def reset(self):
        self.generation += 1
        self.ready = False
        self.subscription = None
        self.vacation = None
        self.vacation_updated = False
        self.lights = dict.fromkeys(LIGHTS)
        self.updates = dict.fromkeys(LIGHTS, 0)
        self.changed.notify_all()

    def on_connect(self, client, _data, _flags, reason, _properties):
        with self.changed:
            self.reset()
            if not reason.is_failure:
                result, message_id = client.subscribe(SUBSCRIPTIONS)
                if result == mqtt.MQTT_ERR_SUCCESS:
                    self.subscription = message_id

    def on_disconnect(self, _client, _data, _flags, _reason, _properties):
        with self.changed:
            self.reset()

    def on_subscribe(self, _client, _data, message_id, reasons, _properties):
        with self.changed:
            if message_id == self.subscription:
                self.ready = len(reasons) == len(SUBSCRIPTIONS) and not any(
                    reason.is_failure for reason in reasons)

    def on_message(self, _client, _data, message):
        value = {b"0": False, b"1": True}.get(message.payload)
        with self.changed:
            if message.topic == VACATION:
                if message.retain and self.vacation_updated:
                    return
                self.vacation = value
                self.vacation_updated = not message.retain
            elif message.topic in STATE_TOPICS:
                light = STATE_TOPICS[message.topic]
                if message.retain and self.updates[light]:
                    return
                self.lights[light] = value
                if not message.retain:
                    self.updates[light] += 1
            self.changed.notify_all()

    def snapshot(self):
        return {"vacation": self.vacation, "lights": self.lights.copy()}

    def state(self):
        with self.changed:
            if not self.ready or not self.client.is_connected():
                raise ApiError(503, "controller_unavailable")
            return self.snapshot()

    def command(self, targets):
        if not self.command_lock.acquire(blocking=False):
            raise ApiError(409, "command_in_progress")
        try:
            with self.changed:
                return self.confirm_command(targets)
        finally:
            self.command_lock.release()

    def confirm_command(self, targets):
        self.state()
        if self.vacation is not False:
            raise ApiError(403, "vacation_active_or_unknown")

        all_off = targets is None
        targets = dict.fromkeys(LIGHTS, False) if all_off else targets
        pending = {light: self.updates[light] for light, value in targets.items()
                   if self.lights[light] is not value}
        publications = [(ALL_OFF, "1")] if all_off else [
            (LIGHTS[light] + "/on", "1" if targets[light] else "0") for light in pending
        ]
        generation = self.generation
        deadline = time.monotonic() + self.timeout
        packets = []
        for topic, value in publications:
            # QoS 0 without retain prevents Paho from re-sending commands after reconnect.
            packet = self.client.publish(topic, value, qos=0, retain=False)
            if packet.rc != mqtt.MQTT_ERR_SUCCESS:
                if packets:
                    break
                raise ApiError(503, "controller_unavailable")
            packets.append(packet)

        while self.generation == generation and self.client.is_connected():
            sent = len(packets) == len(publications) and all(self.was_sent(packet) for packet in packets)
            received = all(self.updates[light] > update for light, update in pending.items())
            matches = all(self.lights[light] is value for light, value in targets.items())
            if sent and received and matches:
                return self.snapshot()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                self.drop_unsent(packets)
                break
            self.changed.wait(min(remaining, 0.05))
        raise ApiError(504, "unconfirmed")

    @staticmethod
    def was_sent(packet):
        try:
            return packet.rc == mqtt.MQTT_ERR_SUCCESS and packet.is_published()
        except RuntimeError:
            return False

    def drop_unsent(self, packets):
        if all(self.was_sent(packet) for packet in packets):
            return
        # A blocked TCP connection must not deliver a queued command after the deadline.
        connection = self.client.socket()
        if connection is not None:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.reset()


class GatewayHandler(BaseHTTPRequestHandler):
    def handle(self):
        self.connection.settimeout(REQUEST_SECONDS)
        self.request_deadline = time.monotonic() + REQUEST_SECONDS
        timer = Timer(REQUEST_SECONDS, self.abort_request)
        timer.daemon = True
        timer.start()
        try:
            super().handle()
        finally:
            timer.cancel()

    def abort_request(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.respond("/v1/state", self.server.controller.state)

    def do_POST(self):
        if self.path == "/v1/pair":
            self.respond("/v1/pair", self.handle_pairing, authorize=False)
        else:
            self.respond("/v1/lights", self.handle_command)

    def respond(self, path, action, *, authorize=True):
        try:
            if authorize:
                credentials = self.headers.get_all("Authorization", [])
                if len(credentials) != 1 or not hmac.compare_digest(
                        credentials[0].encode("latin-1"), self.server.authorization):
                    raise ApiError(401, "unauthorized")
            if self.path != path:
                raise ApiError(404, "not_found")
            status, payload = 200, action()
        except ApiError as error:
            status, payload = error.status, {"error": error.code}
        except TimeoutError:
            status, payload = 408, {"error": "request_timeout"}

        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()
        self.wfile.write(body)

    def handle_command(self):
        command = parse_command(self.read_json("invalid_command"))
        controller = self.server.controller
        if time.monotonic() + controller.timeout >= self.request_deadline:
            raise ApiError(408, "request_timeout")
        return controller.command(command)

    def handle_pairing(self):
        pairing = self.server.pairing
        if pairing is None:
            raise ApiError(410, "pairing_unavailable")
        request = self.read_json("invalid_pairing_request")
        if not isinstance(request, dict) or request.keys() != {"code"}:
            raise ApiError(400, "invalid_pairing_request")
        code = request["code"]
        if not isinstance(code, str) or re.fullmatch(r"[0-9]{8}", code) is None:
            raise ApiError(400, "invalid_pairing_request")
        pairing.redeem(code)
        return {"token": self.server.token}

    def read_json(self, invalid_code):
        lengths = self.headers.get_all("Content-Length", [])
        if "Transfer-Encoding" in self.headers or len(lengths) != 1:
            raise ApiError(400, "invalid_length")
        length = lengths[0]
        if re.fullmatch(r"[0-9]{1,10}", length) is None or int(length) == 0:
            raise ApiError(400, "invalid_length")
        length = int(length)
        if length > MAX_BODY:
            raise ApiError(413, "body_too_large")
        if self.headers.get_content_type() != "application/json":
            raise ApiError(415, "expected_json")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ApiError(400, "invalid_length")
        if time.monotonic() >= self.request_deadline:
            raise ApiError(408, "request_timeout")
        try:
            return json.loads(body.decode("utf-8"), object_pairs_hook=unique_keys)
        except ValueError:
            raise ApiError(400, invalid_code) from None


class Gateway(ThreadingHTTPServer):
    def __init__(self, port, controller, key, *, pairing=None):
        self.controller = controller
        self.token = key
        self.authorization = b"Bearer " + key.encode("ascii")
        self.pairing = pairing
        self.connections = BoundedSemaphore(8)
        super().__init__(("127.0.0.1", port), GatewayHandler)

    def process_request(self, request, address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.connections.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connections.release()

    def handle_error(self, _request, _address):
        # HTTP traffic can include a credential and must never be logged.
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--pair-once", action="store_true", help="выдать одноразовый код для часов")
    args = parser.parse_args()
    key = os.environ.get("WB_GATEWAY_TOKEN", "")
    if re.fullmatch(r"[A-Za-z0-9_-]{32,128}", key) is None:
        parser.error("WB_GATEWAY_TOKEN must be a randomly generated base64url token (32–128 characters)")
    username = os.environ.get("WB_MQTT_USERNAME")
    password = os.environ.get("WB_MQTT_PASSWORD")
    if password is not None and not username:
        parser.error("WB_MQTT_PASSWORD requires WB_MQTT_USERNAME")
    host = os.environ.get("WB_MQTT_HOST", "127.0.0.1")
    try:
        port = int(os.environ.get("WB_MQTT_PORT", "1883"))
        if not (1 <= port <= 65535 and 1 <= args.port <= 65535):
            raise ValueError
    except ValueError:
        parser.error("MQTT and HTTP port numbers must be integers between 1 and 65535")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, clean_session=True)
    if username:
        client.username_pw_set(username, password)
    controller = Controller(client)
    client.connect_async(host, port, keepalive=10)
    pairing = Pairing(f"{secrets.randbelow(100_000_000):08d}") if args.pair_once else None
    with Gateway(args.port, controller, key, pairing=pairing) as server:
        client.loop_start()
        if pairing is not None:
            print(f"Код для часов (5 минут): {pairing.code}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            client.disconnect()
            client.loop_stop()


if __name__ == "__main__":
    main()
