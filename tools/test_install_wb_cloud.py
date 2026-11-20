from contextlib import nullcontext
from email.message import Message
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import install_wb_cloud as installer
import win_prepared


ORIGIN = "https://serial01-8765.apps.wirenboard.cloud"
ID = "0a" * 16
PROFILE = {"refresh": "example.refresh.token", "gatewayKey": "example-gateway", "installId": ID}


class InstallerTest(unittest.TestCase):
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
