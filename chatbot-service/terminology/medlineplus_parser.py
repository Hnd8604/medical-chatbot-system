from __future__ import annotations

import html
import re
from collections.abc import Mapping
from typing import Any

from terminology.schemas import ExternalKnowledgeItem, KnowledgeType, TerminologyCode, TerminologySource


MAX_MEDLINEPLUS_ITEMS = 3


def parse_medlineplus_response(
    raw: Any,
    code: TerminologyCode,
    knowledge_type: KnowledgeType,
    *,
    max_items: int = MAX_MEDLINEPLUS_ITEMS,
) -> list[ExternalKnowledgeItem]:
    """Parse MedlinePlus JSON/Atom-like payloads into compact knowledge items."""
    entries = _entries(raw)
    if not entries:
        return []

    items: list[ExternalKnowledgeItem] = []
    for entry in entries[:max(max_items, 0)]:
        if not isinstance(entry, Mapping):
            continue
        title = _clean_text(_text_from(entry.get("title")))
        url = _clean_text(_url_from(entry.get("link")))
        summary = _strip_html(_text_from(entry.get("summary")))
        attribution = _clean_text(_author_from(entry.get("author")))
        if not any([title, url, summary]):
            continue
        fields: dict[str, Any] = {}
        if attribution:
            fields["attribution"] = attribution
        items.append(
            ExternalKnowledgeItem(
                source=TerminologySource.MEDLINEPLUS,
                type=knowledge_type,
                system=code.system,
                code=code.code,
                display=title or code.display or code.text,
                summary=summary,
                url=url,
                fields=fields,
            )
        )
    return items


def _entries(raw: Any) -> list[Any]:
    if not isinstance(raw, Mapping):
        return []
    feed = raw.get("feed") if isinstance(raw.get("feed"), Mapping) else raw
    entry = feed.get("entry") if isinstance(feed, Mapping) else None
    if entry is None:
        return []
    return entry if isinstance(entry, list) else [entry]


def _text_from(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        for item in value:
            text = _text_from(item)
            if text:
                return text
        return None
    if isinstance(value, Mapping):
        for key in ("_value", "value", "#text", "$", "text"):
            text = _text_from(value.get(key))
            if text:
                return text
    return None


def _url_from(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        for item in value:
            url = _url_from(item)
            if url:
                return url
        return None
    if isinstance(value, Mapping):
        for key in ("href", "@href", "url"):
            url = _text_from(value.get(key))
            if url:
                return url
    return None


def _author_from(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, list):
        parts = [_author_from(item) for item in value]
        return "; ".join(part for part in parts if part) or None
    if isinstance(value, Mapping):
        return _text_from(value.get("name")) or _text_from(value)
    return _text_from(value)


def _strip_html(value: str | None) -> str | None:
    text = _clean_text(value)
    if not text:
        return None
    text = re.sub(r"(?is)<(script|style).*?>.*?</\1>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    return _clean_text(html.unescape(text))


def _clean_text(value: Any) -> str | None:
    if value is None:
        return None
    text = html.unescape(str(value))
    text = " ".join(text.split())
    return text or None
