import threading

from .bucketing import bucket
from .models import Flag


class FlagService:
    """Flags are stored as immutable snapshots (tuples), so a reader never sees a half-built flag."""

    def __init__(self):
        self._flags = {}
        self._lock = threading.Lock()

    def define(self, flag):
        if flag is None:
            raise ValueError("flag required")
        if not flag.key or not flag.key.strip():
            raise ValueError("key required")
        if not 0 <= flag.rollout_percent <= 100:
            raise ValueError("rollout must be 0..100")
        if flag.rules is None:
            raise ValueError("rules required (may be empty)")
        variants = tuple(flag.variants or ())
        if any(v.weight < 0 for v in variants):
            raise ValueError("negative weight")
        if variants and sum(v.weight for v in variants) != 100:
            raise ValueError("weights must sum to 100")
        for r in flag.rules:
            if r is None or r.attribute is None or r.variant is None:
                raise ValueError("bad rule")
        snapshot = Flag(flag.key, flag.enabled, flag.rollout_percent, tuple(flag.rules), variants)
        with self._lock:
            self._flags[snapshot.key] = snapshot

    def evaluate(self, flag_key, user):
        with self._lock:
            flag = self._flags.get(flag_key)
        if flag is None:
            raise KeyError(flag_key)
        if user is None or not user.id or not str(user.id).strip():
            raise ValueError("user required")
        if not flag.enabled:
            return "off"

        attrs = user.attributes or {}
        for rule in flag.rules:
            value = attrs.get(rule.attribute)
            if value is not None and value == rule.equals_value:
                return rule.variant

        if bucket(f"{flag_key}:{user.id}", 100) >= flag.rollout_percent:
            return "off"
        if not flag.variants:
            return "on"

        b = bucket(f"{flag_key}:variant:{user.id}", 100)
        cumulative = 0
        for v in flag.variants:
            cumulative += v.weight
            if b < cumulative:
                return v.name
        raise RuntimeError(f"weights did not cover bucket {b}")
