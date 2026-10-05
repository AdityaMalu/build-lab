import threading
from collections import deque

_EPSILON = 1e-9


def _require_client(client_id):
    if client_id is None or not str(client_id).strip():
        raise ValueError("client_id required")


class _Bucket:
    __slots__ = ("tokens", "last_refill", "lock")

    def __init__(self, tokens, now):
        self.tokens = tokens
        self.last_refill = now
        self.lock = threading.Lock()


class TokenBucketLimiter:
    def __init__(self, capacity, refill_per_second, time):
        if capacity <= 0:
            raise ValueError("capacity must be positive")
        if refill_per_second < 0:
            raise ValueError("refill must be >= 0")
        if time is None:
            raise ValueError("time required")
        self._capacity = capacity
        self._refill_per_milli = refill_per_second / 1000.0
        self._time = time
        self._buckets = {}
        self._buckets_lock = threading.Lock()

    def try_acquire(self, client_id):
        _require_client(client_id)
        with self._buckets_lock:  # only guards the dict; each bucket has its own lock
            bucket = self._buckets.get(client_id)
            if bucket is None:
                bucket = _Bucket(float(self._capacity), self._time.now_millis())
                self._buckets[client_id] = bucket
        with bucket.lock:
            now = self._time.now_millis()
            elapsed = max(0, now - bucket.last_refill)
            bucket.tokens = min(self._capacity, bucket.tokens + elapsed * self._refill_per_milli)
            bucket.last_refill = now
            if bucket.tokens >= 1.0 - _EPSILON:
                bucket.tokens = max(0.0, bucket.tokens - 1.0)
                return True
            return False


class _Log:
    __slots__ = ("times", "lock")

    def __init__(self):
        self.times = deque()
        self.lock = threading.Lock()


class SlidingWindowLimiter:
    def __init__(self, max_requests, window_millis, time):
        if max_requests <= 0:
            raise ValueError("max_requests must be positive")
        if window_millis <= 0:
            raise ValueError("window_millis must be positive")
        if time is None:
            raise ValueError("time required")
        self._max = max_requests
        self._window = window_millis
        self._time = time
        self._logs = {}
        self._logs_lock = threading.Lock()

    def try_acquire(self, client_id):
        _require_client(client_id)
        with self._logs_lock:
            log = self._logs.setdefault(client_id, _Log())
        with log.lock:
            now = self._time.now_millis()
            while log.times and log.times[0] <= now - self._window:
                log.times.popleft()
            if len(log.times) < self._max:
                log.times.append(now)
                return True
            return False
