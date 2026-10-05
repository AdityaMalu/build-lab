# Sandbox probe (CI only): imported by the code under test; every line must say BLOCKED or OK.
import os
import socket
import subprocess
import tempfile


def _probe(name, fn):
    try:
        fn()
        print("PROBE ALLOWED:", name, flush=True)
    except BaseException as e:  # noqa: BLE001
        print("PROBE BLOCKED:", name, "-", type(e).__name__, e, flush=True)


def _fork():
    pid = os.fork()
    if pid == 0:
        os._exit(0)


_probe("read /etc/shadow", lambda: open("/etc/shadow").read())
_probe("write into the app", lambda: open("/app/web/pwned.txt", "w").write("x"))
_probe("internet socket", lambda: socket.create_connection(("1.1.1.1", 80), timeout=3))
_probe("start a process", lambda: subprocess.run(["/bin/echo", "hi"], check=True))
_probe("fork", _fork)
_probe("signal pid 1", lambda: os.kill(1, 0))

try:
    with tempfile.NamedTemporaryFile() as f:
        f.write(b"ok")
    print("PROBE OK: write own temp file", flush=True)
except BaseException as e:  # noqa: BLE001
    print("PROBE BROKEN: own temp file", e, flush=True)
