from dataclasses import dataclass


class AliasTakenError(Exception):
    """The requested alias is already in use."""


@dataclass(frozen=True)
class LinkStats:
    code: str
    hits: int
    last_access_millis: int


class UrlShortener:
    def __init__(self, time):
        # TODO
        pass

    def shorten(self, long_url, ttl_millis):
        raise NotImplementedError("TODO")

    def shorten_with_alias(self, long_url, alias, ttl_millis):
        raise NotImplementedError("TODO")

    def resolve(self, code):
        raise NotImplementedError("TODO")

    def stats(self, code):
        raise NotImplementedError("TODO")
