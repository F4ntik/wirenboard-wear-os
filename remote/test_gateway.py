import json
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from types import SimpleNamespace

from gateway import ALL_OFF, LIGHTS, SUBSCRIPTIONS, VACATION, Controller, Gateway, Pairing, mqtt


KEY = "test_key_with_at_least_thirty_two_characters"


class FakeMQTT:
    def __init__(self):
        self.connected = True
        self.sent = []
        self.published = threading.Event()
        self.subscriptions = None
        self.stalled = False
        self.aborted = False

    def subscribe(self, subscriptions):
        self.subscriptions = subscriptions
        return mqtt.MQTT_ERR_SUCCESS, 7

    def publish(self, topic, value, qos, retain):
        self.sent.append((topic, value, qos, retain))
        self.published.set()
        return SimpleNamespace(rc=mqtt.MQTT_ERR_SUCCESS, is_published=lambda: not self.stalled)

    def is_connected(self):
        return self.connected

    def socket(self):
        return SimpleNamespace(shutdown=self.abort)

    def abort(self, _direction):
        self.aborted = True

    def state(self, topic, payload, retained=False):
        message = SimpleNamespace(topic=topic, payload=payload, retain=retained)
        self.on_message(self, None, message)

    def connect(self):
        self.connected = True
        self.subscriptions = None
        self.on_connect(self, None, None, 0, None)
        if self.subscriptions is not None:
            self.on_subscribe(self, None, 7, [0] * len(self.subscriptions), None)

    def disconnect(self):
        self.connected = False
        self.on_disconnect(self, None, None, None, None)


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.mqtt = FakeMQTT()
        self.controller = Controller(self.mqtt, timeout=0.4)
        self.mqtt.connect()
        self.mqtt.state(VACATION, b"0", retained=True)
        self.server = Gateway(0, self.controller, KEY)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02})
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def request(self, method, path, body=None, token=KEY):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=2)
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            self.assertEqual(response.getheader("Cache-Control"), "no-store")
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def post(self, body):
        return self.request("POST", "/v1/lights", json.dumps(body).encode())

    def pair(self, code):
        return self.request("POST", "/v1/pair", json.dumps({"code": code}).encode(), token=None)

    def test_pairing_disabled_by_default(self):
        self.assertEqual(self.pair("01234567"), (410, {"error": "pairing_unavailable"}))

    def test_paho_v1_callbacks_and_failed_subscription(self):
        self.mqtt.on_connect(self.mqtt, None, {}, 0)
        self.mqtt.on_subscribe(self.mqtt, None, 7, [0x80] * len(SUBSCRIPTIONS))
        self.assertEqual(self.request("GET", "/v1/state")[0], 503)
        self.mqtt.on_subscribe(self.mqtt, None, 7, [0] * len(SUBSCRIPTIONS))
        self.assertEqual(self.request("GET", "/v1/state")[0], 200)
        self.mqtt.on_disconnect(self.mqtt, None, 0)
        self.assertEqual(self.request("GET", "/v1/state")[0], 503)

    def test_pairing_validation_and_one_use(self):
        self.server.pairing = Pairing("01234567")
        for body in (b"{}", b'{"code":12345678}', b'{"code":"1234567"}', b'{"code":"01234567","extra":0}',
                     b'{"code":"01234567","code":"01234567"}', b'{"code":"abcdefgh"}'):
            with self.subTest(body=body):
                self.assertEqual(self.request("POST", "/v1/pair", body, token=None)[0], 400)
        self.assertEqual(self.request("POST", "/v1/pair", b" " * 513, token=None)[0], 413)
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = sorted(executor.map(self.pair, ["01234567", "01234567"]))
        self.assertEqual(results, [(200, {"token": KEY}), (410, {"error": "pairing_unavailable"})])
        self.assertEqual(self.request("GET", "/v1/state", token=KEY)[0], 200)
        self.assertEqual(self.mqtt.sent, [])

    def test_pairing_locks_after_eight_incorrect_attempts(self):
        self.server.pairing = Pairing("01234567")
        for attempt in range(7):
            with self.subTest(attempt=attempt):
                self.assertEqual(self.pair("01234568"), (403, {"error": "incorrect_pairing_code"}))
        self.assertEqual(self.pair("01234568"), (429, {"error": "pairing_attempts_exhausted"}))
        self.assertEqual(self.pair("01234567"), (410, {"error": "pairing_unavailable"}))

    def test_pairing_expires(self):
        time = [12.0]
        self.server.pairing = Pairing("01234567", clock=lambda: time[0])
        time[0] += 300
        self.assertEqual(self.pair("01234567"), (410, {"error": "pairing_unavailable"}))

    def test_token_routes_and_snapshot_shape(self):
        self.assertEqual(self.request("GET", "/v1/state", token=None)[0], 401)
        self.assertEqual(self.request("GET", "/v1/state", token="incorrect")[0], 401)
        self.assertEqual(self.request("POST", "/v1/lights", b'{"allOff":true}', token="incorrect")[0], 401)
        self.assertEqual(self.request("GET", "/admin")[0], 404)
        self.assertEqual(self.request("POST", "/v1/state", b"{}")[0], 404)
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        self.assertEqual(set(self.mqtt.subscriptions), {(topic, 0) for topic in (*LIGHTS.values(), VACATION)})
        self.assertEqual(self.request("GET", "/v1/state"), (200, {
            "vacation": False, "lights": dict.fromkeys(LIGHTS),
        }))
        self.assertEqual(self.mqtt.sent, [])

    def test_rejects_invalid_or_oversize_commands(self):
        for body in (b"{}", b"[]", b"{", b'{"allOff":false}', b'{"allOff":true,"lights":{}}',
                     b'{"lights":{"102/K4":false}}', b'{"lights":{"101/K7":false}}',
                     b'{"lights":{"+/K1":false}}', b'{"lights":{"101/K1":0}}',
                     b'{"lights":{"101/K1":true,"101/K1":false}}'):
            with self.subTest(body=body):
                self.assertEqual(self.request("POST", "/v1/lights", body)[0], 400)
        self.assertEqual(self.request("POST", "/v1/lights", b" " * 513)[0], 413)
        self.assertEqual(self.mqtt.sent, [])

    def test_blocks_vacation_and_unavailable_broker(self):
        for value in (b"1", b""):
            self.mqtt.state(VACATION, value)
            self.assertEqual(self.post({"lights": {"101/K1": True}})[0], 403)
        self.mqtt.state(VACATION, b"0", retained=True)
        self.assertEqual(self.post({"allOff": True})[0], 403)
        self.mqtt.disconnect()
        self.assertEqual(self.request("GET", "/v1/state")[0], 503)
        self.assertEqual(self.post({"allOff": True})[0], 503)
        self.assertEqual(self.mqtt.sent, [])

    def test_group_requires_current_target_states_and_rejects_competing_post(self):
        first, second = LIGHTS["102/K1"], LIGHTS["101/K1"]
        self.mqtt.state(first, b"1", retained=True)
        self.mqtt.state(second, b"1", retained=True)
        with ThreadPoolExecutor(max_workers=1) as executor:
            request = executor.submit(self.post, {"lights": {"102/K1": False, "101/K1": False}})
            self.assertTrue(self.mqtt.published.wait(1))
            self.mqtt.state(first, b"0")
            self.mqtt.state(first, b"1")
            self.mqtt.state(second, b"0")
            self.assertEqual(self.post({"lights": {"101/K6": True}})[0], 409)
            self.mqtt.state(first, b"0")
            status, state = request.result()
        self.assertEqual(status, 200)
        self.assertIs(state["lights"]["102/K1"], False)
        self.assertIs(state["lights"]["101/K1"], False)
        self.assertEqual(self.mqtt.sent, [(topic + "/on", "0", 0, False) for topic in (first, second)])

    def test_master_sends_once_and_waits_for_unlabelled_line(self):
        for light, topic in LIGHTS.items():
            self.mqtt.state(topic, b"1" if light == "102/K4" else b"0", retained=True)
        with ThreadPoolExecutor(max_workers=1) as executor:
            request = executor.submit(self.post, {"allOff": True})
            self.assertTrue(self.mqtt.published.wait(1))
            self.mqtt.state(LIGHTS["102/K4"], b"0", retained=True)
            self.assertEqual(self.post({"lights": {"101/K6": True}})[0], 409)
            self.mqtt.state(LIGHTS["102/K4"], b"0")
            self.assertEqual(request.result(), (200, {"vacation": False, "lights": dict.fromkeys(LIGHTS, False)}))
        self.assertEqual(self.mqtt.sent, [(ALL_OFF, "1", 0, False)])

    def test_confirmation_timeout_never_retries_and_cancels_unsent_packets(self):
        target = LIGHTS["101/K1"] + "/on"
        self.assertEqual(self.post({"lights": {"101/K1": True}}), (504, {"error": "unconfirmed"}))
        self.assertEqual(self.mqtt.sent, [(target, "1", 0, False)])
        self.assertFalse(self.mqtt.aborted)

        self.mqtt.stalled = True
        self.assertEqual(self.post({"lights": {"101/K1": True}})[0], 504)
        self.assertTrue(self.mqtt.aborted)
        self.assertEqual(self.request("GET", "/v1/state")[0], 503)
        self.mqtt.connect()
        self.assertEqual(self.request("GET", "/v1/state")[1]["vacation"], None)
        self.assertEqual(self.mqtt.sent, [(target, "1", 0, False)] * 2)

    def test_disconnect_cannot_confirm_on_new_session(self):
        topic = LIGHTS["101/K1"]
        with ThreadPoolExecutor(max_workers=1) as executor:
            request = executor.submit(self.post, {"lights": {"101/K1": True}})
            self.assertTrue(self.mqtt.published.wait(1))
            self.mqtt.disconnect()
            self.mqtt.connect()
            self.mqtt.state(topic, b"1")
            self.assertEqual(request.result()[0], 504)
        self.assertEqual(self.mqtt.sent, [(topic + "/on", "1", 0, False)])


if __name__ == "__main__":
    unittest.main()
