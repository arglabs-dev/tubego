from contextlib import closing
from fastapi.testclient import TestClient
from tubego_server.main import create_app
from tubego_server.config import Settings
from test_preferences import seed


def test_language_private_persisted_and_does_not_reset_media(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        _,a=seed(app);_,b=seed(app)
        route='/api/v1/account/preferences/language'
        assert client.get(route,headers=a).json()['language'] is None
        media={'ask_every_time':False,'selection':'audio','rewind_seconds':20}
        assert client.put('/api/v1/account/preferences',headers=a,json=media).status_code==200
        assert client.put(route,headers=a,json={'language':'en'}).json()=={'language':'en'}
        assert client.get(route,headers=b).json()['language'] is None
        assert client.get('/api/v1/account/preferences',headers=a).json()==media
        assert client.put('/api/v1/account/preferences',headers=a,json=media).status_code==200
        assert client.get(route,headers=a).json()['language']=='en'
        for bad in [{'language':'fr'},{'language':'EN'},{'language':'es','user_id':'other'},{'language':True}]:
            assert client.put(route,headers=a,json=bad).status_code==422
    with TestClient(create_app(Settings(tmp_path))) as client:
        assert client.get(route,headers=a).json()['language']=='en'


def test_language_approval_and_session_revocation(tmp_path):
    app=create_app(Settings(tmp_path))
    with TestClient(app) as client:
        user,a=seed(app);_,pending=seed(app,status='pending_approval');_,unverified=seed(app,verified=False)
        route='/api/v1/account/preferences/language'
        for headers in [{},pending,unverified]:
            assert client.get(route,headers=headers).status_code in (401,403)
            assert client.put(route,headers=headers,json={'language':'es'}).status_code in (401,403)
        with app.state.db.transaction() as conn:conn.execute("UPDATE sessions SET revoked_at='2026-01-01' WHERE user_id=?",(user,))
        assert client.put(route,headers=a,json={'language':'es'}).status_code==401
        with closing(app.state.db.connect()) as conn:
            assert conn.execute("SELECT 1 FROM settings WHERE scope='user' AND owner_id=? AND key='language'",(user,)).fetchone() is None
