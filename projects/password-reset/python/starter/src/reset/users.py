"""Given. Password hashing with the standard library only (PBKDF2-HMAC-SHA256), so the exercise has no dependencies.
Demo settings: a fixed salt keeps it deterministic for the tests. In production use a random per-user salt
stored with the hash (or bcrypt / scrypt / Argon2) and far more iterations."""

import hashlib
import threading


def hash_password(password):
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), b"practice-lab", 20_000)
    return "pbkdf2-sha256:" + digest.hex()


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
