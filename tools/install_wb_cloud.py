"""Установка пульта на часы и привязка к опубликованному шлюзу WB Cloud."""

import argparse
import getpass
import http.cookiejar
import ipaddress
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import warnings

import win_prepared

API = "https://wirenboard.cloud/api/v1/"
PACKAGE = "com.galaxywatch.home.sample"
ACTIVITY = PACKAGE + "/com.galaxywatch.home.HomeActivity"
BOOTSTRAP = "no_backup/wb-cloud.json"
ONLINE_PREFIX = "no_backup/wb-cloud-online-"


class SetupError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def client(cookie_jar=None):
    return urllib.request.build_opener(
        NoRedirect(), urllib.request.HTTPCookieProcessor(
            http.cookiejar.CookieJar() if cookie_jar is None else cookie_jar
        )
    )


def exchange(opener, url, *, body=None, bearer=None):
    payload = None
    headers = {"Accept": "application/json"}
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    req = urllib.request.Request(url, headers=headers, data=payload)
    try:
        return opener.open(req, timeout=12)
    except urllib.error.HTTPError as response:
        return response


def document(opener, url, message, *, body=None, bearer=None):
    with exchange(opener, url, body=body, bearer=bearer) as response:
        if response.code != 200:
            raise SetupError(f"{message} (HTTP {response.code})")
        data = response.read(65537)
        if len(data) > 65536:
            raise SetupError(message)
        return json.loads(data)


def tunnel_url(origin, link, base=None):
    if not isinstance(link, str) or re.search(r"[\x00-\x20\x7f\\]", link):
        raise SetupError("WB Cloud выдал недопустимый адрес сервиса")
    url = urllib.parse.urljoin(base or origin, link)
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname != urllib.parse.urlsplit(origin).hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.port not in (None, 443) or parsed.fragment):
        raise SetupError("WB Cloud выдал недопустимый адрес сервиса")
    return url


def gateway_challenge(response):
    if response.code != 401 or response.headers.get_content_type() != "application/json":
        return False
    try:
        return json.loads(response.read(512)) == {"error": "unauthorized"}
    except (ValueError, UnicodeError):
        return False


def open_service(transport, origin, signed_link, cookie_jar):
    url = tunnel_url(origin, signed_link)
    for _ in range(5):
        with exchange(transport, url) as response:
            if 300 <= response.code < 400 and response.headers.get("Location"):
                url = tunnel_url(origin, response.headers["Location"], url)
            else:
                target = urllib.request.Request(origin + "/v1/state")
                cookie_jar.add_cookie_header(target)
                gateway_response = (response.code in (200, 404)
                                    and response.headers.get_content_type() == "application/json")
                if target.has_header("Cookie") and (gateway_response or gateway_challenge(response)):
                    return
                raise SetupError(f"Сервис на контроллере недоступен (HTTP {response.code})")
    raise SetupError("WB Cloud не завершил подключение к сервису")


def choose_controller(opener, access, preferred, prompt=None):
    url = API + "controllers/"
    serials = []
    for _ in range(100):
        data = document(opener, url, "Список контроллеров недоступен", bearer=access)
        rows = data if isinstance(data, list) else data["results"]
        for row in rows:
            if re.fullmatch(r"[A-Za-z0-9]{6,32}", row["serialNumber"]):
                serials.append(row["serialNumber"])
        url = None if isinstance(data, list) else data.get("next")
        if not url:
            break
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.netloc != "wirenboard.cloud" or parsed.path != "/api/v1/controllers/":
            raise SetupError("Не удалось получить список контроллеров")
    else:
        raise SetupError("Слишком много страниц контроллеров в аккаунте")
    if preferred:
        if preferred.upper() not in {value.upper() for value in serials}:
            raise SetupError("Этот контроллер отсутствует в аккаунте")
        return next(serial for serial in serials if serial.upper() == preferred.upper())
    if not serials:
        raise SetupError("В аккаунте нет подключённых контроллеров")
    if len(serials) == 1:
        return serials[0]
    if prompt is not None:
        index = prompt(serials)
        if index is None:
            raise SetupError("Подготовка отменена")
        return serials[index - 1]
    for index, serial in enumerate(serials, start=1):
        print(f"{index}. {serial}")
    choice = input("Номер домашнего контроллера: ").strip()
    if not choice.isascii() or not choice.isdigit() or not 1 <= int(choice) <= len(serials):
        raise SetupError("Выберите номер контроллера из списка")
    return serials[int(choice) - 1]


