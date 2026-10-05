import threading
from collections import deque
from enum import Enum

STRIKE_LIMIT = 3
STRIKE_WINDOW_MS = 60 * 60 * 1000
AUTO_BLOCK_MS = 24 * 60 * 60 * 1000
_NEVER = float("-inf")
_FOREVER = float("inf")


class Decision(Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED_KEYWORD = "REJECTED_KEYWORD"
    BLOCKED_USER = "BLOCKED_USER"


def tokens(text):
    out, cur = [], []
    for ch in text.lower():
        if ch.isalnum():
            cur.append(ch)
        elif cur:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return out


def _normalize(phrase):
    if phrase is None:
        raise ValueError("phrase required")
    t = tokens(phrase)
    if not t:
        raise ValueError("phrase has no words")
    return " ".join(t)


class _UserState:
    __slots__ = ("blocked_until", "strikes", "lock")

    def __init__(self):
        self.blocked_until = _NEVER
        self.strikes = deque()
        self.lock = threading.Lock()


class ContentGate:
    def __init__(self, time):
        if time is None:
            raise ValueError("time required")
        self._time = time
        self._keywords = frozenset()  # replaced wholesale, so readers never see a half-updated set
        self._keywords_lock = threading.Lock()
        self._users = {}
        self._users_lock = threading.Lock()

    def add_keyword(self, phrase):
        k = _normalize(phrase)
        with self._keywords_lock:
            self._keywords = self._keywords | {k}

    def remove_keyword(self, phrase):
        k = _normalize(phrase)
        with self._keywords_lock:
            self._keywords = self._keywords - {k}

    def block_user(self, user_id, duration_millis):
        s = self._state(user_id)
        with s.lock:
            s.blocked_until = _FOREVER if duration_millis <= 0 else self._time.now_millis() + duration_millis

    def unblock_user(self, user_id):
        s = self._state(user_id)
        with s.lock:
            s.blocked_until = _NEVER
            s.strikes.clear()

    def is_blocked(self, user_id):
        s = self._state(user_id)
        with s.lock:
            return self._time.now_millis() < s.blocked_until

    def submit(self, user_id, text):
        s = self._state(user_id)
        violates = self._matches(tokens(text or ""))  # pure; no lock needed
        with s.lock:
            now = self._time.now_millis()
            if now < s.blocked_until:
                return Decision.BLOCKED_USER
            if not violates:
                return Decision.ACCEPTED
            self._prune(s, now)
            s.strikes.append(now)
            if len(s.strikes) >= STRIKE_LIMIT:
                s.blocked_until = now + AUTO_BLOCK_MS
                s.strikes.clear()
            return Decision.REJECTED_KEYWORD

    def active_strikes(self, user_id):
        s = self._state(user_id)
        with s.lock:
            self._prune(s, self._time.now_millis())
            return len(s.strikes)

    def _matches(self, text_tokens):
        if not text_tokens:
            return False
        joined = " " + " ".join(text_tokens) + " "
        return any(f" {k} " in joined for k in self._keywords)

    @staticmethod
    def _prune(s, now):
        while s.strikes and s.strikes[0] + STRIKE_WINDOW_MS <= now:
            s.strikes.popleft()

    def _state(self, user_id):
        if user_id is None or not str(user_id).strip():
            raise ValueError("user_id required")
        with self._users_lock:
            s = self._users.get(user_id)
            if s is None:
                s = self._users[user_id] = _UserState()
            return s
