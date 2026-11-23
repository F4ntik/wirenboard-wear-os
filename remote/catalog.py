"""Validate the controller's light configuration and build the public catalog."""

import hashlib
import json
import re
import sys
from pathlib import Path


MAX_CATALOG = 64 * 1024
MAX_ROOMS = 32
MAX_LIGHTS = 128
CONFIG_FILE = Path("/etc/wb-wear-light-gateway.conf")
SERIAL_FILE = Path("/var/lib/wirenboard/short_sn.conf")
SERIAL_TOPIC = "/devices/system/controls/Short SN"
GATEWAY_CONTROLS = "/devices/wb-wear-light-gateway/controls/"
CATALOG_TOPIC = GATEWAY_CONTROLS + "Catalog"
REVISION_TOPIC = GATEWAY_CONTROLS + "CatalogRevision"
ROOM_THEMES = {"living", "kitchen", "bedroom", "hall", "kids", "office", "wc", "shower", "generic"}
LIGHT_THEMES = {"pendant", "wardrobe", "sconce", "kitchen", "entry", "track", "mirror", "shower", "plain"}
TYPES = {"power": "switch", "brightness": "range", "color": "rgb", "vacation": "switch", "allOff": "pushbutton"}


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


def reject_constant(value):
    raise ValueError(f"invalid JSON number: {value}")


def decode_json(data):
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_keys, parse_constant=reject_constant)


def wire_json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def read_settings(path):
    with path.open("rb") as source:
        data = source.read(MAX_CATALOG + 1)
    return decode_settings(data)


def decode_settings(data, *, from_editor=False):
    if len(data) > MAX_CATALOG:
        raise ValueError("gateway configuration exceeds 64 KiB")
    settings = decode_json(data)
    if not isinstance(settings, dict) or type(settings.get("enabled")) is not bool:
        raise ValueError("gateway configuration requires a boolean enabled setting")
    if from_editor:
        arrange_editor_groups(settings, for_editor=False)
    if settings.keys() - {"enabled", "rooms", "vacation", "allOff"}:
        raise ValueError("unknown gateway setting")
    if "rooms" not in settings and settings.keys() != {"enabled"}:
        raise ValueError("gateway catalog requires rooms")
    return settings


def read_serial(path):
    try:
        with path.open("rb") as source:
            value = source.read(128)
            if source.read(1):
                return None
        value = value.decode("ascii").strip()
    except (OSError, UnicodeError):
        return None
    return value if re.fullmatch(r"[A-Za-z0-9]{6,32}", value) else None


def topic(value):
    if not isinstance(value, str) or not re.fullmatch(r"[^/+#\x00-\x1f\x7f]+/[^/+#\x00-\x1f\x7f]+", value):
        raise ValueError("select a control in device/control form")
    device, control = value.split("/")
    full = f"/devices/{device}/controls/{control}"
    if len(full.encode("utf-8")) > 256 or full.startswith(GATEWAY_CONTROLS) or full == SERIAL_TOPIC:
        raise ValueError("invalid lighting control")
    return full


def display_name(value):
    if (not isinstance(value, str) or not value.strip() or len(value) > 80
            or re.search(r"[\x00-\x1f\x7f]", value)):
        raise ValueError("enter a name of 1 to 80 characters")
    return value.strip()


def stable_id(value, prefix, key):
    if value in (None, ""):
        return prefix + "-" + hashlib.sha256(wire_json(key)).hexdigest()[:32]
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,48}", value):
        raise ValueError("invalid catalog identifier")
    return value


def theme(value, allowed):
    if not isinstance(value, str) or value not in allowed:
        raise ValueError("invalid catalog theme")
    return value


def decode_color(value):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,3};[0-9]{1,3};[0-9]{1,3}", value):
        values = [int(part) for part in value.split(";")]
        if all(part <= 255 for part in values):
            return ";".join(map(str, values))
    return None


