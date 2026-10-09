from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from tubego_server import __version__
from tubego_server.config import Settings
from tubego_server.db import Database
from src.storage import validate_channel_paths
import os
from tubego_server.routers import system, registration, admin_priority, library, login, media, preferences, devices, device_delivery
from tubego_server.mail import SmtpMailer


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
    @app.middleware("http")
    async def private_auth_responses(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith(("/api/v1/auth/", "/api/v1/account/", "/api/v1/admin/")):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.exception_handler(RequestValidationError)
    async def invalid_input(request: Request, exc: RequestValidationError):
        # Validation errors must not echo passwords/tokens or submitted credentials.
        return JSONResponse(status_code=422, content={"detail": [
            {"loc": error["loc"], "msg": error["msg"], "type": error["type"]}
            for error in exc.errors()]})

    app.state.settings = settings
    app.state.db = database
    app.state.mailer = SmtpMailer(settings)
    app.include_router(system.router, prefix="/api/v1")
    app.include_router(registration.router, prefix="/api/v1")
    app.include_router(admin_priority.router, prefix="/api/v1")
    app.include_router(library.router, prefix="/api/v1")
    app.include_router(login.router, prefix="/api/v1")
    app.include_router(media.router, prefix="/api/v1")
    app.include_router(preferences.router, prefix="/api/v1")
    app.include_router(devices.router, prefix="/api/v1")
    app.include_router(device_delivery.router, prefix="/api/v1")
    return app


app = create_app()
