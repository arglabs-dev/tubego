"""Offline checks for Telegram/mobile storage separation; no bot import/network."""

import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from src.core import Downloader
from src.manager import DownloadManager
from src.storage import validate_channel_paths


class IndependentChannelsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bot = self.root / "telegram"
        self.mobile = self.root / "mobile"
        self.mobile.mkdir()
        self.env = patch.dict(os.environ, {
            "TUBEGO_BOT_DOWNLOAD_DIR": str(self.bot),
            "TUBEGO_DATA_DIR": str(self.mobile),
        })
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_defaults_and_explicit_downloader_remain_compatible(self):
        previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(DownloadManager().base_dir, "downloads")
        self.assertEqual(Downloader().download_dir, "downloads")
        explicit = str(self.root / "cli-output")
        self.assertEqual(Downloader(explicit).download_dir, explicit)

    def test_environment_routes_tasks_only_to_bot_directory(self):
        manager = DownloadManager()
        task = manager.get_task(manager.create_task("https://example.test/video"))
        self.assertEqual(task["downloader"].download_dir, str(self.bot))
        self.assertEqual(list(self.mobile.iterdir()), [])

    def test_equal_nested_and_symlink_aliases_fail_before_creating_directory(self):
        with self.assertRaises(ValueError):
            validate_channel_paths("", self.mobile)
        for bot, mobile in ((self.mobile, self.mobile),
                            (self.mobile / "bot", self.mobile),
                            (self.root, self.mobile)):
            with self.subTest(bot=bot, mobile=mobile):
                with self.assertRaisesRegex(ValueError, "must not overlap"):
                    validate_channel_paths(bot, mobile)
        alias = self.root / "alias"
        alias.symlink_to(self.mobile, target_is_directory=True)
        with self.assertRaises(ValueError):
            DownloadManager(alias)
        with self.assertRaises(ValueError):
            DownloadManager(self.mobile / "not-created")
        self.assertFalse((self.mobile / "not-created").exists())

    def test_listing_archiving_and_cleanup_do_not_touch_mobile(self):
        manager = DownloadManager()
        mobile_video = self.mobile / "same.mp4"
        mobile_video.write_bytes(b"mobile content")
        (self.bot / "same.mp4").write_bytes(b"telegram content")
        (self.bot / "mobile-link.mp4").symlink_to(mobile_video)
        self.assertEqual(manager.get_local_files(), ["same.mp4"])
        self.assertIsNone(manager.create_task_from_file("../mobile/same.mp4"))
        self.assertIsNone(manager.create_task_from_file("mobile-link.mp4"))
        task_id = manager.create_task_from_file("same.mp4")
        self.assertTrue(manager.archive_task_file(task_id))
        self.assertEqual(manager.clear_uploaded_dir(), (True, 1))
        self.assertEqual(mobile_video.read_bytes(), b"mobile content")
        self.assertEqual(list(self.mobile.iterdir()), [mobile_video])

    def test_task_delete_refuses_outside_channel(self):
        manager = DownloadManager()
        victim = self.mobile / "keep.mp4"
        victim.write_bytes(b"keep")
        task_id = manager.create_task("https://example.test")
        manager.tasks[task_id]["file_path"] = str(victim)
        self.assertFalse(manager.delete_task_data(task_id))
        self.assertFalse(manager.archive_task_file(task_id))
        self.assertEqual(victim.read_bytes(), b"keep")
        manager.tasks[task_id]["file_path"] = str(self.bot / "delete.mp4")
        Path(manager.tasks[task_id]["file_path"]).write_bytes(b"delete")
        self.assertTrue(manager.delete_task_data(task_id))
        self.assertFalse((self.bot / "delete.mp4").exists())

    def test_uploaded_symlink_cannot_clean_other_channel(self):
        self.bot.mkdir()
        (self.bot / "uploaded").symlink_to(self.mobile, target_is_directory=True)
        with self.assertRaises(ValueError):
            DownloadManager()


if __name__ == "__main__":
    unittest.main()
