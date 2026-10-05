from dataclasses import dataclass
from enum import Enum


class Category(Enum):
    ELECTRONICS = "ELECTRONICS"
    APPAREL = "APPAREL"
    HOME = "HOME"
    BOOKS = "BOOKS"
    TOYS = "TOYS"


class RiskLevel(Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass(frozen=True)
class ReturnRequest:
    order_id: str
    amount_cents: int
    days_since_purchase: int
    returns_last_90_days: int
    category: Category
    opened: bool
    has_receipt: bool


@dataclass(frozen=True)
class RiskResult:
    score: int
    level: RiskLevel
    reasons: tuple


@dataclass(frozen=True)
class BatchEntry:
    order_id: object
    result: object
    error: object

    @property
    def ok(self):
        return self.error is None
