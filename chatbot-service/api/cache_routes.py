"""Endpoint quản trị semantic cache (nội bộ).

Chatbot-service không có đường ghi FHIR nào — dữ liệu bệnh nhân được ghi vào
HAPI từ bên ngoài (script seed, thao tác quản trị trực tiếp trên FHIR server).
Vì vậy invalidation chủ động phải được kích hoạt từ ngoài qua endpoint này
(script seed gọi sau khi nạp dữ liệu); TTL ngắn trong Qdrant vẫn là cơ chế
hết hạn tự động nền (product-spec §8.3).
"""

import logging

from fastapi import APIRouter, Depends

from services.semantic_cache import SemanticCacheService, get_semantic_cache


log = logging.getLogger(__name__)

router = APIRouter(tags=["cache"])


@router.post("/cache/invalidate/{patient_id}")
async def invalidate_patient_cache(
    patient_id: str,
    cache_service: SemanticCacheService = Depends(get_semantic_cache),
) -> dict:
    """Xóa mọi cache entry gắn với một bệnh nhân sau khi dữ liệu FHIR thay đổi."""
    await cache_service.invalidate_patient_cache(patient_id)
    return {"status": "ok", "invalidated_patient_id": patient_id}
