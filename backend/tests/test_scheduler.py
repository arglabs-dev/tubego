from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from tubego_server.db import Database
from tubego_server.scheduler import Scheduler, put_setting


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / 'queue.sqlite')
    database.initialize()
    return database


def seed(db, user, count, priority='normal'):
    with db.transaction() as conn:
        conn.execute("INSERT INTO users VALUES(?,?,?,'user','approved','verified','now','now')", (user, user+'@test.dev', 'hash'))
        put_setting(conn, 'user', user, 'priority_level', priority)
        for i in range(count):
            key = f'{user}-{i:03}'
            conn.execute("INSERT INTO resources(id,user_id,source_url,created_at,updated_at) VALUES(?,?,?,'now','now')", (key,user,'https://example.com/'+key))
            conn.execute("INSERT INTO tasks(id,user_id,resource_id,created_at,updated_at) VALUES(?,?,?,?,'now')", (key,user,key,f'{i:03}'))


def drain(db, count):
    users = []
    for _ in range(count):
        scheduler = Scheduler(db)  # cursor survives instances/restarts
        task = scheduler.claim()
        users.append(task['user_id'])
        assert scheduler.finish(task['id'],task['claim_token'])
    return users


def test_weighted_round_robin_each_user(db):
    seed(db,'a',30,'prioritario')
    seed(db,'b',30,'prioritario')
    seed(db,'c',30)
    seed(db,'d',30)
    users = drain(db,24)
    assert users == ['a','b','c','d','a','b','a','b']*3
    assert users.count('a') == users.count('b') == 9
    assert users.count('c') == users.count('d') == 3


def test_one_priority_one_normal_ratio(db):
    seed(db,'a',9,'prioritario')
    seed(db,'b',3)
    assert drain(db,12) == ['a','b','a','a']*3


def test_empty_queues_yield_and_reactivated_queue_progresses(db):
    seed(db,'a',1,'prioritario')
    seed(db,'b',6)
    assert drain(db,4) == ['a','b','b','b']
    with db.transaction() as conn:
        conn.execute("UPDATE tasks SET status='queued' WHERE id='a-000'")
    assert 'a' in drain(db,2)


def test_task_priority_next_and_fifo_never_preempts(db):
    seed(db,'a',3)
    scheduler = Scheduler(db)
    first = scheduler.claim()
    with db.transaction() as conn:
        conn.execute("UPDATE tasks SET priority=10 WHERE id='a-002'")
    assert scheduler.claim() is None
    assert scheduler.preview() is None
    scheduler.finish(first['id'],first['claim_token'])
    second = scheduler.claim()
    assert second['id'] == 'a-002'
    scheduler.finish(second['id'],second['claim_token'])
    assert scheduler.claim()['id'] == 'a-001'


def test_preview_does_not_mutate_or_consume_turn(db):
    seed(db,'a',3,'prioritario'); seed(db,'b',2)
    scheduler = Scheduler(db)
    assert scheduler.preview()['id'] == scheduler.preview()['id'] == 'a-000'
    with db.transaction() as conn:
        assert conn.execute("SELECT 1 FROM settings WHERE key='scheduler_cursor'").fetchone() is None
    assert drain(db,4) == ['a','b','a','a']


def test_two_connections_claim_only_one(db):
    seed(db,'a',3)
    with ThreadPoolExecutor(2) as pool:
        tasks = list(pool.map(lambda _: Scheduler(Database(db.path)).claim(), range(2)))
    assert sum(task is not None for task in tasks) == 1
    with db.transaction() as conn:
        assert conn.execute("SELECT count(*) FROM tasks WHERE status='running'").fetchone()[0] == 1


def test_recovery_fences_stale_worker_and_does_not_happen_on_creation(db):
    seed(db,'a',3)
    scheduler = Scheduler(db)
    first = scheduler.claim()
    restarted = Scheduler(Database(db.path))
    assert restarted.claim() is None
    assert restarted.recover_after_worker_stopped() == 1
    assert restarted.recover_after_worker_stopped() == 0
    new = restarted.claim()
    assert new['id'] == first['id']
    assert not scheduler.finish(first['id'],first['claim_token'])
    assert restarted.finish(new['id'],new['claim_token'])


def test_unapproved_and_unverified_users_cannot_run(db):
    seed(db,'a',2)
    with db.transaction() as conn:
        conn.execute("UPDATE users SET status='blocked' WHERE id='a'")
    assert Scheduler(db).claim() is None
    with db.transaction() as conn:
        conn.execute("UPDATE users SET status='approved',email_verified_at=NULL WHERE id='a'")
    assert Scheduler(db).claim() is None
