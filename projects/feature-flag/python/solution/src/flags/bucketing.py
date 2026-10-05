def bucket(key, buckets):
    """32-bit FNV-1a of the UTF-8 bytes of key, modulo buckets."""
    if key is None:
        raise ValueError("key required")
    if buckets <= 0:
        raise ValueError("buckets must be positive")
    h = 0x811C9DC5
    for b in key.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h % buckets
