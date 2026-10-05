import hmac
import threading
from datetime import datetime, timedelta, timezone

from .users import hash_password

MAX_ATTEMPTS = 5


def _int_setting(config, key, default, lo, hi):
    raw = config.get(key, default)
    try:
        value = int(str(raw).strip())
    except ValueError:
        raise ValueError(f"{key} must be a number") from None
    if not lo <= value <= hi:
        raise ValueError(f"{key} must be {lo}..{hi}")
    return value


class _Pending:
    __slots__ = ("code", "expires_at", "failures")

    def __init__(self, code, expires_at):
        self.code, self.expires_at, self.failures = code, expires_at, 0


class PasswordResetService:
    """Fixes: correct config key with validation; zero-padded fixed-length codes; expiry from the injected clock;
    describe_expiry honours the configured offset; expired at now >= expiry; password validated first; hash stored;
    code consumed on success; at most 5 wrong guesses; constant-time comparison; one lock for check-and-consume."""

    def __init__(self, config, clock, users, codes):
        if config is None or clock is None or users is None or codes is None:
            raise ValueError("all dependencies are required")
        self._clock = clock
        self._users = users
        self._codes = codes
        self._ttl_ms = _int_setting(config, "reset.ttl.minutes", "15", 1, 10_000_000) * 60_000
        self._length = _int_setting(config, "reset.code.length", "6", 4, 10)
        self._offset = _int_setting(config, "reset.utc.offset.minutes", "0", -720, 840)
        self._pending = {}
        self._lock = threading.Lock()

    def request_reset(self, email):
        if not self._users.exists(email):
            raise KeyError(email)
        code = str(self._codes(10 ** self._length)).zfill(self._length)
        with self._lock:
            self._pending[email] = _Pending(code, self._clock.now_millis() + self._ttl_ms)
        return code

    def expires_at(self, email):
        with self._lock:
            p = self._pending.get(email)
            if p is None:
                raise KeyError(email)
            return p.expires_at

    def describe_expiry(self, email):
        tz = timezone(timedelta(minutes=self._offset))
        when = datetime.fromtimestamp(self.expires_at(email) / 1000, tz=tz)
        return when.strftime("%Y-%m-%d %H:%M") + " " + _label(self._offset)

    def confirm_reset(self, email, code, new_password):
        if new_password is None or len(new_password) < 8:
            raise ValueError("password too short")
        with self._lock:
            p = self._pending.get(email)
            if p is None:
                return False
            if self._clock.now_millis() >= p.expires_at:
                del self._pending[email]
                return False
            if code is None or not hmac.compare_digest(p.code, str(code)):
                p.failures += 1
                if p.failures >= MAX_ATTEMPTS:
                    del self._pending[email]
                return False
            del self._pending[email]  # consumed
        self._users.set_password_hash(email, hash_password(new_password))
        return True


def _label(offset):
    if offset == 0:
        return "UTC"
    sign = "+" if offset > 0 else "-"
    h, m = divmod(abs(offset), 60)
    return f"UTC{sign}{h:02d}:{m:02d}"
