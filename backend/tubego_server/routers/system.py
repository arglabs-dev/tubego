from contextlib import closing
from fastapi import APIRouter, Request, Depends
from tubego_server.auth import require_admin,utcnow
from tubego_server.routers.admin_users import _active_admin
from src.disk_guard import DiskGuard
from pydantic import BaseModel
from tubego_server import __version__

router = APIRouter(tags=["system"])


class HealthResponse(BaseModel):
    status: str
    version: str
    database: str


class VersionResponse(BaseModel):
    version: str
    api_version: str


@router.get("/health", response_model=HealthResponse)
def health(request: Request):
    with closing(request.app.state.db.connect()) as connection:
        connection.execute("SELECT 1").fetchone()
    return HealthResponse(status="ok", version=__version__, database="ok")


@router.get("/version", response_model=VersionResponse)
def version():
    return VersionResponse(version=__version__, api_version="v1")


@router.get('/admin/storage')
def admin_storage(request:Request,admin=Depends(require_admin)):
    with request.app.state.db.transaction() as conn:
        _active_admin(conn,admin)
    # Observe the same volume/guard as the downloader. No notification or cleanup side effect.
    state=DiskGuard(request.app.state.settings.data_dir).sample()
    return {'total_bytes':state.total,'used_bytes':state.used,'free_bytes':state.free,
            'used_percent':state.used*100/state.total if state.total>0 else None,
            'pause_at_used_percent':90,'minimum_headroom_bytes':state.required_bytes,
            'downloads_paused':state.paused,'reason':state.reason,'checked_at':utcnow()}
