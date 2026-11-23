import copy
import hashlib
import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from catalog import (
    CATALOG_TOPIC, MAX_CATALOG, REVISION_TOPIC, SERIAL_TOPIC, Catalog, persist_editor_ids,
    arrange_editor_groups, decode_settings, read_serial, read_settings, wire_json,
)
from gateway import Controller, Gateway
import test_gateway
from test_gateway import FakeMQTT, KEY


SERIAL = "TEST001"
POWER = "/devices/lighting/controls/Light 1"
BRIGHTNESS = "/devices/lighting/controls/Level"
COLOR = "/devices/lighting/controls/RGB"
SECOND = "/devices/lighting/controls/Light 2"
VACATION = "/devices/lighting/controls/Vacation"
MASTER = "/devices/lighting/controls/Master"
SETTINGS = {
    "enabled": True,
    "rooms": [{"label": "Гостиная", "theme": "living", "lights": [
        {"id": "first", "label": "Свет", "theme": "pendant", "power": "lighting/Light 1",
         "brightness": "lighting/Level", "color": "lighting/RGB"},
        {"id": "second", "label": "Подсветка", "power": "lighting/Light 2"},
    ]}],
}
METADATA = {POWER: {"type": "switch"}, SECOND: {"type": "switch"}, BRIGHTNESS: {"type": "range", "max": 100},
            COLOR: {"type": "rgb"}, VACATION: {"type": "switch", "readonly": True}, MASTER: {"type": "pushbutton"}}


class CatalogSettingsTest(unittest.TestCase):
    def test_fresh_and_legacy_settings(self):
        config = Path(__file__).parent / "debian" / "wb-wear-light-gateway.conf"
        settings = read_settings(config)
        self.assertIs(settings["enabled"], False)
        self.assertEqual(Catalog(settings, SERIAL).layout["rooms"], [])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.json"
            self.assertIsNone(read_serial(path))
            path.write_text('{"enabled":true}', encoding="utf-8")
            self.assertEqual(read_settings(path), {"enabled": True})
            path.write_text("TEST001\n", encoding="ascii")
            self.assertEqual(read_serial(path), SERIAL)
            path.write_text("invalid/123", encoding="ascii")
            self.assertIsNone(read_serial(path))

    def test_stable_ids_revision_and_repeat_confed_save(self):
        settings = copy.deepcopy(SETTINGS)
        draft = settings["rooms"][0]
        del draft["lights"][0]["id"]
        first = Catalog(settings, SERIAL)
        expected = persist_editor_ids(copy.deepcopy(settings), {})
        draft["label"] = "Зал"
        draft["lights"].reverse()
        draft["lights"][1]["label"] = "Основной"
        second = Catalog(settings, SERIAL)
        self.assertEqual(first.rooms[0]["id"], second.rooms[0]["id"])
        self.assertEqual(first.rooms[0]["lights"][0]["id"], second.rooms[0]["lights"][1]["id"])
        self.assertNotEqual(first.revision, second.revision)
        draft["lights"].append({"label": "Ещё светильник", "power": "lighting/Light 3"})
        normalized = persist_editor_ids(settings, expected)
        self.assertEqual(normalized["rooms"][0]["id"], first.rooms[0]["id"])
        unsigned = {key: value for key, value in first.layout.items() if key != "revision"}
        self.assertEqual(first.revision, hashlib.sha256(wire_json(unsigned)).hexdigest())
        self.assertNotEqual(first.revision, Catalog(SETTINGS, "TEST002").revision)

    def test_editor_round_trip_keeps_ids_and_optional_settings(self):
        persisted = persist_editor_ids(copy.deepcopy({**SETTINGS, "vacation": "lighting/Vacation",
                                                      "allOff": "lighting/Master"}), {})
        editor = copy.deepcopy(persisted)
        arrange_editor_groups(editor, for_editor=True)
        room = editor["rooms"][0]
        light = room["lights"][0]
        self.assertEqual(room["advanced"], {"theme": "living"})
        self.assertEqual(light["advanced"], {"theme": "pendant", "brightness": "lighting/Level", "color": "lighting/RGB"})
        self.assertEqual(editor["house"], {"vacation": "lighting/Vacation", "allOff": "lighting/Master"})
        room["label"] = "Новая гостиная"
        light["power"] = "lighting/Replacement"
        restored = persist_editor_ids(decode_settings(wire_json(editor), from_editor=True), persisted)
        self.assertEqual(restored["rooms"][0]["id"], persisted["rooms"][0]["id"])
        self.assertEqual(restored["rooms"][0]["lights"][0]["id"], light["id"])
        self.assertEqual(restored["rooms"][0]["lights"][0]["color"], "lighting/RGB")
        self.assertEqual(restored["rooms"][0]["lights"][0]["brightness"], "lighting/Level")
        self.assertEqual(restored["rooms"][0]["theme"], "living")
        legacy = {"enabled": True}
        arrange_editor_groups(legacy, for_editor=True)
        self.assertEqual(decode_settings(wire_json(legacy), from_editor=True), {"enabled": True})

    def test_rejects_duplicates_and_unsafe_topics(self):
        for power in ("lighting/Light 1", "lighting/+/on", "+/channel", "lighting/Light 2/on",
                      "lighting/Bad\x00", "wb-wear-light-gateway/Catalog", "system/Short SN"):
            with self.subTest(power=power):
                settings = copy.deepcopy(SETTINGS)
                settings["rooms"][0]["lights"][1]["power"] = power
                with self.assertRaises(ValueError):
                    Catalog(settings, SERIAL)
        settings = copy.deepcopy(SETTINGS)
        settings["rooms"].append({"label": "Спальня", "lights": [
            {"id": "first", "label": "Лампа", "power": "lighting/Light 3"},
        ]})
        with self.assertRaises(ValueError):
            Catalog(settings, SERIAL)


