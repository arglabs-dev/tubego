"""Filesystem boundaries shared by the independent Telegram and mobile channels."""

import os
from pathlib import Path


def validate_channel_paths(bot_download_dir, mobile_data_dir):
    """Reject overlapping roots, including aliases through existing symlinks.

    Both services must validate these same settings before creating storage.
    Siblings on a shared disk are permitted; nested roots are not.
    """
    if not os.fspath(bot_download_dir).strip() or not os.fspath(mobile_data_dir).strip():
        raise ValueError("Channel storage directories must not be empty")
    bot = Path(bot_download_dir).expanduser().resolve()
    mobile = Path(mobile_data_dir).expanduser().resolve()
    if bot == mobile or bot in mobile.parents or mobile in bot.parents:
        raise ValueError("Telegram downloads and mobile data directories must not overlap")
    return bot, mobile


def bot_download_directory(download_dir=None):
    bot = download_dir if download_dir is not None else os.getenv("TUBEGO_BOT_DOWNLOAD_DIR", "downloads")
    mobile = os.getenv("TUBEGO_DATA_DIR", "data/mobile")
    if not str(bot).strip() or not mobile.strip():
        raise ValueError("Channel storage directories must not be empty")
    validate_channel_paths(bot, mobile)
    # Preserve relative paths expected by existing Telegram, CLI and Flet code.
    return os.path.expanduser(os.fspath(bot))


def owned_file(root, file_path):
    """Return whether a real file is inside root without following file symlinks."""
    path = Path(file_path)
    return not path.is_symlink() and Path(root).resolve() in path.resolve().parents
