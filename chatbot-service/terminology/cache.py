from __future__ import annotations

from dataclasses import dataclass
from time import monotonic
from typing import Any, Callable

from terminology.schemas import TerminologyCode


@dataclass
class _CacheEntry:
    value: Any
    expires_at: float


class TerminologyCache:
    """Small TTL cache for non-patient-specific terminology lookups."""

    def __init__(
        self,
        *,
        default_ttl_seconds: int = 43200,
        time_provider: Callable[[], float] = monotonic,
    ) -> None:
        self.default_ttl_seconds = default_ttl_seconds
        self._time_provider = time_provider
        self._items: dict[str, _CacheEntry] = {}

    def get(self, key: str) -> Any | None:
        """Return a cached value or None when missing/expired."""
        entry = self._items.get(key)
        if entry is None:
            return None
        if entry.expires_at <= self._time_provider():
            self._items.pop(key, None)
            return None
        return entry.value

    def set(self, key: str, value: Any, *, ttl_seconds: int | None = None) -> None:
        """Store a value with TTL."""
        ttl = self.default_ttl_seconds if ttl_seconds is None else ttl_seconds
        self._items[key] = _CacheEntry(
            value=value,
            expires_at=self._time_provider() + max(ttl, 0),
        )

    def get_for_code(self, source: str, code: TerminologyCode) -> Any | None:
        """Return a cached value for an extracted code."""
        return self.get(self.build_key_for_code(source, code))

    def set_for_code(
        self,
        source: str,
        code: TerminologyCode,
        value: Any,
        *,
        ttl_seconds: int | None = None,
    ) -> None:
        """Store a cached value for an extracted code."""
        self.set(self.build_key_for_code(source, code), value, ttl_seconds=ttl_seconds)

    def build_key_for_code(self, source: str, code: TerminologyCode) -> str:
        """Build a cache key that excludes patient/resource identifiers."""
        return build_cache_key(
            source=source,
            system=code.system,
            code=code.code,
            text=code.text or code.display,
        )

    def clear(self) -> None:
        """Remove all cached entries."""
        self._items.clear()


def build_cache_key(
    *,
    source: str,
    system: str | None = None,
    code: str | None = None,
    text: str | None = None,
    knowledge_type: str | None = None,
) -> str:
    """Build a stable terminology cache key without patient-specific fields."""
    parts = [
        "terminology",
        _norm(source),
        _norm(knowledge_type),
        _norm(system),
        _norm(code),
        _norm(text),
    ]
    return "|".join(parts)


def _norm(value: Any) -> str:
    return " ".join(str(value or "").strip().lower().split())