def valid_jwt(value):
    return isinstance(value, str) and len(value) <= 4096 and re.fullmatch(
        r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", value
    )


def authenticate(opener, email, password, code=""):
    email = email.strip()
    code = code.strip()
    if not email or not password:
        raise SetupError("Введите почту и пароль WB Cloud")
    if code and not re.fullmatch(r"[0-9]{6}", code):
        raise SetupError("Код двухфакторной защиты должен содержать 6 цифр")
    credentials = {"email": email, "password": password}
    if code:
        credentials["totpCode"] = code
    with exchange(opener, API + "auth/token/", body=credentials) as response:
        data = response.read(16385)
        try:
            tokens = json.loads(data) if len(data) <= 16384 else None
        except (ValueError, UnicodeError):
            tokens = None
        if not isinstance(tokens, dict):
            tokens = {}
        if response.code != 200:
            provider = f"{tokens.get('code', '')} {tokens.get('detail', '')}".lower()
            if response.code == 429:
                raise SetupError("WB Cloud ограничил число попыток входа. Повторите позже")
            if "totpCode" in tokens or any(word in provider for word in ("totp", "two-factor", "two_factor", "mfa")):
                raise SetupError("WB Cloud запрашивает актуальный код двухфакторной защиты")
            if response.code == 401:
                raise SetupError("WB Cloud не принял данные входа. Проверьте почту, пароль и код 2FA")
            raise SetupError(f"WB Cloud отклонил вход (HTTP {response.code})")
    refresh = tokens.get("refresh")
    if not valid_jwt(refresh):
        raise SetupError("WB Cloud не выдал сессию для часов")
    return refresh


def login(opener):
    email = input("Email аккаунта WB Cloud: ")
    password = getpass.getpass("Пароль WB Cloud: ")
    code = getpass.getpass("Код из приложения 2FA, если включена двухфакторная защита: ")
    return authenticate(opener, email, password, code)


