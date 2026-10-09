from contextlib import closing
from fastapi import APIRouter, Request
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
