"""Quality warnings remain part of private history, including when files are gone."""
from test_private_library import service,owner,media
from tubego_server.scheduler import put_setting

def test_quality_notice_survives_server_deletion_and_is_owner_scoped(service):
    app,client=service;alice=owner(app);bob=owner(app)
    for code in ('lower_quality_available','quality_unknown'):
        rid,_=media(app,alice)
        with app.state.db.transaction() as conn:
            put_setting(conn,'resource',rid,'quality_notice',code)
            conn.execute("UPDATE tasks SET status='completed' WHERE resource_id=?",(rid,))
            conn.execute("UPDATE resources SET server_deleted_at='2026-01-01' WHERE id=?",(rid,))
        response=client.get('/api/v1/resources/'+rid,headers=alice['headers'])
        assert response.status_code==200
        assert response.json()['latest_task']['quality_notice']==code
        assert response.json()['server_available'] is False
        assert client.get('/api/v1/resources/'+rid,headers=bob['headers']).status_code==404