def decode_state(kind, payload):
    if kind in ("power", "vacation"):
        return {b"0": False, b"1": True}.get(payload)
    if kind == "brightness" and re.fullmatch(rb"[0-9]{1,3}(?:\.[0-9]{1,8})?", payload):
        value = float(payload)
        if 0 <= value <= 100 and value.is_integer():
            return int(value)
    if kind == "color" and len(payload) <= 11:
        return decode_color(payload.decode("ascii", errors="replace"))
    return None


def valid_metadata(kind, meta):
    if not isinstance(meta, dict) or meta.get("type") != TYPES[kind]:
        return False
    if kind != "vacation" and meta.get("readonly", False) is not False:
        return False
    if kind == "brightness":
        minimum, maximum = meta.get("min", 0), meta.get("max")
        return type(minimum) in (int, float) and minimum == 0 and type(maximum) in (int, float) and maximum == 100
    return True


class Catalog:
    def __init__(self, settings, serial):
        rooms = settings["rooms"]
        if not isinstance(rooms, list) or len(rooms) > MAX_ROOMS:
            raise ValueError("catalog must contain at most 32 rooms")
        self.serial = serial
        self.lights = {}
        self.controls = {}
        self.rooms = []
        room_ids = set()
        for room in rooms:
            entry = self.read_room(room)
            if entry["id"] in room_ids:
                raise ValueError("duplicate room identifier")
            room_ids.add(entry["id"])
            self.rooms.append(entry)
        self.vacation = self.optional_control("vacation", settings.get("vacation"))
        self.master = self.optional_control("allOff", settings.get("allOff"))
        self.layout = {"schema": 1, "controller": serial, "rooms": self.rooms}
        if self.vacation:
            self.layout["vacation"] = self.vacation
        if self.master:
            self.layout["allOff"] = self.master
        self.revision = hashlib.sha256(wire_json(self.layout)).hexdigest()
        self.layout["revision"] = self.revision
        self.body = wire_json(self.layout)
        if len(self.body) > MAX_CATALOG:
            raise ValueError("serialized catalog exceeds 64 KiB")

    def read_room(self, room):
        if (not isinstance(room, dict) or room.keys() - {"id", "label", "theme", "lights"}
                or not isinstance(room.get("lights"), list) or not room["lights"]):
            raise ValueError("a room needs a name and at least one light")
        if len(self.lights) + len(room["lights"]) > MAX_LIGHTS:
            raise ValueError("catalog must contain at most 128 lights")
        lights = [self.read_light(light) for light in room["lights"]]
        identity = stable_id(room.get("id"), "room", sorted(light["power"] for light in lights))
        return {"id": identity, "label": display_name(room.get("label")),
                "theme": theme(room.get("theme", "generic"), ROOM_THEMES), "lights": lights}

    def read_light(self, light):
        if not isinstance(light, dict) or light.keys() - {"id", "label", "theme", "power", "brightness", "color"}:
            raise ValueError("invalid light configuration")
        power = self.add_control("power", light.get("power"))
        identity = stable_id(light.get("id"), "light", power)
        if identity in self.lights:
            raise ValueError("duplicate light identifier")
        entry = {"id": identity, "label": display_name(light.get("label")), "power": power,
                 "theme": theme(light.get("theme", "plain"), LIGHT_THEMES)}
        for kind in ("brightness", "color"):
            control = self.optional_control(kind, light.get(kind))
            if control:
                entry[kind] = control
        self.lights[identity] = entry
        return entry

    def optional_control(self, kind, value):
        return self.add_control(kind, value) if value not in (None, "") else None

    def add_control(self, kind, value):
        control = topic(value)
        if control in self.controls:
            raise ValueError("a control can only appear once in the catalog")
        self.controls[control] = kind
        return control

    def command(self, request):
        if not isinstance(request, dict):
            raise ApiError(400, "invalid_command")
        revision = request.get("revision")
        if not isinstance(revision, str) or not re.fullmatch(r"[a-f0-9]{64}", revision):
            raise ApiError(400, "invalid_command")
        if revision != self.revision:
            raise ApiError(409, "catalog_changed")
        if request.keys() == {"revision", "allOff"} and request["allOff"] is True:
            return {light["power"]: False for light in self.lights.values()}, self.master
        changes = request.get("changes")
        if request.keys() != {"revision", "changes"} or not isinstance(changes, dict) or not changes:
            raise ApiError(400, "invalid_command")
        targets = {}
        for identity, attrs in changes.items():
            if identity not in self.lights or not isinstance(attrs, dict) or len(attrs) != 1:
                raise ApiError(400, "invalid_command")
            kind, value = next(iter(attrs.items()))
            if kind not in ("power", "brightness", "color") or kind not in self.lights[identity]:
                raise ApiError(400, "invalid_command")
            if kind == "color":
                value = decode_color(value)
            if ((kind == "power" and type(value) is bool)
                    or (kind == "brightness" and type(value) is int and 0 <= value <= 100)
                    or (kind == "color" and value is not None)):
                targets[self.lights[identity][kind]] = value
            else:
                raise ApiError(400, "invalid_command")
        return targets, None


