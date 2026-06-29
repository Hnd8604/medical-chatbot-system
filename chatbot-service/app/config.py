from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "Dịch vụ Chatbot Y tế"
    fhir_base_url: str = Field(default="http://localhost:8080/fhir")
    fhir_request_timeout_seconds: float = Field(default=20)
    llm_provider: str = Field(default="openai")
    model_simple: str = Field(default="gpt-4o-mini")
    model_complex: str = Field(default="gpt-4.1-mini")
    llm_request_timeout_seconds: float = Field(default=20)
    enable_llm_answer: bool = Field(default=True)


    litellm_base_url: str = Field(default="http://localhost:4000")
    litellm_master_key: str | None = Field(default=None)

    # Semantic Cache Config (Qdrant)
    qdrant_url: str = Field(default="http://localhost:6333")
    cache_collection_name: str = Field(default="medical_chat_cache")
    cache_embedding_model: str = Field(default="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    cache_vector_size: int = Field(default=384)
    cache_ttl_seconds: int = Field(default=300)
    cache_similarity_threshold: float = Field(default=0.94)

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def normalized_fhir_base_url(self) -> str:
        return self.fhir_base_url.rstrip("/")

    @property
    def llm_base_url(self) -> str:
        return self.litellm_base_url

    @property
    def llm_api_key(self) -> str | None:
        return self.litellm_master_key

    @property
    def use_llm(self) -> bool:
        """Có master key của LiteLLM gateway hay không (đường LLM duy nhất)."""
        return bool(self.litellm_master_key)

    @property
    def use_llm_answer(self) -> bool:
        return self.enable_llm_answer and self.use_llm


@lru_cache
def get_settings() -> Settings:
    return Settings()
