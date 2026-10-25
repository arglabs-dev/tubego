from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from tubego_server.media import MediaError, analyze_media
from tubego_server.auth import require_approved

router = APIRouter(tags=["media"])


def approved_user(request: Request):
    return require_approved(request)


class AnalyzeRequest(BaseModel):
    url: str = Field(min_length=1, max_length=4096)


@router.post("/media/analyze")
def analyze(body: AnalyzeRequest,request:Request, principal=Depends(approved_user)):
    try:
        info=analyze_media(body.url)
        from tubego_server.media import normalize_url
        from tubego_server.resource_identity import remember
        from tubego_server.tasks import live_scope
        normalized=normalize_url(body.url)
        with request.app.state.db.transaction() as conn:
            scope=live_scope(conn,principal);remember(conn,scope.user_id,normalized,info)
        return info
    except MediaError as error:
        status = {"invalid_url": 422, "unsupported": 422, "unavailable": 404,
                  "authentication_required": 422, "source_restricted": 422,
                  "temporary_failure": 503}[error.code]
        raise HTTPException(status, {"code": error.code, "message": str(error)}) from None
