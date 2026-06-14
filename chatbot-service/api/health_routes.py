from fastapi import APIRouter
import logging

log = logging.getLogger(__name__)
router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}

@router.get("/testlog")
async def health_check():
    log.info("Bệnh nhân Nguyễn Văn A vừa cập nhật số điện thoại thành 0987654321 và số phụ là 0123456789.")
    
    return {"status": "ok"}