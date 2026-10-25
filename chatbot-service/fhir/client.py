from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, urlsplit

import httpx

from app.config import get_settings


# Trần số resource (khớp tìm kiếm, không tính _include) đọc qua nhiều trang.
MAX_SEARCH_RESULTS = 1000


class FhirClientError(Exception):
    def __init__(self, user_message: str, technical_detail: str | None = None) -> None:
        super().__init__(technical_detail or user_message)
        self.user_message = user_message
        self.technical_detail = technical_detail or user_message


class FhirNotFoundError(FhirClientError):
    pass


@dataclass(frozen=True)
class FhirClient:
    base_url: str
    timeout_seconds: float = 20
    transport: httpx.AsyncBaseTransport | None = None

    async def get_metadata(self) -> dict[str, Any]:
        return await self._get("metadata")

    async def get_patient(self, patient_id: str) -> dict[str, Any]:
        return await self._get(f"Patient/{patient_id}")

    async def get_resource(self, resource_type: str, resource_id: str) -> dict[str, Any]:
        return await self._get(f"{resource_type}/{resource_id}")

    async def search_patients(
        self,
        *,
        count: int = 20,
        name: str | None = None,
        phone: str | None = None,
        birth_date: str | None = None,
        identifier: str | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"_count": count}
        if name:
            # HAPI khớp mỗi tham số `name` theo tiền tố trên từng phần tên (family/
            # given/...) và AND các tham số `name` lặp lại. Tách tên nhiều từ thành
            # nhiều `name` để thu hẹp đúng người (vd "Hoàng Quang Phong" -> 1 người)
            # thay vì chỉ khớp một token cuối và trả về "toàn Phong".
            tokens = [token for token in (part.strip(" .,'-") for part in name.split()) if token]
            params["name"] = tokens if len(tokens) > 1 else name
        if phone:
            params["phone"] = phone
        if birth_date:
            params["birthdate"] = birth_date
        if identifier:
            params["identifier"] = identifier
        return await self._get("Patient", params=params)

    async def search_patients_flexible(
        self,
        *,
        count: int = 20,
        name: str | None = None,
        phone: str | None = None,
        birth_date: str | None = None,
        identifier: str | None = None,
    ) -> dict[str, Any]:
        bundle = await self.search_patients(
            count=count,
            name=name,
            phone=phone,
            birth_date=birth_date,
            identifier=identifier,
        )
        if bundle.get("entry") or not name:
            return bundle

        tokens = [token for token in (part.strip(" .,'-") for part in name.split()) if len(token) >= 2]
        if len(tokens) < 3:
            return bundle

        # Khi AND đủ token ra rỗng, chỉ nới lỏng tối thiểu: thử các tổ hợp con giữ
        # lại n-1 token (bỏ đúng 1 token) để dung sai thứ tự từ / token thừa. KHÔNG
        # tụt xuống khớp 1 token đơn — tên đệm phổ biến ("An", "Văn", "Thị") sẽ khớp
        # nhầm người khác và bị tự động chọn im lặng (xem docs/product-spec.md §9, §10).
        seen_subsets: set[tuple[str, ...]] = set()
        for drop_index in range(len(tokens)):
            subset = tuple(tokens[:drop_index] + tokens[drop_index + 1:])
            key = tuple(sorted(part.lower() for part in subset))
            if key in seen_subsets:
                continue
            seen_subsets.add(key)
            subset_bundle = await self.search_patients(
                count=count,
                name=" ".join(subset),
                phone=phone,
                birth_date=birth_date,
                identifier=identifier,
            )
            if subset_bundle.get("entry"):
                return subset_bundle
        return bundle

    async def search_patient_resources(
        self,
        resource_type: str,
        patient_id: str,
        *,
        count: int = 10,
        sort: str | None = None,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Tìm resource của một bệnh nhân. ``params``: tham số FHIR thêm (code, date, encounter...)."""
        query: dict[str, Any] = {
            **(params or {}),
            "patient": f"Patient/{patient_id}",
            "_count": count,
        }
        if sort:
            query["_sort"] = sort
        return await self._get(resource_type, params=query)

    async def search_resources(
        self,
        resource_type: str,
        params: dict[str, Any],
        *,
        max_results: int = MAX_SEARCH_RESULTS,
    ) -> dict[str, Any]:
        """Tìm resource trên mọi bệnh nhân, đi theo link ``next`` tới khi đủ ``max_results``.

        Trả một Bundle gộp: ``entry`` của mọi trang (kể cả resource ``_include``),
        ``total`` nếu server báo, ``truncated=True`` nếu còn trang chưa đọc.
        """
        page = await self._get(resource_type, params=params)
        total = page.get("total")
        entries = list(page.get("entry") or [])
        next_url = _next_link(page)
        while next_url and _match_count(entries) < max_results:
            page = await self._get_page(next_url)
            entries.extend(page.get("entry") or [])
            next_url = _next_link(page)
        return {
            "resourceType": "Bundle",
            "type": "searchset",
            "total": total,
            "entry": entries,
            "truncated": bool(next_url),
        }

    async def _get_page(self, next_url: str) -> dict[str, Any]:
        # Link phân trang của HAPI có thể mang host nội bộ (vd tên container); chỉ lấy
        # query string và gọi lại qua base_url mà service này cấu hình.
        query = parse_qs(urlsplit(next_url).query)
        return await self._get("", params={key: values if len(values) > 1 else values[0] for key, values in query.items()})

    # Nơi fhir gửi request đến HAPI FHIR server
    async def _get(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        base = self.base_url.rstrip("/")
        url = f"{base}/{path.lstrip('/')}" if path else base
        try:
            async with httpx.AsyncClient(
                timeout=self.timeout_seconds,
                transport=self.transport,
                headers={"Accept": "application/fhir+json"},
            ) as client:
                response = await client.get(url, params=params)
                if response.status_code == 404:
                    raise FhirNotFoundError("Không tìm thấy tài nguyên FHIR được yêu cầu.")
                response.raise_for_status()
                payload = response.json()
        except FhirClientError:
            raise
        except httpx.HTTPStatusError as exc:
            raise FhirClientError(
                "FHIR Server trả về lỗi.",
                f"HTTP {exc.response.status_code}: {exc.response.text}",
            ) from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise FhirClientError(
                "FHIR Server hiện không khả dụng hoặc trả về dữ liệu không hợp lệ.",
                str(exc),
            ) from exc

        if not isinstance(payload, dict):
            raise FhirClientError("FHIR Server trả về JSON không hợp lệ.")
        return payload


def _next_link(bundle: dict[str, Any]) -> str | None:
    for link in bundle.get("link") or []:
        if isinstance(link, dict) and link.get("relation") == "next" and link.get("url"):
            return str(link["url"])
    return None


def _match_count(entries: list[dict[str, Any]]) -> int:
    # Resource kéo theo bằng _include có search.mode = "include"; không tính vào trần.
    return sum(1 for entry in entries if (entry.get("search") or {}).get("mode", "match") == "match")


def get_fhir_client() -> FhirClient:
    settings = get_settings()
    return FhirClient(
        base_url=settings.normalized_fhir_base_url,
        timeout_seconds=settings.fhir_request_timeout_seconds,
    )
