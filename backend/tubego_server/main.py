from contextlib import asynccontextmanager
from fastapi import FastAPI
from tubego_server import __version__
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.routers import system
from src.storage import validate_channel_paths
import os


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    database = Database(settings.database_path)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        validate_channel_paths(os.getenv("TUBEGO_BOT_DOWNLOAD_DIR", "downloads"), settings.data_dir)
        database.initialize()
        yield

    app = FastAPI(title="Tubego Mobile API", version=__version__,
                  lifespan=lifespan, docs_url="/api/v1/docs", redoc_url=None,
                  openapi_url="/api/v1/openapi.json")
    app.state.settings = settings
    app.state.db = database
    app.include_router(system.router, prefix="/api/v1")
    return app


app = create_app()
