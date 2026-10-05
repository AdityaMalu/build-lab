from enum import Enum

STRIKE_LIMIT = 3
STRIKE_WINDOW_MS = 60 * 60 * 1000
AUTO_BLOCK_MS = 24 * 60 * 60 * 1000


class Decision(Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED_KEYWORD = "REJECTED_KEYWORD"
    BLOCKED_USER = "BLOCKED_USER"


class ContentGate:
    def __init__(self, time):
        # TODO
        pass

    def add_keyword(self, phrase):
        raise NotImplementedError("TODO")

    def remove_keyword(self, phrase):
        raise NotImplementedError("TODO")

    def block_user(self, user_id, duration_millis):
        raise NotImplementedError("TODO")

    def unblock_user(self, user_id):
        raise NotImplementedError("TODO")

    def is_blocked(self, user_id):
        raise NotImplementedError("TODO")

    def submit(self, user_id, text):
        raise NotImplementedError("TODO")

    def active_strikes(self, user_id):
        raise NotImplementedError("TODO")