class CatalogGatewayTest(unittest.TestCase):
    request = test_gateway.GatewayTest.request

    @staticmethod
    def stop_server(server, thread):
        server.shutdown()
        thread.join()
        server.server_close()

    def start_gateway(self, settings=SETTINGS, serial=SERIAL):
        self.mqtt = FakeMQTT()
        self.layout = Catalog(settings, serial)
        self.controller = Controller(self.mqtt, timeout=0.4, catalog=self.layout, legacy=False)
        self.mqtt.connect()
        self.server = Gateway(0, self.controller, KEY)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02})
        self.thread.start()
        self.addCleanup(self.stop_server, self.server, self.thread)

    def identify(self, value=SERIAL.encode()):
        self.mqtt.state(SERIAL_TOPIC, value, retained=True)
        for control in self.layout.controls:
            self.mqtt.state(control + "/meta", wire_json(METADATA[control]), retained=True)

    def post(self, **command):
        command.setdefault("revision", self.layout.revision)
        return self.request("POST", "/v2/command", json.dumps(command).encode())

    def commands(self):
        return [item[:2] for item in self.mqtt.sent if not item[3]]
    def test_identity_metadata_publication_and_read_only_http(self):
        self.start_gateway()
        with self.assertLogs(level="WARNING"):
            self.identify(b"WINDOWS1")
        self.assertEqual(self.request("GET", "/v2/layout")[0], 503)
        self.assertEqual(self.mqtt.sent, [])
        self.identify(b"test001")
        self.assertEqual(self.request("GET", "/v2/layout", token=None)[0], 401)
        self.assertEqual(self.request("GET", "/v2/layout"), (200, self.layout.layout))
        self.assertEqual(self.request("GET", "/v1/state")[0], 404)
        self.assertEqual(self.mqtt.sent, [(CATALOG_TOPIC, self.layout.body, 0, True),
                                          (REVISION_TOPIC, self.layout.revision, 0, True)])
        self.mqtt.state(BRIGHTNESS, b"35.0", retained=True)
        self.assertEqual(self.request("GET", "/v2/state"), (200, {"revision": self.layout.revision,
            "vacation": False, "lights": {"first": {"power": None, "brightness": 35, "color": None},
                                          "second": {"power": None}},
        }))
        self.mqtt.state(BRIGHTNESS, b"35.5")
        self.assertIsNone(self.request("GET", "/v2/state")[1]["lights"]["first"]["brightness"])
        with self.assertLogs(level="WARNING"):
            self.mqtt.state(POWER + "/meta", b'{"type":"switch","readonly":true}')
        self.assertEqual(self.request("GET", "/v2/layout")[0], 503)
        self.assertEqual(self.mqtt.sent[-2:], [(REVISION_TOPIC, b"", 0, True), (CATALOG_TOPIC, b"", 0, True)])
        self.assertEqual(self.commands(), [])

    def test_missing_controller_serial_never_advertises(self):
        self.start_gateway({"enabled": True, "rooms": []}, serial=None)
        self.mqtt.state(SERIAL_TOPIC, SERIAL.encode(), retained=True)
        self.assertEqual(self.request("GET", "/v2/layout")[0], 503)
        self.assertEqual(self.post(allOff=True)[0], 503)
        self.assertEqual(self.mqtt.sent, [])

    def test_invalid_command_rejects_entire_batch(self):
        self.start_gateway()
        self.identify()
        for attrs in ({"brightness": True}, {"brightness": 101}, {"brightness": float("inf")},
                      {"color": "0;256;1"}, {"power": 1}, {"power": True, "brightness": 30},
                      {"topic": POWER}, {"color": [0, 0, 0]}):
            self.assertEqual(self.post(changes={"second": {"power": True}, "first": attrs})[0], 400)
        self.assertEqual(self.post(changes={"removed": {"power": True}})[0], 400)
        self.assertEqual(self.post(changes={"second": {"brightness": 50}})[0], 400)
        self.assertEqual(self.post(revision="0" * 64, allOff=True), (409, {"error": "catalog_changed"}))
        self.assertEqual(self.request("POST", "/v2/command", b" " * (MAX_CATALOG + 1))[0], 413)
        self.assertEqual(self.commands(), [])

    def test_vacation_gates_and_batch_requires_live_confirmation(self):
        self.start_gateway({**SETTINGS, "vacation": "lighting/Vacation"})
        self.identify()
        self.assertEqual(self.post(allOff=True)[0], 403)
        self.mqtt.state(VACATION, b"1", retained=True)
        self.assertEqual(self.post(allOff=True)[0], 403)
        self.mqtt.state(VACATION, b"0")
        self.mqtt.published.clear()
        with ThreadPoolExecutor(max_workers=1) as executor:
            body = {"revision": self.layout.revision, "changes": {"first": {"color": "0;128;255"},
                                                               "second": {"power": True}}}
            request = executor.submit(self.request, "POST", "/v2/command", b" " * 1024 + json.dumps(body).encode())
            self.assertTrue(self.mqtt.published.wait(1))
            self.mqtt.state(COLOR, b"0;128;255", retained=True)
            self.mqtt.state(SECOND, b"1")
            self.assertEqual(self.post(allOff=True)[0], 409)
            self.mqtt.state(COLOR, b"0;128;255")
            self.assertEqual(request.result()[1]["lights"]["first"]["color"], "0;128;255")
        self.assertEqual(self.commands(), [(COLOR + "/on", "0;128;255"), (SECOND + "/on", "1")])

    def test_master_once_or_only_configured_power_without_master(self):
        for master in (True, False):
            with self.subTest(master=master):
                settings = {**SETTINGS, **({"allOff": "lighting/Master"} if master else {})}
                self.start_gateway(settings)
                self.identify()
                self.mqtt.state(POWER, b"1", retained=True)
                self.mqtt.state(SECOND, b"0", retained=True)
                self.mqtt.published.clear()
                with ThreadPoolExecutor(max_workers=1) as executor:
                    request = executor.submit(self.post, allOff=True)
                    self.assertTrue(self.mqtt.published.wait(1))
                    self.mqtt.state(POWER, b"0")
                    self.assertEqual(request.result()[0], 200)
                self.assertEqual(self.commands(), [(MASTER + "/on", "1")] if master else [(POWER + "/on", "0")])
