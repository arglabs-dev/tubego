from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from fastapi import HTTPException
from test_private_library import service,owner
from test_device_delivery import add_device,ready
from tubego_server.delivery import read_setting,write_setting,publish_ready
from tubego_server.retention import delete_if_eligible,sweep_confirmed,unlink_server_copy
from tubego_server.routers.retention import current_admin


def preserve(app,user,value=True):
    with app.state.db.transaction() as conn:write_setting(conn,'user',user['id'],'preserve_server_files',value)


def confirm(client,user,key,published):
    return client.post(f'/api/v1/resources/{key}/deliveries/confirm',json={'size_bytes':published['size_bytes'],'sha256':published['sha256']},headers=user['headers'])


def test_all_snapshot_recipients_confirm_then_only_server_copy_removed(service):
    app,client=service;user=owner(app);other=add_device(app,user);key,pub=ready(app,user)
    file=app.state.settings.data_dir/'media'/(key+'.mp4')
    assert not confirm(client,user,key,pub).json()['server_copy_removed'] and file.exists()
    late=add_device(app,user)
    last=confirm(client,other,key,pub);assert last.status_code==200 and last.json()['server_copy_removed']
    assert not file.exists()
    assert confirm(client,other,key,pub).json()==last.json()
    assert confirm(client,user,key,pub).json()['server_copy_removed']
    for device in (user,other):
        item=client.get('/api/v1/device/sync',headers=device['headers']).json()['deliveries'][0]
        assert item['delivery_status']=='complete' and not item['server_available']
        assert item['title']=='Lesson' and item['sha256']==pub['sha256']
    assert client.get('/api/v1/device/sync',headers=late['headers']).json()['deliveries']==[]
    with app.state.db.transaction() as conn:
        row=conn.execute('SELECT * FROM resources WHERE id=?',(key,)).fetchone()
        assert row['source_url'] and row['first_delivered_at'] and row['ready_at']
        assert conn.execute("SELECT count(*) FROM deliveries WHERE resource_id=? AND confirmed_at IS NOT NULL",(key,)).fetchone()[0]==2
        assert conn.execute("SELECT count(*) FROM audit WHERE action='resource.retention_removed' AND target_id=?",(key,)).fetchone()[0]==1


def test_admin_only_default_disabled_and_disabling_cleans_own_target(service):
    app,client=service;admin=owner(app,'admin');alice=owner(app);bob=owner(app)
    endpoint=f"/api/v1/admin/users/{alice['id']}/retention"
    assert client.get('/api/v1/admin/users/retention',headers=alice['headers']).status_code==403
    assert client.put(endpoint,json={'preserve_server_files':True},headers=alice['headers']).status_code==403
    assert client.put(endpoint,json={'preserve_server_files':'true'},headers=admin['headers']).status_code==422
    listing=client.get('/api/v1/admin/users/retention?limit=1',headers=admin['headers']).json()
    assert len(listing['items'])==1 and not listing['items'][0]['preserve_server_files'] and listing['next_cursor']
    assert client.put(endpoint,json={'preserve_server_files':True},headers=admin['headers']).status_code==200
    preserve(app,bob)
    key,pub=ready(app,alice);bkey,bpub=ready(app,bob)
    assert not confirm(client,alice,key,pub).json()['server_copy_removed']
    confirm(client,bob,bkey,bpub)
    response=client.put(endpoint,json={'preserve_server_files':False},headers=admin['headers'])
    assert response.json()['removed_server_copies']==1
    assert not (app.state.settings.data_dir/'media'/(key+'.mp4')).exists()
    assert (app.state.settings.data_dir/'media'/(bkey+'.mp4')).exists()


def test_revocation_removes_waiting_recipient_but_empty_is_not_delivered(service):
    app,client=service;user=owner(app);other=add_device(app,user);key,pub=ready(app,user)
    assert not confirm(client,user,key,pub).json()['server_copy_removed']
    assert client.post(f"/api/v1/account/devices/{other['id']}/revoke",headers=user['headers']).status_code==200
    assert not (app.state.settings.data_dir/'media'/(key+'.mp4')).exists()
    user2=owner(app);key2,pub2=ready(app,user2)
    # All recipients revoked without any confirmation must never trigger early deletion.
    client.post(f"/api/v1/account/devices/{user2['device']}/revoke",headers=user2['headers'])
    assert not delete_if_eligible(app.state.db,app.state.settings.data_dir/'media',key2)
    assert (app.state.settings.data_dir/'media'/(key2+'.mp4')).exists()
    with app.state.db.transaction() as conn:write_setting(conn,'resource',key2,'publication_recipients',[])
    assert not delete_if_eligible(app.state.db,app.state.settings.data_dir/'media',key2)


