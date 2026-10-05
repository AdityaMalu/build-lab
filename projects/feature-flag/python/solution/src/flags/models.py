from dataclasses import dataclass
from typing import Mapping, Optional, Sequence


@dataclass(frozen=True)
class Rule:
    """If user.attributes[attribute] == equals_value, serve variant."""
    attribute: str
    equals_value: str
    variant: str


@dataclass(frozen=True)
class Variant:
    name: str
    weight: int


@dataclass(frozen=True)
class Flag:
    key: str
    enabled: bool
    rollout_percent: int
    rules: Sequence[Rule]
    variants: Sequence[Variant]


@dataclass(frozen=True)
class User:
    id: str
    attributes: Optional[Mapping[str, str]]
