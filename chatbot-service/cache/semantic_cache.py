import asyncio
import uuid
import logging
import time
from typing import Optional
from functools import lru_cache

from qdrant_client import AsyncQdrantClient
from qdrant_client.models import (
    Distance,
    VectorParams,
    PointStruct,
    Filter,
    FieldCondition,
    MatchValue,
    Range
)
from fastembed import TextEmbedding

from app.config import Settings, get_settings 

log = logging.getLogger(__name__)


class SemanticCacheService:
    """Service for semantic caching utilizing Qdrant with dynamic settings."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = AsyncQdrantClient(url=settings.qdrant_url)
        self.collection_name = settings.cache_collection_name
        self.embedding_model = TextEmbedding(model_name=settings.cache_embedding_model)

    async def init_collection(self) -> None:
        """Initializes the Qdrant collection using parameters from settings."""
        try:
            exists = await self.client.collection_exists(self.collection_name)
            if not exists:
                await self.client.create_collection(
                    collection_name=self.collection_name,
                    vectors_config=VectorParams(
                        size=self.settings.cache_vector_size, 
                        distance=Distance.COSINE
                    ),
                )
                log.info(f"Qdrant collection '{self.collection_name}' created successfully.")
        except Exception as e:
            log.error(f"Failed to connect to Qdrant during startup initialization: {e}")

    def _embed_sync(self, text: str) -> list[float]:
        vectors = list(self.embedding_model.embed([text]))
        return vectors[0].tolist()

    async def _get_embedding(self, text: str) -> list[float]:
        """Generates a dense vector embedding for the text query.

        Embedding là CPU-bound và sync; chạy trong thread pool để không block
        event loop khi có nhiều request /chat đồng thời.
        """
        return await asyncio.to_thread(self._embed_sync, text)

    async def get_cached_answer(
        self,
        user_id: str,
        patient_id: str,
        question: str,
    ) -> Optional[tuple[str, str, dict, Optional[str], Optional[str]]]:
        """
        Retrieves a cached answer using strict semantic similarity without intent filtering.
        Returns (answer, intent, original_usage, llm_provider, llm_model) if a match is found;
        provider/model là của lượt đã sinh câu trả lời gốc (entry cũ có thể thiếu → None).
        """
        # Lấy threshold từ tham số truyền vào, nếu không có thì lấy từ config
        threshold =self.settings.cache_similarity_threshold
        query_vector = await self._get_embedding(question)
        
        expiration_threshold = (
            time.time() - self.settings.cache_ttl_seconds
        )
        search_result = await self.client.query_points(
            collection_name=self.collection_name,
            query=query_vector, 
            query_filter=Filter(
                must=[
                    FieldCondition(key="user_id", match=MatchValue(value=user_id)),
                    FieldCondition(key="patient_id", match=MatchValue(value=patient_id)),
                    FieldCondition(key="created_at",range=Range(gte=expiration_threshold))
                ]
            ),
            limit=1
        )

        points = search_result.points

        if not points:
            log.info("[CACHE MISS] Không tìm thấy cache.")
            return None

        point = points[0]

        if point.score < threshold:
            log.info(
                f"[CACHE MISS] Score={point.score:.3f} < threshold={threshold}"
            )
            return None

        payload = point.payload

        log.info(
            f"[CACHE HIT] Độ tương đồng={point.score:.3f}"
        )

        return (
            payload.get("answer"),
            payload.get("intent"),
            payload.get("original_usage", {}),
            payload.get("llm_provider"),
            payload.get("llm_model"),
        )

    async def save_to_cache(
        self,
        user_id: str,
        patient_id: str,
        intent: str,
        question: str,
        answer: str,
        usage: dict,
        llm_provider: str | None = None,
        llm_model: str | None = None,
    ) -> None:
        """Stores the newly generated LLM answer into the vector cache database.

        ``llm_provider``/``llm_model`` là model đã sinh câu trả lời gốc — khi cache
        hit, Spring dùng chúng để tính saved_cost đúng theo bảng model_pricing.
        """
        vector = await self._get_embedding(question)

        await self.client.upsert(
            collection_name=self.collection_name,
            points=[
                PointStruct(
                    id=str(uuid.uuid4()),
                    vector=vector,
                    payload={
                        "user_id": user_id,
                        "patient_id": patient_id,
                        "intent": intent,
                        "question": question,
                        "answer": answer,
                        "original_usage": usage,
                        "llm_provider": llm_provider,
                        "llm_model": llm_model,
                        "created_at": time.time()
                    }
                )
            ]
        )
        log.info(f"[CACHE SAVED] Đã lưu câu trả lời mới kèm user_id '{user_id}' vào Vector DB.")

    async def invalidate_patient_cache(self, patient_id: str) -> None:
        """Clears all current cache points bound to a specific patient context."""
        try:
            await self.client.delete(
                collection_name=self.collection_name,
                points_selector=Filter(
                    must=[
                        FieldCondition(key="patient_id", match=MatchValue(value=patient_id))
                    ]
                ),
            )
            log.info(f"[CACHE INVALIDATED] All cache vectors cleared for patient: {patient_id}")
        except Exception as e:
            log.error(f"[CACHE ERROR] Invalidation sequence failed for patient {patient_id}: {e}")

    async def cleanup_expired_cache(self) -> None:
        """Quét và xóa toàn bộ các vector cache đã hết hạn (TTL) khỏi Qdrant."""
        try:
            expiration_threshold = time.time() - self.settings.cache_ttl_seconds
            
            await self.client.delete(
                collection_name=self.collection_name,
                points_selector=Filter(
                    must=[
                        FieldCondition(
                            key="created_at",
                            range=Range(lt=expiration_threshold)
                        )
                    ]
                )
            )
            log.info("[CACHE CLEANUP] Đã quét và dọn dẹp các bản ghi cache hết hạn.")
        except Exception as e:
            log.error(f"[CACHE CLEANUP ERROR] Lỗi khi dọn dẹp cache: {e}", exc_info=True)


@lru_cache
def get_semantic_cache() -> SemanticCacheService:
    """Dependency injection wrapper providing a singleton cache service instance."""
    settings = get_settings()
    return SemanticCacheService(settings)