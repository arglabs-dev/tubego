from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from tubego_server.media import MediaError, analyze_media

router = APIRouter(tags=["media"])


def approved_user(request: Request):
    # Foundation can start before the account feature is integrated; fail closed.
    try:
        from tubego_server.auth import require_approved
    except ImportError:
        raise HTTPException(503, "Account service unavailable") from None
    return require_approved(request)


class AnalyzeRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)


@router.post("/media/analyze")
def analyze(body: AnalyzeRequest, principal=Depends(approved_user)):
    try:
        return analyze_media(body.url)
    except MediaError as error:
        status = {"invalid_url": 422, "unsupported": 422, "unavailable": 404,
                  "authentication_required": 422, "source_restricted": 422,
                  "temporary_failure": 503}[error.code]
        raise HTTPException(status, {"code": error.code, "message": str(error)}) from None
