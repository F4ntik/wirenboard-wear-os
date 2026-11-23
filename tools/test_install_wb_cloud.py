from contextlib import nullcontext
from email.message import Message
from io import BytesIO
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch

import install_wb_cloud as installer
import win_prepared


ORIGIN = "https://serial01-8765.apps.wirenboard.cloud"
ID = "0a" * 16
PROFILE = {"refresh": "example.refresh.token", "gatewayKey": "example-gateway", "installId": ID}
REVISION = "a" * 64
GATEWAY_KEY = "example-gateway-key-1234567890-abcd"


def fake_response(status, body, content_type="application/json"):
    response = BytesIO(json.dumps(body).encode())
    response.code = status
    response.headers = Message()
    response.headers["Content-Type"] = content_type
    return response


def layout(serial="serial01", rooms=None):
    return {"schema": 1, "controller": serial, "revision": REVISION,
            "rooms": [{"lights": [{"id": "lamp"}]}] if rooms is None else rooms}


def home_state(ready=True, lights=None):
    return {"revision": REVISION, "vacation": False,
            "lights": {"lamp": {"power": ready}} if lights is None else lights}


class InstallerTest(unittest.TestCase):
    def test_app_only_updates_without_requesting_cloud_credentials(self):
        apk = Path(__file__)
        with (patch.object(installer.sys, "argv", ["installer", "--app-only", "--watch", "watch", str(apk)]),
              patch.object(installer, "install") as install,
              patch.object(installer, "prepare_profile") as cloud,
              patch("builtins.print")):
            installer.main()
        install.assert_called_once_with(["adb", "-s", "watch"], apk)
        cloud.assert_not_called()

    def prepare_with_service(self, endpoints, expect_error=None):
        cloud = Mock()
        replies = [{"access": "new.access.token", "refresh": "new.refresh.token"},
                   [{"serialNumber": "SERIAL01"}], [{"port": 8765}], {"tunnelRedirectUrl": "/?grant=example"}]
        cloud.open.side_effect = [fake_response(200, reply) for reply in replies]
        service = Mock()
        service.open.side_effect = [fake_response(200, {"token": GATEWAY_KEY}), *endpoints]
        pair_code = Mock(return_value="01234567")
        args = SimpleNamespace(controller="serial01", port=8765, pair_with_putty=None)
        with (patch.object(installer, "client", side_effect=[object(), cloud, service]),
              patch.object(installer, "open_service"), patch.object(installer, "send_profile") as send):
            if expect_error:
                with self.assertRaisesRegex(installer.SetupError, expect_error):
                    installer.prepare_profile(args, lambda _: "old.refresh.token", pair_prompt=pair_code)
            else:
                profile = installer.prepare_profile(args, lambda _: "old.refresh.token", pair_prompt=pair_code)
                self.assertEqual(("SERIAL01", GATEWAY_KEY, "new.refresh.token"),
                                 (profile["serial"], profile["gatewayKey"], profile["refresh"]))
            send.assert_not_called()
        pair_code.assert_called_once()
        requests = [call.args[0] for call in service.open.call_args_list]
        self.assertEqual(ORIGIN + "/v1/pair", requests[0].full_url)
        self.assertEqual({"code": "01234567"}, json.loads(requests[0].data))
        for request in requests[1:]:
            self.assertEqual("GET", request.get_method())
            self.assertEqual("Bearer " + GATEWAY_KEY, request.get_header("Authorization"))
        return [request.full_url.removeprefix(ORIGIN) for request in requests[1:]]

    def test_preparation_accepts_v2_only_and_empty_catalog(self):
        for current_layout, state in [(layout(), home_state()), (layout(rooms=[]), home_state(lights={}))]:
            with self.subTest(layout=current_layout):
                self.assertEqual(["/v2/layout", "/v2/state"], self.prepare_with_service([
                    fake_response(200, current_layout), fake_response(200, state)]))

    def test_preparation_falls_back_to_v1_only_after_gateway_404(self):
        self.assertEqual(["/v2/layout", "/v1/state"], self.prepare_with_service([
            fake_response(404, {"error": "not_found"}),
            fake_response(200, {"vacation": False, "lights": {"relay/K1": False}})]))

    def test_preparation_rejects_v2_errors_without_legacy_fallback_or_redeem_retry(self):
        cases = [
            ([fake_response(404, {"error": "not_found"}, "text/html")], "HTTP 404"),
            ([fake_response(404, {"error": "other"})], "HTTP 404"),
            ([fake_response(503, {"error": "catalog_unavailable"})], "HTTP 503"),
            ([fake_response(200, None)], "неверную схему"),
            ([fake_response(200, layout("serial02"))], "другому контроллеру"),
            ([fake_response(200, layout()), fake_response(200, {**home_state(), "revision": "b" * 64})], "изменилась"),
            ([fake_response(200, layout()), fake_response(200, {**home_state(), "vacation": None})], "состояние дома"),
            ([fake_response(200, layout()), fake_response(200, home_state(0))], "ни одной лампы"),
        ]
        for replies, error in cases:
            with self.subTest(error=error):
                requests = self.prepare_with_service(replies, expect_error=error)
                self.assertEqual(["/v2/layout", "/v2/state"][:len(replies)], requests)

    def test_connects_only_to_the_address_entered_on_the_watch(self):
        replies = [Mock(stdout=b"List of devices attached\n"), Mock(), Mock(stdout=b"device\n")]
        with patch.object(installer, "adb", side_effect=replies) as adb, \
                patch("builtins.input", return_value="192.0.2.15:40455"), patch("builtins.print"):
            self.assertEqual(installer.select_watch(), "192.0.2.15:40455")
            self.assertEqual(adb.call_args_list[1].args, (["adb"], "connect", "192.0.2.15:40455"))

    def test_rejects_cross_host_redirect(self):
        response = Mock(code=302, headers={"Location": "https://external.example/"})
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(installer, "exchange", return_value=response) as exchange:
            with self.assertRaises(installer.SetupError):
                installer.open_service(object(), ORIGIN, "/?grant=example", Mock())
            exchange.assert_called_once()
            self.assertEqual(ORIGIN + "/?grant=example", exchange.call_args.args[1])
            self.assertNotIn("bearer", exchange.call_args.kwargs)

    def test_saves_profile_on_stdin_and_confirms_import(self):
        with patch.object(installer.subprocess, "run", return_value=Mock(returncode=0, stdout=b"online")) as run:
            installer.send_profile(["adb", "-s", "watch"], PROFILE)
        commands = run.call_args_list
        root = Path(__file__).resolve().parents[1]
        self.assertIn(f"applicationId '{installer.PACKAGE}'", (root / "app/build.gradle").read_text())
        self.assertIn(["adb", "-s", "watch", "shell", "am", "start", "-W", "-n",
                       installer.PACKAGE + "/com.galaxywatch.home.HomeActivity", "--ez", "verify_wb_cloud", "true"],
                      [call.args[0] for call in commands])
        profile_write = [call for call in commands if call.kwargs["input"] is not None]
        self.assertEqual(1, len(profile_write))
        self.assertEqual(PROFILE, json.loads(profile_write[0].kwargs["input"]))
        self.assertTrue(any(f"wb-cloud-online-{ID}" in " ".join(call.args[0]) for call in commands))
        for call in commands:
            for value in (PROFILE["refresh"], PROFILE["gatewayKey"]):
                self.assertNotIn(value, " ".join(call.args[0]))

    def test_saved_profile_without_read_is_reported_as_incomplete(self):
        with (patch.object(installer, "adb"),
              patch.object(installer, "app_command"),
              patch.object(installer, "install_status", return_value="imported"),
              patch.object(installer.time, "monotonic", side_effect=(0, 1, 25)),
              patch.object(installer.time, "sleep")):
            with self.assertRaisesRegex(installer.SetupError, "Профиль сохранён"):
                installer.send_profile(["adb"], PROFILE)

    def test_rejects_controller_pagination_outside_cloud_api(self):
        with patch.object(installer, "document", return_value={
            "results": [],
            "next": "https://external.example/api/v1/controllers/",
        }) as get:
            with self.assertRaises(installer.SetupError):
                installer.choose_controller(object(), "example-access", None)
        get.assert_called_once()

    def test_authentication_maps_provider_2fa_error_without_showing_values(self):
        response = MagicMock(code=401)
        response.__enter__.return_value = response
        response.read.return_value = b'{"detail":"Need TOTP for example-password"}'
        with patch.object(installer, "exchange", return_value=response) as exchange:
            with self.assertRaisesRegex(installer.SetupError, "актуальный код двухфакторной защиты") as rejected:
                installer.authenticate(object(), " example@example.test ", "example-password")
        self.assertNotIn("example-password", str(rejected.exception))
        self.assertEqual(installer.API + "auth/token/", exchange.call_args.args[1])
        self.assertEqual("example@example.test", exchange.call_args.kwargs["body"]["email"])

    def test_controller_choice_can_use_dialog(self):
        rows = [{"serialNumber": "SERIAL01"}, {"serialNumber": "SERIAL02"}]
        with patch.object(installer, "document", return_value=rows):
            self.assertEqual("SERIAL02", installer.choose_controller(object(), "example-access", None, lambda _rows: 2))

    def test_missing_putty_session_can_prompt_in_window(self):
        with (patch.object(installer, "putty_pairing_code", return_value=None),
              patch.object(installer.getpass, "getpass") as console):
            self.assertEqual("01234567", installer.pairing_code("192.0.2.1", lambda: " 01234567 "))
        console.assert_not_called()

    def test_private_gateway_401_with_expected_body_requires_service_cookie(self):
        response = Mock(code=401)
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.headers = Message()
        response.headers["Content-Type"] = "application/json; charset=utf-8"
        response.read.return_value = b'{"error":"unauthorized"}'
        with patch.object(installer, "exchange", return_value=response):
            cookies = Mock()
            with self.assertRaises(installer.SetupError):
                installer.open_service(object(), ORIGIN, ORIGIN, cookies)
            cookies.add_cookie_header.side_effect = lambda request: request.add_header("Cookie", "scoped=example")
            installer.open_service(object(), ORIGIN, ORIGIN, cookies)
            response.headers.replace_header("Content-Type", "text/html")
            with self.assertRaises(installer.SetupError):
                installer.open_service(object(), ORIGIN, ORIGIN, cookies)
            response.code = 200
            with self.assertRaises(installer.SetupError):
                installer.open_service(object(), ORIGIN, ORIGIN, cookies)

    def test_resume_does_not_send_initial_refresh_again(self):
        file = Mock()
        with (patch.object(win_prepared, "path", return_value=file),
              patch.object(win_prepared, "lock", return_value=nullcontext()),
              patch.object(win_prepared, "read", return_value={"watch": "a123", "profile": PROFILE}),
              patch.object(installer, "watch_identity", return_value="a123"),
              patch.object(installer, "install"),
              patch.object(installer, "install_status", return_value="imported"),
              patch.object(installer, "send_profile") as send,
              patch.object(installer, "app_command")):
            installer.install_prepared(["adb"], Path("app.apk"))
        send.assert_called_once_with(["adb"], PROFILE, resume=True)
        file.unlink.assert_called_once()

    def test_existing_cloud_receipt_leaves_the_selected_route_alone(self):
        file = Mock()
        with (patch.object(win_prepared, "path", return_value=file),
              patch.object(win_prepared, "lock", return_value=nullcontext()),
              patch.object(win_prepared, "read", return_value={"watch": "a123", "profile": PROFILE}),
              patch.object(installer, "watch_identity", return_value="a123"),
              patch.object(installer, "install_status", return_value="online"),
              patch.object(installer, "adb") as adb, patch.object(installer, "app_command")):
            installer.install_prepared(["adb"], Path("app.apk"))
        adb.assert_called_once_with(["adb"], "shell", "am", "start", "-W", "-n", installer.ACTIVITY)
        file.unlink.assert_called_once()

    def test_unknown_result_never_resends_bound_profile(self):
        file = Mock()
        with (patch.object(win_prepared, "path", return_value=file),
              patch.object(win_prepared, "lock", return_value=nullcontext()),
              patch.object(win_prepared, "read", return_value={"watch": "a123", "profile": PROFILE}),
              patch.object(installer, "watch_identity", return_value="a123"),
              patch.object(installer, "install"),
              patch.object(installer, "install_status", return_value="absent"),
              patch.object(installer, "send_profile") as send):
            with self.assertRaisesRegex(installer.SetupError, "Подготовьте доступ заново"):
                installer.install_prepared(["adb"], Path("app.apk"))
        send.assert_not_called()
        file.unlink.assert_not_called()

    def test_prepare_keeps_existing_profile_unless_replacement_is_explicit(self):
        file = Mock()
        with (patch.object(win_prepared, "path", return_value=file),
              patch.object(win_prepared, "lock", return_value=nullcontext()),
              patch.object(win_prepared, "write") as write,
              patch.object(installer, "prepare_profile") as prepare):
            with self.assertRaisesRegex(installer.SetupError, "уже сохранён"):
                installer.save_prepared(object())
        prepare.assert_not_called()
        write.assert_not_called()

    def test_unshared_putty_never_opens_remote_command(self):
        with (patch.object(installer.shutil, "which", return_value="plink.exe"),
              patch.object(installer.subprocess, "run", return_value=Mock(returncode=1)) as run):
            self.assertIsNone(installer.putty_pairing_code("192.0.2.10"))
        self.assertEqual(["plink.exe", "-batch", "-shareexists", "root@192.0.2.10"], run.call_args.args[0])
        run.assert_called_once()

    @unittest.skipUnless(os.name == "nt", "защищённое хранилище доступно в Windows")
    def test_prepared_profile_is_encrypted_with_dpapi(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "prepared.dpapi"
            with win_prepared.lock(file):
                win_prepared.write(file, PROFILE)
                self.assertNotIn(PROFILE["refresh"].encode(), file.read_bytes())
                self.assertEqual(PROFILE, win_prepared.read(file))
