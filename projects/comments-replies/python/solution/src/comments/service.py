import dataclasses
import threading

from .models import Comment, CommentNode

MAX_LENGTH = 500
MAX_DEPTH = 3


def _require(value, name):
    if value is None or not str(value).strip():
        raise ValueError(f"{name} required")


def _clean(text):
    if text is None or not text.strip():
        raise ValueError("text required")
    text = text.strip()
    if len(text) > MAX_LENGTH:
        raise ValueError("text too long")
    return text


class CommentService:
    def __init__(self):
        self._lock = threading.Lock()
        self._by_id = {}
        self._children = {}  # parent id, or ("root", post_id) for top level -> [ids]
        self._next_seq = 1

    def add_comment(self, post_id, author, text):
        _require(post_id, "post_id")
        _require(author, "author")
        body = _clean(text)
        with self._lock:
            return self._insert(post_id, None, author, body, 0)

    def reply(self, parent_id, author, text):
        _require(author, "author")
        body = _clean(text)
        with self._lock:
            parent = self._by_id.get(parent_id)
            if parent is None:
                raise KeyError(parent_id)
            if parent.deleted:
                raise RuntimeError("cannot reply to a deleted comment")
            if parent.depth >= MAX_DEPTH:
                raise RuntimeError("max depth reached")
            return self._insert(parent.post_id, parent.id, author, body, parent.depth + 1)

    def get_thread(self, post_id):
        with self._lock:
            return list(self._build(("root", post_id)))

    def delete(self, comment_id, requester):
        with self._lock:
            c = self._by_id.get(comment_id)
            if c is None:
                raise KeyError(comment_id)
            if c.deleted:
                return
            if c.author != requester:
                raise PermissionError("only the author may delete")
            self._by_id[comment_id] = dataclasses.replace(c, author=None, text="[deleted]", deleted=True)

    def count_visible(self, post_id):
        with self._lock:
            return sum(1 for c in self._by_id.values() if c.post_id == post_id and not c.deleted)

    def _insert(self, post_id, parent_id, author, body, depth):
        seq = self._next_seq
        self._next_seq += 1
        c = Comment(f"c{seq}", post_id, parent_id, author, body, seq, depth, False)
        self._by_id[c.id] = c
        key = ("root", post_id) if parent_id is None else parent_id
        self._children.setdefault(key, []).append(c.id)
        return c

    def _build(self, key):
        return tuple(CommentNode(self._by_id[i], self._build(i)) for i in self._children.get(key, ()))
