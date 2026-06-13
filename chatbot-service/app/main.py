from fastapi import FastAPI
from contextlib import asynccontextmanager
from api.chat_routes import router as chat_router
from api.fhir_routes import router as fhir_router
from api.health_routes import router as health_router
from app.config import get_settings
from services.semantic_cache import get_semantic_cache


settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    cache_service = get_semantic_cache()
    await cache_service.init_collection()
    
    yield

app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(health_router)
app.include_router(fhir_router)
app.include_router(chat_router)
