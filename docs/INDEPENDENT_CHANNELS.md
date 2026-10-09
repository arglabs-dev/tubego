# Telegram and Android coexistence

Telegram remains operational during the mobile MVP. The channels have independent
queues, histories, media files and delivery destinations. Sending a Telegram URL
does not create a mobile resource; adding a mobile URL does not upload to Telegram.
There is no automatic import of existing Telegram downloads.

## Storage configuration

Configure both services with the same absolute paths:

```dotenv
TUBEGO_BOT_DOWNLOAD_DIR=/srv/tubego/telegram-downloads
TUBEGO_DATA_DIR=/srv/tubego/mobile
```

`TUBEGO_BOT_DOWNLOAD_DIR` defaults to `downloads`, preserving existing bot behavior
and the `uploaded` archive below it. `TUBEGO_DATA_DIR` defaults to `data/mobile`.
Relative defaults are resolved from the process working directory. CLI/Flet
`Downloader()` keeps its existing `downloads` default and explicit directory API.

Telegram's `DownloadManager` validates the roots before creating directories.
Mobile startup must call `src.storage.validate_channel_paths(bot_root, mobile_root)`
with its configured paths before creating data. Equal or nested directories are
rejected in either direction, including existing symbolic-link aliases. Do not
change mounts or symlink targets while services are running. Only trusted operators
should be able to modify the storage directories.

Telegram listings, file tasks, archive and deletion are restricted to Telegram
storage. Mobile cleanup must only operate on its own data root; Telegram's uploaded
cleanup must never be used as a global mobile cleanup operation. The bot's Telegram
session (`data/user_session`), token and logs remain separate from mobile accounts.

## Running both services

Keep the bot and API in separate processes/containers so restarting one does not
restart the other. The existing `deploy/docker-compose.yml` starts only the bot;
the API is a separate service. For the default bot mount, use
`TUBEGO_BOT_DOWNLOAD_DIR=/app/downloads`; use `TUBEGO_DATA_DIR=/app/data/mobile`
consistently when that is the API data root inside its container. Prefer separate
volume mounts for Telegram media and mobile data; do not expose Telegram media via
the API. Mobile credentials are not Telegram bot credentials.

Existing Telegram links and uploads keep their workflow. A mobile user cannot
list or delete Telegram files. Restarting the bot still loses its in-memory task
list as before; this change does not add persistence or synchronization to Telegram.

Both channels can share the same physical disk. Channel isolation does not imply
separate capacity. The global 90% disk guard is covered by PLA-249, not this card;
its bot hook belongs before a download starts and in its progress callback, using
the filesystem containing `manager.base_dir`, without merging the channel queues.

## Offline verification

With the existing `yt-dlp` dependency installed, run:

```sh
python -m unittest discover -s tests -p 'test_channel_storage.py' -v
```

These tests do not import the bot, need Telegram credentials or contact a network.
They cover defaults, configuration overlap, aliases, queue destinations and
Telegram cleanup preserving mobile media.
