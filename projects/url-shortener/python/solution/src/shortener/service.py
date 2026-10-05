import re
import threading
from dataclasses import dataclass

from . import base62

_OFFSET = 62 ** 5  # every generated code has at least 6 digits
_ALIAS = re.compile(r"[A-Za-z0-9_-]{3,32}")
_NEVER = float("inf")


class AliasTakenError(Exception):
    """The requested alias is already in use."""


@dataclass(frozen=True)
class LinkStats:
    code: str
    hits: int
    last_access_millis: int


class _Link:
    __slots__ = ("code", "url", "expires_at", "alias", "hits", "last_access")

    def __init__(self, code, url, expires_at, alias):
        self.code, self.url, self.expires_at, self.alias = code, url, expires_at, alias
        self.hits = 0
        self.last_access = -1


class UrlShortener:
    def __init__(self, time):
        if time is None:
            raise ValueError("time required")
        self._time = time
        self._lock = threading.Lock()
        self._links = {}
        self._generated_by_url = {}
        self._counter = 0

    def shorten(self, long_url, ttl_millis):
        _validate_url(long_url)
        with self._lock:
            now = self._time.now_millis()
            existing = self._generated_by_url.get(long_url)
            if existing is not None and self._links[existing].expires_at > now:
                return existing
            while True:
                code = base62.encode(_OFFSET + self._counter)
                self._counter += 1
                if code not in self._links:  # an alias may have taken it
                    break
            self._links[code] = _Link(code, long_url, _expiry(now, ttl_millis), False)
            self._generated_by_url[long_url] = code
            return code

    def shorten_with_alias(self, long_url, alias, ttl_millis):
        _validate_url(long_url)
        if not isinstance(alias, str) or not _ALIAS.fullmatch(alias):
            raise ValueError("bad alias")
        with self._lock:
            if alias in self._links:
                raise AliasTakenError(alias)
            self._links[alias] = _Link(alias, long_url, _expiry(self._time.now_millis(), ttl_millis), True)
            return alias

    def resolve(self, code):
        with self._lock:
            link = self._links.get(code)
            now = self._time.now_millis()
            if link is None or now >= link.expires_at:
                return None
            link.hits += 1
            link.last_access = now
            return link.url

    def stats(self, code):
        with self._lock:
            link = self._links.get(code)
            if link is None:
                raise KeyError(code)
            return LinkStats(link.code, link.hits, link.last_access)


def _expiry(now, ttl):
    return _NEVER if ttl <= 0 else now + ttl


def _validate_url(url):
    if not isinstance(url, str) or len(url) > 2048:
        raise ValueError("bad url")
    if url.startswith("https://"):
        rest = url[8:]
    elif url.startswith("http://"):
        rest = url[7:]
    else:
        raise ValueError("url must be http(s)")
    if not rest:
        raise ValueError("missing host")
    if any(ch.isspace() for ch in url):
        raise ValueError("whitespace in url")
