from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "Dịch vụ Chatbot Y tế"
    fhir_base_url: str = Field(default="http://localhost:8080/fhir")
    fhir_request_timeout_seconds: float = Field(default=20)
    llm_provider: str = Field(default="openai")
    # Model của bước sinh câu trả lời; chọn theo độ phức tạp của plan (llm/model_select.py).
    model_simple: str = Field(default="gpt-4o-mini")
    model_complex: str = Field(default="gpt-4.1-mini")
    llm_request_timeout_seconds: float = Field(default=20)
    enable_llm_answer: bool = Field(default=True)

    # Rolling summary hội thoại. Chỉ gọi LLM khi tổng số message của
    # session >= summary_trigger_message_count (khớp RECENT_CONTEXT_MESSAGE_LIMIT=8 bên Spring).
    enable_llm_summary: bool = Field(default=True)
    model_summary: str = Field(default="gpt-4o-mini")
    summary_trigger_message_count: int = Field(default=8)
    summary_max_output_tokens: int = Field(default=256)


    litellm_base_url: str = Field(default="http://localhost:4000")
    litellm_master_key: str | None = Field(default=None)

    # Terminology enrichment (giải thích mã y khoa). Chỉ chạy khi câu hỏi mang ý
    # "giải thích/ý nghĩa" (explain). RxNorm/MedlinePlus là API công khai, không cần key;
    # LOINC cần tài khoản loinc.org (thiếu creds -> nhánh LOINC tự bỏ qua).
    loinc_enabled: bool = Field(default=True)
    rxnorm_enabled: bool = Field(default=True)
    medlineplus_enabled: bool = Field(default=True)
    loinc_username: str | None = Field(default=None)
    loinc_password: str | None = Field(default=None)
    terminology_timeout_seconds: float = Field(default=5)
    terminology_cache_ttl_seconds: int = Field(default=43200)
    rxnorm_cache_ttl_seconds: int = Field(default=86400)
    loinc_cache_ttl_seconds: int = Field(default=2592000)

    # ---- Agent (router → planner → validator → executor → answer) ----
    # Model theo stage. None -> rơi về model_simple/model_complex.
    model_route: str | None = Field(default=None)
    model_planner: str | None = Field(default=None)
    model_chat: str | None = Field(default=None)
    # Router LLM: bao nhiêu quyết định route được nhớ trong LRU (0 = tắt cache).
    route_cache_size: int = Field(default=512)
    # Trần số step của một plan sau khi validate.
    max_plan_steps: int = Field(default=4)
    # Evidence budget: trần ký tự của evidence đưa vào prompt answer (~4 ký tự/token).
    evidence_max_chars: int = Field(default=12000)
    # Template fast-path: plan 1 bước, data_only, <= ngần này evidence -> không gọi answer LLM.
    template_fast_path: bool = Field(default=False)
    template_fast_path_max_evidence: int = Field(default=5)
    # Plan cache: cache "ý định đã validate" (không chứa PHI), dùng chung mọi user.
    enable_plan_cache: bool = Field(default=True)
    plan_cache_collection_name: str = Field(default="medical_plan_cache")
    plan_cache_ttl_seconds: int = Field(default=86400)
    plan_cache_similarity_threshold: float = Field(default=0.95)
    # Low-cost mode: quota vượt ngưỡng -> bỏ router/planner LLM, tắt terminology.
    low_cost_mode_ratio: float = Field(default=0.9)
    # QUYẾT ĐỊNH BẢO MẬT ĐANG CHỜ CHỐT.
    # false = USER không được get_resource_by_id.
    # true  = cho phép, nhưng executor kiểm tra chủ sở hữu resource sau khi fetch
    #         và chặn nếu subject không thuộc allowed_patient_ids.
    allow_user_resource_lookup: bool = Field(default=False)
    # Kiểm tra tất định câu trả lời LLM so với evidence (mã bệnh nhân/resource, con số).
    # enforce = sai thì thay bằng câu template và không cache; log = chỉ ghi log; off = tắt.
    grounding_check_mode: Literal["enforce", "log", "off"] = Field(default="enforce")

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

    @property
    def use_llm_summary(self) -> bool:
        return self.enable_llm_summary and self.use_llm

    @property
    def route_model(self) -> str:
        return self.model_route or self.model_simple

    @property
    def planner_model(self) -> str:
        return self.model_planner or self.model_complex

    @property
    def chat_model(self) -> str:
        """Model cho general_chat / conversation_meta / unsupported (câu ngắn, không FHIR)."""
        return self.model_chat or self.model_simple


@lru_cache
def get_settings() -> Settings:
    return Settings()
