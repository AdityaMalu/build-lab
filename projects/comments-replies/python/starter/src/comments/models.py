from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class Comment:
    id: str
    post_id: str
    parent_id: Optional[str]  # None for top-level comments
    author: Optional[str]     # None once deleted
    text: str                 # "[deleted]" once deleted
    seq: int
    depth: int
    deleted: bool


@dataclass(frozen=True)
class CommentNode:
    comment: Comment
    replies: Tuple["CommentNode", ...]