def persist_editor_ids(settings, prior):
    if "rooms" not in settings:
        return settings
    # Build the projections first, so invalid edits never replace the active config.
    draft = Catalog(settings, None)
    try:
        current = Catalog(prior, None) if "rooms" in prior else None
    except ValueError:
        current = None
    used_room_ids = {room["id"] for room in settings["rooms"] if room.get("id")}
    previous_lights = {light["power"]: light["id"] for light in current.lights.values()} if current else {}
    new_powers = [{light["power"] for light in room["lights"]} for room in draft.rooms]
    previous_rooms = [(room["id"], {light["power"] for light in room["lights"]})
                      for room in current.rooms if room["id"] not in used_room_ids] if current else []
    for room, projected, powers in zip(settings["rooms"], draft.rooms, new_powers):
        if not room.get("id"):
            candidates = [identity for identity, previous in previous_rooms
                          if previous & powers and sum(bool(previous & group) for group in new_powers) == 1]
            room["id"] = candidates[0] if len(candidates) == 1 else projected["id"]
        for light, result in zip(room["lights"], projected["lights"]):
            if not light.get("id"):
                light["id"] = previous_lights.get(result["power"], result["id"])
    Catalog(settings, None)
    return settings


def move_editor_fields(entry, group, fields, *, for_editor):
    if not isinstance(entry, dict):
        raise ValueError("invalid catalog settings")
    if for_editor:
        entry[group] = {field: entry.pop(field) for field in fields if field in entry}
    elif group in entry:
        options = entry.pop(group)
        if not isinstance(options, dict) or options.keys() - set(fields) or options.keys() & entry.keys():
            raise ValueError("invalid additional settings")
        entry.update(options)


def arrange_editor_groups(settings, *, for_editor):
    if "rooms" in settings or (not for_editor and "house" in settings):
        move_editor_fields(settings, "house", ("vacation", "allOff"), for_editor=for_editor)
    rooms = settings.get("rooms", [])
    if not isinstance(rooms, list):
        raise ValueError("rooms must be a list")
    for room in rooms:
        move_editor_fields(room, "advanced", ("theme",), for_editor=for_editor)
        lights = room.get("lights", [])
        if not isinstance(lights, list):
            raise ValueError("lights must be a list")
        for light in lights:
            move_editor_fields(light, "advanced", ("theme", "brightness", "color"), for_editor=for_editor)


def editor_main():
    for_editor = sys.argv[1:] == ["--to-editor"]
    try:
        settings = decode_settings(sys.stdin.buffer.read(MAX_CATALOG + 1), from_editor=not for_editor)
        if for_editor:
            arrange_editor_groups(settings, for_editor=True)
        else:
            try:
                prior = read_settings(CONFIG_FILE)
            except (OSError, ValueError, RecursionError):
                prior = {}
            persist_editor_ids(settings, prior)
        body = json.dumps(settings, ensure_ascii=False, indent=2).encode("utf-8")
        if not for_editor and len(body) + 1 > MAX_CATALOG:
            raise ValueError("gateway configuration exceeds 64 KiB")
        sys.stdout.buffer.write(body + b"\n")
    except (ValueError, RecursionError) as error:
        sys.exit(f"Неверная конфигурация пульта Wear OS ({error})")


if __name__ == "__main__":
    editor_main()
