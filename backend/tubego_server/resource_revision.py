"""Monotonic mutation revision; callers hold the same transaction as their effect."""
from tubego_server.delivery import read_setting,write_setting


def current(conn,resource_id):
    return read_setting(conn,'resource',resource_id,'intent_revision') or 0


def bump(conn,resource_id):
    revision=current(conn,resource_id)+1
    write_setting(conn,'resource',resource_id,'intent_revision',revision)
    return revision
