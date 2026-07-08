from app.logger import setup_logging
setup_logging()

import logging
import asyncio
from api.exceptions import register_exception_handlers
from fastapi import FastAPI
from contextlib import asynccontextmanager

from api.cache_routes import router as cache_router
from api.chat_routes import router as chat_router
from api.fhir_routes import router as fhir_router
from api.health_routes import router as health_router
from app.config import get_settings
from services.semantic_cache import get_semantic_cache

log = logging.getLogger(__name__)
settings = get_settings()

async def cache_cleanup_task():
    """Background task định kỳ dọn dẹp cache."""
    cache_service = get_semantic_cache()
    while True:
        await asyncio.sleep(3600) 
        log.info("[SYSTEM] Bắt đầu tiến trình dọn dẹp cache định kỳ...")
        await cache_service.cleanup_expired_cache()

@asynccontextmanager
async def lifespan(app: FastAPI):
    cache_service = get_semantic_cache()
    await cache_service.init_collection()
    
    cleanup_task = asyncio.create_task(cache_cleanup_task())
    log.info("[SYSTEM] Đã khởi động tiến trình dọn dẹp cache ngầm.")

    yield

    cleanup_task.cancel()
    log.info("[SYSTEM] Đã tắt tiến trình dọn dẹp cache ngầm.")
    try:
        await cleanup_task
    except asyncio.CancelledError:
        pass

app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    lifespan=lifespan,
)

register_exception_handlers(app)

app.include_router(health_router)
app.include_router(fhir_router)
app.include_router(chat_router)
app.include_router(cache_router)