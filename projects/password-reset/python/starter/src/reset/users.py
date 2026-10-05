"""Given. Demo-grade hashing so the exercise has no dependencies.
In production use a slow, salted KDF (bcrypt / scrypt / Argon2) with a per-user salt."""

import hashlib
import threading


def hash_password(password):
    return "sha256:" + hashlib.sha256(("practice-lab:" + password).encode("utf-8")).hexdigest()


class InMemoryUserStore:
    def __init__(self):
        self._hashes = {}
        self._lock = threading.Lock()

    def add(self, email, initial_hash):
        with self._lock:
            self._hashes[email] = initial_hash
        return self

    def exists(self, email):
        with self._lock:
            return email in self._hashes

    def set_password_hash(self, email, password_hash):
        with self._lock:
            if email not in self._hashes:
                raise KeyError(email)
            self._hashes[email] = password_hash

    def password_hash(self, email):
        with self._lock:
            return self._hashes.get(email)
