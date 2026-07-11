from __future__ import annotations

from typing import Any

import httpx

from terminology.schemas import TerminologyCode


LOINC_BASE_URL = "https://fhir.loinc.org"
LOINC_SYSTEM = "http://loinc.org"
LOINC_SYSTEMS = {
    LOINC_SYSTEM,
    "2.16.840.1.113883.6.1",
    "urn:oid:2.16.840.1.113883.6.1",
}


class LoincClient:
    """Async client for LOINC FHIR terminology lookups (CodeSystem/$lookup)."""

    def __init__(
        self,
        *,
        base_url: str = LOINC_BASE_URL,
        timeout_seconds: float = 5,
        username: str | None = None,
        password: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.username = _clean(username)
        self.password = _clean(password)
        self.transport = transport

    @property
    def has_credentials(self) -> bool:
        """Return whether Basic Auth credentials are available."""
        return bool(self.username and self.password)

    async def lookup(self, code: str) -> dict[str, Any] | None:
        """Lookup one LOINC code through FHIR CodeSystem/$lookup."""
        if not self.has_credentials:
            return None
        params = {"system": LOINC_SYSTEM, "code": code}
        auth = (self.username or "", self.password or "")
        try:
            async with httpx.AsyncClient(
                timeout=self.timeout_seconds,
                follow_redirects=True,
                transport=self.transport,
            ) as client:
                response = await client.get(f"{self.base_url}/CodeSystem/$lookup", params=params, auth=auth)
                response.raise_for_status()
                data = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        return data if isinstance(data, dict) else None


def is_loinc_code(code: TerminologyCode) -> bool:
    """Return whether an extracted code is a LOINC Observation code."""
    return (
        code.resource_type == "Observation"
        and bool(code.code)
        and str(code.system or "").strip().lower() in LOINC_SYSTEMS
    )


def _clean(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
