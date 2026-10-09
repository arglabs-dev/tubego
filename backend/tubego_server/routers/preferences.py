from fastapi import APIRouter, Depends, Request
from tubego_server.auth import require_approved
from tubego_server.preferences import MediaPreferences, read_preferences, save_preferences

router = APIRouter(tags=['preferences'])


@router.get('/account/preferences', response_model=MediaPreferences)
def get_preferences(request: Request, user=Depends(require_approved)):
    return read_preferences(request.app.state.db, user['id'])


@router.put('/account/preferences', response_model=MediaPreferences)
def put_preferences(body: MediaPreferences, request: Request, user=Depends(require_approved)):
    return save_preferences(request.app.state.db, user['id'], body)
