import logging
from fastapi import Request, status, FastAPI
from fastapi.responses import JSONResponse
from fhir.client import FhirClientError 

log = logging.getLogger(__name__)

async def global_exception_handler(request: Request, exc: Exception):
    log.error(f"[SYSTEM FATAL] Lỗi tại {request.method} {request.url.path}: {str(exc)}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "status": 500,
            "error_code": "INTERNAL_SERVER_ERROR",
            "message": "Dịch vụ AI đang gặp sự cố nội bộ. Vui lòng thử lại sau.",
            "answer_source": "system_error"
        }
    )

async def fhir_exception_handler(request: Request, exc: FhirClientError):
    log.error(f"[FHIR ERROR] Lỗi giao tiếp FHIR: {exc.user_message}")
    return JSONResponse(
        status_code=status.HTTP_502_BAD_GATEWAY,
        content={
            "status": 502,
            "error_code": "FHIR_SERVICE_UNAVAILABLE",
            "message": exc.user_message
        }
    )

# Hàm gom tất cả handler lại để gắn vào app
def register_exception_handlers(app: FastAPI):
    app.add_exception_handler(Exception, global_exception_handler)
    app.add_exception_handler(FhirClientError, fhir_exception_handler)