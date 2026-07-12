from dataclasses import dataclass
from typing import Any

import httpx

from app.config import get_settings


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
        tokens = [token for token in (part.strip(" .,'-") for part in name.split()) if len(token) >= 2]
        if bundle.get("entry") or len(tokens) < 3:
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
    ) -> dict[str, Any]:
        params: dict[str, str | int] = {
            "patient": f"Patient/{patient_id}",
            "_count": count,
        }
        if sort:
            params["_sort"] = sort
        return await self._get(resource_type, params=params)
        
    # Nơi fhir gửi request đến HAPI FHIR server
    async def _get(
        self,
        path: str,
        *,
        params: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        url = f"{self.base_url.rstrip('/')}/{path.lstrip('/')}"
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


def get_fhir_client() -> FhirClient:
    settings = get_settings()
    return FhirClient(
        base_url=settings.normalized_fhir_base_url,
        timeout_seconds=settings.fhir_request_timeout_seconds,
    )
