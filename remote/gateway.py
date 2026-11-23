"""Loopback HTTP origin for the watch app, backed by the local Wiren Board MQTT broker."""

from __future__ import annotations

import argparse
import hmac
import logging
import os
import re
import secrets
import signal
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import BoundedSemaphore, Condition, Lock, Timer

from paho.mqtt import client as mqtt

from catalog import (
    CATALOG_TOPIC, MAX_CATALOG, REVISION_TOPIC, SERIAL_FILE, SERIAL_TOPIC, ApiError, Catalog,
    decode_json, decode_state, read_serial, read_settings, valid_metadata, wire_json,
)


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
    def __init__(self, client, timeout=3, *, catalog=None, legacy=True):
        self.client = client
        self.timeout = timeout
        self.catalog = catalog
        self.legacy = legacy
        self.changed = Condition()
        self.command_lock = Lock()
        self.generation = 0
        self.session = 0
        self.closing = False
        self.controls = {**dict.fromkeys(STATE_TOPICS, "power"), VACATION: "vacation"} if legacy else {}
        self.catalog_metadata = {}
        if catalog is not None and catalog.serial is not None:
            self.controls = {topic: kind for topic, kind in catalog.controls.items() if kind != "allOff"}
            self.catalog_metadata = {topic + "/meta": (topic, kind) for topic, kind in catalog.controls.items()}
        self.subscriptions = [(topic, 0) for topic in (*self.controls, *self.catalog_metadata)]
        if catalog is not None and catalog.serial is not None:
            self.subscriptions.append((SERIAL_TOPIC, 0))
        with self.changed:
            self.reset()
        client.on_connect = self.on_connect
        client.on_disconnect = self.on_disconnect
        client.on_subscribe = self.on_subscribe
        client.on_message = self.on_message

    def reset(self):
        self.generation += 1
        self.session += 1
        self.ready = False
        self.subscription = None
        self.serial_verified = False
        self.catalog_valid = False
        self.advertised = False
        self.values = dict.fromkeys(self.controls)
        self.updates = dict.fromkeys(self.controls, 0)
        self.metadata = set()
        self.live_identity = set()
        self.changed.notify_all()

    def on_connect(self, client, _data, _flags, reason, _properties=None):
        with self.changed:
            self.reset()
            if reason == 0 and self.subscriptions and not self.closing:
                result, message_id = client.subscribe(self.subscriptions)
                if result == mqtt.MQTT_ERR_SUCCESS:
                    self.subscription = message_id

    def on_disconnect(self, _client, _data, *_details):
        with self.changed:
            self.reset()

    def on_subscribe(self, _client, _data, message_id, reasons, _properties=None):
        with self.changed:
            if message_id == self.subscription:
                self.ready = len(reasons) == len(self.subscriptions) and all(
                    reason in (0, 1, 2) for reason in reasons)
                self.sync_catalog()
                self.changed.notify_all()

    def on_message(self, _client, _data, message):
        topic, payload = message.topic, message.payload
        with self.changed:
            if topic in self.controls:
                if message.retain and self.updates[topic]:
                    return
                self.values[topic] = decode_state(self.controls[topic], payload)
                if not message.retain:
                    self.updates[topic] += 1
            elif topic == SERIAL_TOPIC or topic in self.catalog_metadata:
                if message.retain and topic in self.live_identity:
                    return
                if not message.retain:
                    self.live_identity.add(topic)
                if topic == SERIAL_TOPIC:
                    if self.catalog is None or self.catalog.serial is None:
                        return
                    self.serial_verified = payload.strip().lower() == self.catalog.serial.lower().encode("ascii")
                    if not self.serial_verified:
                        logging.warning("Серийный номер MQTT не совпадает с номером контроллера")
                else:
                    control, kind = self.catalog_metadata[topic]
                    try:
                        meta = decode_json(payload) if len(payload) <= 8192 else None
                    except (ValueError, RecursionError):
                        meta = None
                    if valid_metadata(kind, meta):
                        self.metadata.add(control)
                    else:
                        self.metadata.discard(control)
                        logging.warning("Канал %s не соответствует настройке %s", control, kind)
                self.sync_catalog()
            self.changed.notify_all()

    def sync_catalog(self):
        if self.catalog is None or self.closing:
            return
        valid = self.ready and self.serial_verified and len(self.metadata) == len(self.catalog.controls)
        if valid != self.catalog_valid:
            self.catalog_valid = valid
            self.generation += 1
        if valid and not self.advertised:
            packet = self.client.publish(CATALOG_TOPIC, self.catalog.body, qos=0, retain=True)
            if packet.rc == mqtt.MQTT_ERR_SUCCESS:
                revision = self.client.publish(REVISION_TOPIC, self.catalog.revision, qos=0, retain=True)
                self.advertised = revision.rc == mqtt.MQTT_ERR_SUCCESS
        elif not valid and self.advertised:
            self.clear_catalog()

    def clear_catalog(self):
        self.advertised = False
        return [self.client.publish(topic, b"", qos=0, retain=True) for topic in (REVISION_TOPIC, CATALOG_TOPIC)]

    def close(self):
        with self.changed:
            self.closing = True
            self.generation += 1
            self.changed.notify_all()
            if not self.advertised or not self.client.is_connected():
                return
            packets = self.clear_catalog()
        for packet in packets:
            try:
                packet.wait_for_publish(timeout=0.5)
            except RuntimeError:
                break

    def snapshot(self):
        if self.legacy:
            return {"vacation": self.values[VACATION],
                    "lights": {identity: self.values[topic] for identity, topic in LIGHTS.items()}}
        return {"revision": self.catalog.revision,
                "vacation": self.values[self.catalog.vacation] if self.catalog.vacation else False,
                "lights": {identity: {kind: self.values[light[kind]] for kind in ("power", "brightness", "color")
                                      if kind in light} for identity, light in self.catalog.lights.items()}}

    def check_available(self, *, v2=False):
        if self.legacy == v2:
            raise ApiError(404, "not_found")
        if self.closing or not self.ready or not self.client.is_connected():
            raise ApiError(503, "controller_unavailable")
        if v2 and not self.catalog_valid:
            raise ApiError(503, "catalog_unavailable")

    def state(self, *, v2=False):
        with self.changed:
            self.check_available(v2=v2)
            return self.snapshot()

    def layout(self):
        with self.changed:
            self.check_available(v2=True)
            return self.catalog.layout

    def command(self, request, *, v2=False):
        if not self.command_lock.acquire(blocking=False):
            raise ApiError(409, "command_in_progress")
        try:
            with self.changed:
                self.check_available(v2=v2)
                if v2:
                    targets, master = self.catalog.command(request)
                    vacation = self.catalog.vacation
                else:
                    selected = dict.fromkeys(LIGHTS, False) if request is None else request
                    targets = {LIGHTS[identity]: value for identity, value in selected.items()}
                    master = ALL_OFF.removesuffix("/on") if request is None else None
                    vacation = VACATION
                if vacation is not None and self.values[vacation] is not False:
                    raise ApiError(403, "vacation_active_or_unknown")
                return self.confirm_command(targets, master)
        finally:
            self.command_lock.release()

    def confirm_command(self, targets, master):
        pending = {topic: self.updates[topic] for topic, value in targets.items() if self.values[topic] != value}
        publications = [(master + "/on", "1")] if master else [
            (topic + "/on", str(int(targets[topic])) if type(targets[topic]) is bool else str(targets[topic]))
            for topic in pending
        ]
        generation = self.generation
        session = self.session
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
            received = all(self.updates[topic] > update for topic, update in pending.items())
            matches = all(self.values[topic] == value for topic, value in targets.items())
            if sent and received and matches:
                return self.snapshot()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            self.changed.wait(min(remaining, 0.05))
        if self.session == session:
            self.drop_unsent(packets)
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
        controller = self.server.controller
        routes = {"/v1/state": controller.state, "/v2/layout": controller.layout,
                  "/v2/state": lambda: controller.state(v2=True)}
        self.respond(self.path, routes.get(self.path))

    def do_POST(self):
        if self.path == "/v1/pair":
            self.respond("/v1/pair", self.handle_pairing, authorize=False)
        elif self.path == "/v2/command":
            self.respond("/v2/command", lambda: self.handle_command(v2=True))
        else:
            self.respond("/v1/lights", self.handle_command)

    def respond(self, path, action, *, authorize=True):
        try:
            if authorize:
                credentials = self.headers.get_all("Authorization", [])
                if len(credentials) != 1 or not hmac.compare_digest(
                        credentials[0].encode("latin-1"), self.server.authorization):
                    raise ApiError(401, "unauthorized")
            if self.path != path or action is None:
                raise ApiError(404, "not_found")
            status, payload = 200, action()
        except ApiError as error:
            status, payload = error.status, {"error": error.code}
        except TimeoutError:
            status, payload = 408, {"error": "request_timeout"}

        body = wire_json(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.end_headers()
        self.wfile.write(body)

    def handle_command(self, *, v2=False):
        request = self.read_json("invalid_command", limit=MAX_CATALOG if v2 else MAX_BODY)
        command = request if v2 else parse_command(request)
        controller = self.server.controller
        if time.monotonic() + controller.timeout >= self.request_deadline:
            raise ApiError(408, "request_timeout")
        return controller.command(command, v2=v2)

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

    def read_json(self, invalid_code, *, limit=MAX_BODY):
        lengths = self.headers.get_all("Content-Length", [])
        if "Transfer-Encoding" in self.headers or len(lengths) != 1:
            raise ApiError(400, "invalid_length")
        length = lengths[0]
        if re.fullmatch(r"[0-9]{1,10}", length) is None or int(length) == 0:
            raise ApiError(400, "invalid_length")
        length = int(length)
        if length > limit:
            raise ApiError(413, "body_too_large")
        if self.headers.get_content_type() != "application/json":
            raise ApiError(415, "expected_json")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ApiError(400, "invalid_length")
        if time.monotonic() >= self.request_deadline:
            raise ApiError(408, "request_timeout")
        try:
            return decode_json(body)
        except (ValueError, RecursionError):
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
    parser.add_argument("--switch-config", type=Path, help="файл настроек конфигуратора Wiren Board")
    parser.add_argument("--pair-code-file", type=Path, help="файл кода, выдаваемого по сигналу SIGUSR1")
    parser.add_argument("--controller-serial-file", type=Path, default=SERIAL_FILE, help=argparse.SUPPRESS)
    args = parser.parse_args()
    catalog = None
    legacy = True
    if args.switch_config:
        try:
            settings = read_settings(args.switch_config)
            if not settings["enabled"]:
                return
            legacy = "rooms" not in settings
            if not legacy:
                catalog = Catalog(settings, read_serial(args.controller_serial_file))
                if catalog.serial is None:
                    logging.warning("Каталог недоступен: не удалось прочитать серийный номер контроллера")
        except (OSError, ValueError, RecursionError) as error:
            parser.error(f"invalid gateway configuration: {error}")
    if args.pair_code_file and not hasattr(signal, "SIGUSR1"):
        parser.error("--pair-code-file requires SIGUSR1")
    key = os.environ.get("WB_GATEWAY_TOKEN", "")
    if re.fullmatch(r"[A-Za-z0-9_-]{32,128}", key) is None:
        parser.error("gateway token must be a randomly generated base64url token (32–128 characters)")
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

    if hasattr(mqtt, "CallbackAPIVersion"):
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, clean_session=True)
    else:
        client = mqtt.Client(clean_session=True)
    if username:
        client.username_pw_set(username, password)
    if catalog is not None and catalog.serial is not None:
        client.will_set(REVISION_TOPIC, b"", qos=0, retain=True)
    controller = Controller(client, catalog=catalog, legacy=legacy)
    client.connect_async(host, port, keepalive=10)
    with Gateway(args.port, controller, key) as server:
        def issue_code(*_signal):
            pairing = Pairing(f"{secrets.randbelow(100_000_000):08d}")
            if args.pair_code_file:
                args.pair_code_file.write_text(pairing.code + "\n", encoding="ascii")
            else:
                print(f"Код для часов (5 минут): {pairing.code}", flush=True)
            server.pairing = pairing

        if args.pair_code_file:
            signal.signal(signal.SIGUSR1, issue_code)
        if args.pair_once:
            issue_code()
        def stop_service(*_signal):
            raise KeyboardInterrupt

        signal.signal(signal.SIGTERM, stop_service)
        client.loop_start()
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            controller.close()
            client.disconnect()
            client.loop_stop()


if __name__ == "__main__":
    main()