def test_eligibility_revalidated_after_candidate_scan_and_publication_change(service,monkeypatch):
    import tubego_server.retention as retention
    app,client=service;user=owner(app);preserve(app,user);key,pub=ready(app,user);confirm(client,user,key,pub)
    preserve(app,user,False)
    original=retention.delete_if_eligible
    def intervene(*args,**kwargs):
        preserve(app,user,True)
        return original(*args,**kwargs)
    monkeypatch.setattr(retention,'delete_if_eligible',intervene)
    assert sweep_confirmed(app.state.db,app.state.settings.data_dir/'media',user['id'])==0
    file=app.state.settings.data_dir/'media'/(key+'.mp4');assert file.exists()
    preserve(app,user,False)
    file.write_bytes(b'Changed contents');publish_ready(app.state.db,file.parent,key,file.name)
    assert not original(app.state.db,file.parent,key)


@pytest.mark.parametrize('change',["UPDATE users SET role='user'","UPDATE sessions SET revoked_at='now'","UPDATE users SET status='blocked'"])
def test_admin_rechecks_mutation_principal_in_transaction(service,change):
    app,client=service;admin=owner(app,'admin')
    principal={**admin,'session_id':admin['session']}
    with app.state.db.transaction() as conn:
        current_admin(conn,principal)
        conn.execute(change)
        with pytest.raises(HTTPException):current_admin(conn,principal)


def test_repeated_concurrent_confirmation_does_not_delete_history_twice(service):
    app,client=service;user=owner(app);key,pub=ready(app,user)
    with ThreadPoolExecutor(3) as pool:responses=list(pool.map(lambda _:confirm(client,user,key,pub),range(3)))
    assert all(r.status_code==200 and r.json()['server_copy_removed'] for r in responses)
    with app.state.db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM audit WHERE action='resource.retention_removed'",()).fetchone()[0]==1


def test_expiry_extension_still_obeys_preserve_and_approved_gates(service):
    app,client=service;user=owner(app);key,pub=ready(app,user);root=app.state.settings.data_dir/'media'
    preserve(app,user)
    assert not delete_if_eligible(app.state.db,root,key,expired=lambda conn,row:True)
    preserve(app,user,False)
    with app.state.db.transaction() as conn:conn.execute("UPDATE users SET status='blocked' WHERE id=?",(user['id'],))
    assert not delete_if_eligible(app.state.db,root,key,expired=lambda conn,row:True)


def test_unlink_never_follows_symlinks_or_parent_traversal(tmp_path):
    root=tmp_path/'media';root.mkdir();outside=tmp_path/'outside';outside.mkdir();victim=outside/'secret.mp4';victim.write_bytes(b'keep')
    (root/'link').symlink_to(outside,target_is_directory=True)
    (root/'file.mp4').symlink_to(victim)
    for path in ['link/secret.mp4','file.mp4','../outside/secret.mp4',str(victim)]:
        with pytest.raises((OSError,ValueError)):unlink_server_copy(root,path)
    assert victim.read_bytes()==b'keep'


def test_corrupt_foreign_namespace_reference_cannot_delete_other_user_file(service):
    app,client=service;user=owner(app);other=owner(app);key,pub=ready(app,user)
    root=app.state.settings.data_dir/'media';foreign=root/other['id'];foreign.mkdir();victim=foreign/'resource.mp4';victim.write_bytes(b'0123456789')
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET server_path=? WHERE id=?',(str(victim.relative_to(root)),key))
    response=confirm(client,user,key,pub)
    assert response.status_code==200 and not response.json()['server_copy_removed']
    assert victim.exists()


def test_normalized_worker_owner_namespace_is_cleaned(service):
    import uuid
    app,client=service;user=owner(app);key,pub=ready(app,user)
    root=app.state.settings.data_dir/'media'
    path=root/str(uuid.UUID(user['id']))/str(uuid.UUID(key))/'media.mp4'
    path.parent.mkdir(parents=True);path.write_bytes(b'0123456789')
    with app.state.db.transaction() as conn:
        conn.execute('UPDATE resources SET server_path=? WHERE id=?',(str(path),key))
    assert confirm(client,user,key,pub).json()['server_copy_removed']
    assert not path.exists()
