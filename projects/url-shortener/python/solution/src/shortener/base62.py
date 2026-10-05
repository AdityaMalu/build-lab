ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
_INDEX = {c: i for i, c in enumerate(ALPHABET)}


def encode(n):
    if not isinstance(n, int) or n < 0:
        raise ValueError("n must be a non-negative int")
    if n == 0:
        return "0"
    digits = []
    while n:
        n, r = divmod(n, 62)
        digits.append(ALPHABET[r])
    return "".join(reversed(digits))


def decode(s):
    if not s:
        raise ValueError("empty")
    n = 0
    for ch in s:
        if ch not in _INDEX:
            raise ValueError(f"invalid character {ch!r}")
        n = n * 62 + _INDEX[ch]
    return n