def putty_pairing_code(address):
    plink = shutil.which("plink")
    if not plink:
        return None
    target = f"root@{address}"
    command = (
        'file=/run/wb-wear-light-gateway/pair-code; old=$(cat "$file" 2>/dev/null); '
        'systemctl kill --kill-who=main --signal=SIGUSR1 wb-wear-light-gateway >/dev/null || exit 1; '
        'for attempt in 1 2 3 4 5 6 7 8 9 10; do '
        'code=$(cat "$file" 2>/dev/null); '
        'if [ -n "$code" ] && [ "$code" != "$old" ]; then printf "%s\\n" "$code"; exit 0; fi; '
        'sleep 0.2; done; exit 1'
    )
    try:
        existing = subprocess.run([plink, "-batch", "-shareexists", target],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, timeout=3, check=False)
        if existing.returncode:
            return None
        # The failing local proxy prevents a fresh network connection if PuTTY closes meanwhile.
        result = subprocess.run([plink, "-batch", "-ssh", "-noagent", "-T", "-share",
                                 "-proxycmd", "cmd.exe /d /c exit 1", target, command],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=7, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    output = result.stdout.strip()
    return output.decode("ascii") if result.returncode == 0 and re.fullmatch(rb"[0-9]{8}", output) else None


def pairing_code(address, prompt=None):
    code = putty_pairing_code(address) if address else None
    if code is None:
        if prompt is not None:
            code = prompt()
            if code is None:
                raise SetupError("Подготовка отменена")
        else:
            if address:
                print("Общая сессия PuTTY недоступна. Можно ввести код вручную.")
            code = getpass.getpass("Восьмизначный код привязки со шлюза: ")
        code = code.strip()
    if not re.fullmatch(r"[0-9]{8}", code):
        raise SetupError("Введите 8 цифр с контроллера")
    return code


def adb(commands, *args, payload=None, check=True):
    completed = subprocess.run(
        [*commands, *args], input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=120, check=False
    )
    if check and completed.returncode:
        raise SetupError("Команда ADB не выполнена. Проверьте подключение часов и совместимость APK")
    return completed


def app_command(commands, command, *, payload=None, check=True):
    return adb(commands, "shell", "-T", f"run-as {PACKAGE} sh -c {shlex.quote(command)}",
               payload=payload, check=check)


def select_watch():
    rows = adb(["adb"], "devices").stdout.decode("utf-8").splitlines()[1:]
    online = [row.split()[0] for row in rows if len(row.split()) == 2 and row.split()[1] == "device"]
    if len(online) == 1:
        return online[0]
    if online:
        for index, serial in enumerate(online, 1):
            print(f"{index}. {serial}")
        choice = input("Номер подключённых часов: ").strip()
        if choice.isascii() and choice.isdigit() and 1 <= int(choice) <= len(online):
            return online[int(choice) - 1]
        raise SetupError("Выберите часы из списка подключённых устройств")
    print("На часах откройте «Для разработчиков → Беспроводная отладка».")
    target = input("IP-адрес и порт с основного экрана отладки: ").strip()
    host, separator, port = target.rpartition(":")
    if not separator or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise SetupError("Нужны IP-адрес и порт с экрана часов")
    try:
        address = ipaddress.IPv4Address(host)
    except ValueError:
        raise SetupError("Нужен IPv4-адрес с экрана часов") from None
    target = f"{address}:{int(port)}"
    adb(["adb"], "connect", target, check=False)
    if adb(["adb", "-s", target], "get-state", check=False).stdout.strip() != b"device":
        raise SetupError("Часы недоступны. Проверьте Wi-Fi и порт. При первом подключении выполните сопряжение ADB с этим ПК.")
    return target


def install(commands, apk):
    features = adb(commands, "shell", "getprop", "ro.build.characteristics").stdout.strip().split(b",")
    if b"watch" not in features:
        raise SetupError("Выбранное устройство не определилось как часы")
    adb(commands, "install", "-r", str(apk.resolve()))
    app_command(commands, "true")


def install_id(profile):
    value = profile.get("installId")
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{32}", value):
        raise SetupError("Номер подготовленной привязки повреждён")
    return value


def watch_identity(commands):
    probe = adb(commands, "shell", "settings", "get", "secure", "android_id").stdout.strip()
    if not re.fullmatch(rb"[a-f0-9]{1,16}", probe):
        raise SetupError("Не удалось определить, какие часы подключены")
    return probe.decode("ascii")


def install_status(commands, profile):
    identifier = install_id(profile)
    preference = shlex.quote(f'<string name="wb_cloud_import_id">{identifier}</string>')
    command = (
        f"if grep -Fq {preference} shared_prefs/home.xml 2>/dev/null; "
        f"then if test -f {ONLINE_PREFIX}{identifier} && test ! -f {BOOTSTRAP}; "
        "then echo online; else echo imported; fi; else echo absent; fi"
    )
    status = app_command(commands, command).stdout.strip()
    if status not in (b"online", b"imported", b"absent"):
        raise SetupError("Часы не ответили на проверку привязки")
    return status.decode("ascii")


def send_profile(commands, profile, *, resume=False):
    temporary = BOOTSTRAP + ".tmp"
    try:
        if not resume:
            adb(commands, "shell", "am", "force-stop", PACKAGE)
            app_command(commands, f"umask 077 && mkdir -p no_backup && rm -f {temporary}"
                        f" && cat > {temporary} && mv {temporary} {BOOTSTRAP}",
                        payload=json.dumps(profile).encode("utf-8"))
        adb(commands, "shell", "am", "start", "-W", "-n", ACTIVITY)
        deadline = time.monotonic() + 24
        while time.monotonic() < deadline:
            if install_status(commands, profile) == "online":
                return
            time.sleep(0.5)
        if install_status(commands, profile) == "imported":
            raise SetupError("Профиль сохранён. Часы пока не подтвердили чтение света через WB Cloud")
        raise SetupError("Часы не подтвердили сохранение доступа")
    finally:
        if not resume:
            app_command(commands, f"rm -f {temporary} {BOOTSTRAP}", check=False)


def install_prepared(commands, apk):
    file = win_prepared.path()
    with win_prepared.lock(file):
        if not file.is_file():
            raise SetupError("На этом ПК пока нет подготовленного доступа")
        saved = win_prepared.read(file)
        profile = saved["profile"]
        identifier = install_id(profile)
        identity = watch_identity(commands)
        bound = saved["watch"]
        if bound is not None and bound != identity:
            raise SetupError("Эта привязка уже начата на других часах. Подключите те же часы")
        if bound is None:
            install(commands, apk)
        status = install_status(commands, profile)
        if status == "online":
            adb(commands, "shell", "am", "start", "-W", "-n", ACTIVITY)
        else:
            if status == "imported":
                send_profile(commands, profile, resume=True)
            elif bound is None:
                # Keep custody before transmission. A retry must never present a rotated refresh again.
                win_prepared.write(file, {"profile": profile, "watch": identity})
                send_profile(commands, profile)
            else:
                raise SetupError("Прежняя установка прервалась до подтверждения. Подготовьте доступ заново")
        file.unlink()
        app_command(commands, f"rm -f {ONLINE_PREFIX}{identifier}", check=False)


def prepare_profile(args, login_prompt=None, *, controller_prompt=None, pair_prompt=None):
    refresh = (login_prompt or login)(client())
    cloud = client()
    renewed = document(cloud, API + "auth/token/refresh/", "WB Cloud не продлил доступ", body={"refresh": refresh})
    access, refresh = renewed.get("access"), renewed.get("refresh", refresh)
    if not valid_jwt(access) or not valid_jwt(refresh):
        raise SetupError("WB Cloud не выдал сессию для часов")
    serial = choose_controller(cloud, access, args.controller, controller_prompt)
    controller_api = f"{API}controllers/{serial}/"
    services = document(cloud, controller_api + "services/", "Список сервисов контроллера недоступен", bearer=access)
    services = services if isinstance(services, list) else services["results"]
    if not any(service["port"] == args.port for service in services):
        raise SetupError(f"Добавьте шлюз на порту {args.port} в «Сервисы» контроллера в WB Cloud")

    link = document(cloud, f"{controller_api}tcp-tunnels/{args.port}/", "WB Cloud не открыл сервис",
                    body={}, bearer=access)["tunnelRedirectUrl"]
    cookies = http.cookiejar.CookieJar()
    service = client(cookies)
    origin = f"https://{serial.lower()}-{args.port}.apps.wirenboard.cloud"
    open_service(service, origin, link, cookies)
    code = pairing_code(args.pair_with_putty, pair_prompt)
    key = document(service, origin + "/v1/pair", "Шлюз не принял код привязки", body={"code": code})["token"]
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", key):
        raise SetupError("Шлюз вернул неверный ключ")
    state = document(service, origin + "/v1/state", "Шлюз не ответил на проверку состояния", bearer=key)
    if "vacation" not in state or not isinstance(state.get("lights"), dict) or not state["lights"]:
        raise SetupError("Шлюз вернул неверное состояние")
    return {"serial": serial, "port": args.port, "refresh": refresh, "gatewayKey": key, "installId": uuid.uuid4().hex}


def save_prepared(args, login_prompt=None, *, replace=False, controller_prompt=None, pair_prompt=None):
    prepared = win_prepared.path()
    with win_prepared.lock(prepared):
        if prepared.is_file() and not replace:
            raise SetupError("Доступ уже сохранён на этом ПК")
        profile = prepare_profile(args, login_prompt, controller_prompt=controller_prompt, pair_prompt=pair_prompt)
        win_prepared.write(prepared, {"profile": profile, "watch": None})


def prepare_desktop(args):
    from prepare_wb_cloud import prepare_window

    if not args.replace_prepared and win_prepared.path().is_file():
        raise SetupError("Доступ уже сохранён на этом ПК")

    def prepare(email, password, otp, address, controller_prompt, pair_prompt):
        try:
            args.pair_with_putty = ipaddress.IPv4Address(address) if address else None
            if args.pair_with_putty and (not args.pair_with_putty.is_private or args.pair_with_putty.is_unspecified):
                raise ValueError()
        except ValueError:
            return "Введите локальный IPv4-адрес контроллера или оставьте поле пустым"
        try:
            save_prepared(args, lambda opener: authenticate(opener, email, password, otp),
                          replace=args.replace_prepared, controller_prompt=controller_prompt, pair_prompt=pair_prompt)
        except SetupError as error:
            return str(error)
        except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
            return "Не удалось подготовить доступ. Проверьте связь с WB Cloud и домашним контроллером"
        return None

    if not prepare_window(prepare, str(args.pair_with_putty or "")):
        raise SetupError("Подготовка отменена")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="?")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--prepare", action="store_true", help="сохранить доступ в Windows до появления часов")
    modes.add_argument("--install-prepared", action="store_true", help="установить с ранее сохранённым доступом")
    parser.add_argument("--replace-prepared", action="store_true", help="явно заменить сохранённый доступ после проверки")
    parser.add_argument("--pair-with-putty", type=ipaddress.IPv4Address, metavar="IP",
                        help="получить код через уже открытое соединение PuTTY с контроллером")
    parser.add_argument("--watch", help="адрес или имя устройства из adb devices")
    parser.add_argument("--controller", help="серийный номер контроллера в WB Cloud")
    parser.add_argument("--port", type=int, default=8765, help="порт опубликованного сервиса шлюза")
    args = parser.parse_args()
    if args.replace_prepared and not args.prepare:
        parser.error("--replace-prepared требует --prepare")
    if not args.prepare and (args.apk is None or not args.apk.is_file()):
        parser.error("укажите существующий APK")
    if not 1024 <= args.port <= 65535:
        parser.error("укажите порт от 1024 до 65535")
    if args.pair_with_putty and (not args.pair_with_putty.is_private or args.pair_with_putty.is_unspecified):
        parser.error("укажите локальный IPv4-адрес контроллера")

    warnings.simplefilter("error", getpass.GetPassWarning)
    if args.prepare:
        prepare_desktop(args)
        print("Доступ подготовлен для этого пользователя Windows. Часы можно подключить позже.")
        return

    watch = args.watch or select_watch()
    commands = ["adb", "-s", watch]
    if args.install_prepared:
        install_prepared(commands, args.apk)
    else:
        install(commands, args.apk)
        profile = prepare_profile(args)
        send_profile(commands, profile)
        app_command(commands, f"rm -f {ONLINE_PREFIX}{install_id(profile)}", check=False)
    print("Готово. Часы прочитали состояние света через WB Cloud.")


if __name__ == "__main__":
    try:
        main()
    except SetupError as error:
        sys.exit(str(error))
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError, getpass.GetPassWarning):
        sys.exit("Не удалось закончить настройку. Проверьте связь с часами и WB Cloud.")
    except (KeyboardInterrupt, EOFError):
        sys.exit("Установка прервана.")
