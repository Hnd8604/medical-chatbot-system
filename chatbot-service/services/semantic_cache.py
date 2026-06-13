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

    def _get_embedding(self, text: str) -> list[float]:
        """Generates a dense vector embedding for the text query."""
        vectors = list(self.embedding_model.embed([text]))
        return vectors[0].tolist()

    async def get_cached_answer(
        self, 
        user_id: str, 
        patient_id: str, 
        question: str, 
    ) -> Optional[tuple[str, str, dict]]:
        """
        Retrieves a cached answer using strict semantic similarity without intent filtering.
        Returns a tuple of (answer, intent) if a match is found.
        """
        # Lấy threshold từ tham số truyền vào, nếu không có thì lấy từ config
        threshold =self.settings.cache_similarity_threshold
        query_vector = self._get_embedding(question)
        
        search_result = await self.client.query_points(
            collection_name=self.collection_name,
            query=query_vector, 
            query_filter=Filter(
                must=[
                    FieldCondition(key="user_id", match=MatchValue(value=user_id)),
                    FieldCondition(key="patient_id", match=MatchValue(value=patient_id))
                ]
            ),
            limit=1
        )

        points = search_result.points
        
        if points and points[0].score >= threshold:
            payload = points[0].payload
            created_at = payload.get("created_at", 0)
            
            # Sử dụng TTL từ config
            if time.time() - created_at > self.settings.cache_ttl_seconds:
                log.info(f"[CACHE EXPIRED] Dữ liệu cache đã quá hạn.")
                return None
                
            log.info(f"[CACHE HIT] Bỏ qua hoàn toàn LLM! Độ tương đồng: {points[0].score:.3f}")
            return payload.get("answer"), payload.get("intent"), payload.get("original_usage", {})
            
        if points:
            log.info(f"[CACHE MISS] Điểm tương đồng cao nhất là {points[0].score:.3f} (Dưới ngưỡng {threshold}).")
        else:
            log.info(f"[CACHE MISS] Không có câu hỏi nào trong cache.")
            
        return None

    async def save_to_cache(self, user_id: str, patient_id: str, intent: str, question: str, answer: str, usage: dict) -> None:
        """Stores the newly generated LLM answer into the vector cache database."""
        vector = self._get_embedding(question)
        
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


@lru_cache
def get_semantic_cache() -> SemanticCacheService:
    """Dependency injection wrapper providing a singleton cache service instance."""
    settings = get_settings()
    return SemanticCacheService(settings)