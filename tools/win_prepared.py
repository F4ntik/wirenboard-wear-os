"""Хранилище подготовленного профиля для текущего пользователя Windows."""

import contextlib
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import tempfile


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.c_void_p)]


def path():
    directory = Path(os.environ["LOCALAPPDATA"])
    if os.name != "nt" or not directory.is_absolute():
        raise OSError("Windows LocalAppData required")
    return directory / "WirenBoardWearSample" / "prepared.dpapi"


def dpapi(data, protect):
    if not data:
        raise ValueError()
    input_buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.addressof(input_buffer))
    target = Blob()
    dll = ctypes.WinDLL("crypt32.dll", use_last_error=True)
    local_free = ctypes.WinDLL("kernel32.dll").LocalFree
    local_free.argtypes = [ctypes.c_void_p]
    local_free.restype = ctypes.c_void_p
    func = dll.CryptProtectData if protect else dll.CryptUnprotectData
    func.argtypes = [
        ctypes.POINTER(Blob), ctypes.c_wchar_p if protect else ctypes.c_void_p,
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob),
    ]
    func.restype = wintypes.BOOL
    try:
        # CRYPTPROTECT_UI_FORBIDDEN. Omitting LOCAL_MACHINE binds the data to CurrentUser.
        if not func(ctypes.byref(source), "Wiren Board Wear" if protect else None,
                    None, None, None, 0x01, ctypes.byref(target)):
            raise ctypes.WinError(ctypes.get_last_error())
        return ctypes.string_at(target.data, target.size)
    finally:
        ctypes.memset(input_buffer, 0, ctypes.sizeof(input_buffer))
        if target.data:
            ctypes.memset(target.data, 0, target.size)
            local_free(target.data)


@contextlib.contextmanager
def lock(file):
    import msvcrt

    file.parent.mkdir(parents=True, exist_ok=True)
    with file.with_suffix(".lock").open("a+b") as handle:
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)


def read(file):
    with file.open("rb") as stream:
        data = stream.read(65537)
    if len(data) > 65536:
        raise ValueError()
    return json.loads(dpapi(data, protect=False))


def write(file, profile):
    payload = dpapi(json.dumps(profile).encode("utf-8"), protect=True)
    with tempfile.NamedTemporaryFile(prefix="prepared-", suffix=".tmp", dir=file.parent, delete=False) as temporary:
        pending = Path(temporary.name)
        try:
            temporary.write(payload)
            temporary.flush()
            os.fsync(temporary.fileno())
        except OSError:
            temporary.close()
            pending.unlink(missing_ok=True)
            raise
    try:
        os.replace(pending, file)
    finally:
        pending.unlink(missing_ok=True)
