import time
from datetime import datetime, timezone


class PasswordResetService:
    def __init__(self, config, clock, users, codes):
        self._clock = clock
        self._users = users
        self._codes = codes
        self._ttl_minutes = int(config.get("reset.ttl.minute", "15"))
        self._code_length = int(config.get("reset.code.length", "6"))
        self._offset_minutes = int(config.get("reset.utc.offset.minutes", "0"))
        self._pending = {}  # email -> (code, expires_at)

    def request_reset(self, email):
        if not self._users.exists(email):
            raise KeyError(email)
        code = str(self._codes(10 ** self._code_length))
        expires = int(time.time() * 1000) + self._ttl_minutes * 60_000
        self._pending[email] = (code, expires)
        return code

    def expires_at(self, email):
        if email not in self._pending:
            raise KeyError(email)
        return self._pending[email][1]

    def describe_expiry(self, email):
        when = datetime.fromtimestamp(self.expires_at(email) / 1000, tz=timezone.utc)
        return when.strftime("%Y-%m-%d %H:%M") + " UTC"

    def confirm_reset(self, email, code, new_password):
        pending = self._pending.get(email)
        if pending is None:
            return False
        if self._clock.now_millis() > pending[1]:
            return False
        if pending[0] != code:
            return False
        if new_password is None or len(new_password) < 8:
            raise ValueError("password too short")
        self._users.set_password_hash(email, new_password)
        return True
