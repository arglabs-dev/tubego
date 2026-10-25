from fastapi import APIRouter, Depends, HTTPException, Request

from tubego_server.auth import require_admin
from tubego_server.mail import MailTestError

router = APIRouter(prefix="/admin/mail", tags=["admin mail diagnostics"])


@router.get("/status")
def status(request: Request, admin: dict = Depends(require_admin)):
    return {**request.app.state.mailer.configuration_status(), "test_recipient": admin["email"]}


@router.post("/check")
def check(request: Request, admin: dict = Depends(require_admin)):
    try:
        return request.app.state.mailer.check_connection()
    except MailTestError as exc:
        raise HTTPException(502, {"code": exc.code, "message": exc.safe_message}) from None


@router.post("/test")
def test(request: Request, admin: dict = Depends(require_admin)):
    # Test messages can only be sent to the currently authenticated administrator.
    try:
        return request.app.state.mailer.send_test(admin["email"])
    except MailTestError as exc:
        raise HTTPException(502, {"code": exc.code, "message": exc.safe_message}) from None
