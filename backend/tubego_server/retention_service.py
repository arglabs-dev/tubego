"""Durable deadline sweeper; safe to restart or run alongside another sweeper."""
import logging
import signal
import threading
from tubego_server.config import Settings
from tubego_server.db import Database
from tubego_server.retention_policy import sweep_expired


def run(database,media_root,stop,interval=60):
    while not stop.is_set():
        try:sweep_expired(database,media_root)
        except Exception:
            # Keep filenames, source URLs and account data out of service logs.
            logging.error('Retention sweep failed; will retry')
        stop.wait(interval)


def main():
    settings=Settings.from_env();database=Database(settings.database_path);database.initialize()
    stop=threading.Event()
    signal.signal(signal.SIGTERM,lambda *_:stop.set())
    signal.signal(signal.SIGINT,lambda *_:stop.set())
    run(database,settings.data_dir/'media',stop)


if __name__=='__main__':main()
