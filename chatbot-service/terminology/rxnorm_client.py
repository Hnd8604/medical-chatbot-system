from __future__ import annotations

from typing import Any

import httpx

from terminology.schemas import TerminologyCode


RXNORM_BASE_URL = "https://rxnav.nlm.nih.gov"
RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm"
RXNORM_SYSTEMS = {
    RXNORM_SYSTEM,
    "2.16.840.1.113883.6.88",
    "urn:oid:2.16.840.1.113883.6.88",
}


class RxNormClient:
    """Async client for compact RxNorm lookups (public RxNav API, no key)."""

    def __init__(
        self,
        *,
        base_url: str = RXNORM_BASE_URL,
        timeout_seconds: float = 5,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    async def fetch_properties(self, rxcui: str) -> dict[str, Any] | None:
        """Fetch RxNorm concept properties for one RxCUI."""
        return await self._get_json(f"/REST/rxcui/{rxcui}/properties.json")

    async def find_rxcui_by_string(self, name: str) -> dict[str, Any] | None:
        """Find a likely RxCUI by medication text."""
        return await self._get_json("/REST/rxcui.json", params={"name": name, "search": "2"})

    async def _get_json(
        self,
        path: str,
        *,
        params: dict[str, str] | None = None,
    ) -> dict[str, Any] | None:
        try:
            async with httpx.AsyncClient(
                timeout=self.timeout_seconds,
                follow_redirects=True,
                transport=self.transport,
            ) as client:
                response = await client.get(f"{self.base_url}{path}", params=params)
                response.raise_for_status()
                data = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        return data if isinstance(data, dict) else None


def is_rxnorm_code(code: TerminologyCode) -> bool:
    """Return whether an extracted code is a RxNorm medication code."""
    return (
        code.resource_type == "MedicationRequest"
        and bool(code.code)
        and str(code.system or "").strip().lower() in RXNORM_SYSTEMS
    )


def is_rxnorm_text_lookup_candidate(code: TerminologyCode) -> bool:
    """Return whether a medication text can be used for RxNorm lookup."""
    text = code.display or code.text
    return code.resource_type == "MedicationRequest" and not code.code and bool(str(text or "").strip())
