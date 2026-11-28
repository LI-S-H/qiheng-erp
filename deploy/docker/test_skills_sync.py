import importlib.util
import os
import stat
import time
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("skills_sync", Path(__file__).with_name("skills-sync.py"))
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


class SkillsSyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.source, self.target, self.state = root / "source", root / "target", root / "state"
        (self.source / "main" / "demo").mkdir(parents=True)
        (self.source / "main" / "demo" / "SKILL.md").write_text("初始", encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def apply(self):
        sync.apply(self.source, self.target, self.state)

    def test_initialize_and_update_preserves_root(self):
        self.apply()
        inode = self.target.stat().st_ino
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        self.assertEqual(inode, self.target.stat().st_ino)
        self.assertEqual("更新", (self.target / "main" / "demo" / "SKILL.md").read_text(encoding="utf-8"))

    @unittest.skipIf(os.name == "nt", "权限和 umask 需在 POSIX 系统验证")
    def test_new_directories_remain_accessible_under_restrictive_umask(self):
        previous = os.umask(0o077)
        try:
            self.apply()
        finally:
            os.umask(previous)
        for directory in (self.target, self.target / "main", self.target / "main" / "demo"):
            self.assertEqual(0o755, stat.S_IMODE(directory.stat().st_mode))

    @unittest.skipIf(os.name == "nt", "目录读取权限需在 POSIX 系统验证")
    def test_unreadable_existing_directory_is_rejected_without_chmod(self):
        self.apply()
        self.target.chmod(0o700)
        with self.assertRaises(sync.SyncError):
            self.apply()
        self.assertEqual(0o700, stat.S_IMODE(self.target.stat().st_mode))

    @unittest.skipIf(os.name == "nt", "文件权限需在 POSIX 系统验证")
    def test_unreadable_existing_skill_file_is_rejected_without_chmod(self):
        self.apply()
        file = self.target / "main" / "demo" / "SKILL.md"
        file.chmod(0o600)
        with self.assertRaises(sync.SyncError):
            self.apply()
        self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))

    def test_changed_content_same_size_advances_mtime(self):
        self.apply()
        target = self.target / "main" / "demo" / "SKILL.md"
        future = time.time_ns() + 20_000_000_000
        os.utime(target, ns=(future, future))
        previous = target.stat().st_mtime_ns
        (self.source / "main" / "demo" / "SKILL.md").write_text("不同", encoding="utf-8")
        self.apply()
        self.assertGreater(target.stat().st_mtime_ns, previous)

    def test_unchanged_tree_does_not_rewrite_files(self):
        self.apply()
        file = self.target / "main" / "demo" / "SKILL.md"
        before = file.stat().st_mtime_ns
        self.assertIs(sync.apply(self.source, self.target, self.state), False)
        self.assertEqual(before, file.stat().st_mtime_ns)

    def test_restore_reverts_successful_update_and_preserves_root(self):
        self.apply()
        inode = self.target.stat().st_ino
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        sync.restore(self.target, self.state)
        self.assertEqual("初始", (self.target / "main" / "demo" / "SKILL.md").read_text(encoding="utf-8"))
        self.assertEqual(inode, self.target.stat().st_ino)
        sync.inspect(self.source, self.target, self.state)

    def test_restore_rejects_manual_changes(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        file = self.target / "main" / "demo" / "SKILL.md"
        file.write_text("手工编辑", encoding="utf-8")
        with self.assertRaises(sync.SyncError):
            sync.restore(self.target, self.state)
        self.assertEqual("手工编辑", file.read_text(encoding="utf-8"))

    def test_restore_rejects_damaged_snapshot(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        metadata = __import__("json").loads((self.state / "skills-sync.last.json").read_text(encoding="utf-8"))
        (Path(metadata["snapshot"]) / "main" / "demo" / "SKILL.md").write_text("损坏", encoding="utf-8")
        with self.assertRaises(sync.SyncError):
            sync.restore(self.target, self.state)
        self.assertEqual("更新", (self.target / "main" / "demo" / "SKILL.md").read_text(encoding="utf-8"))

    def test_restore_rejects_deletion_of_new_files(self):
        self.apply()
        (self.source / "main" / "demo" / "new.md").write_text("新增", encoding="utf-8")
        self.apply()
        with self.assertRaises(sync.SyncError):
            sync.restore(self.target, self.state)
        self.assertTrue((self.target / "main" / "demo" / "new.md").is_file())

    def test_restore_first_creation_requires_manual_decision(self):
        self.apply()
        with self.assertRaises(sync.SyncError):
            sync.restore(self.target, self.state)
        self.assertTrue(self.target.is_dir())

    def test_repository_deletion_requires_approval(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").unlink()
        with self.assertRaises(sync.SyncError):
            self.apply()
        self.assertTrue((self.target / "main" / "demo" / "SKILL.md").exists())

    def test_posix_permission_owner_precedence_over_other(self):
        file_info = SimpleNamespace(st_uid=10001, st_gid=10001, st_mode=0o004)
        self.assertFalse(sync.permission_allows(file_info, is_directory=False))
        dir_info = SimpleNamespace(st_uid=10001, st_gid=10001, st_mode=0o005)
        self.assertFalse(sync.permission_allows(dir_info, is_directory=True))
        group_info = SimpleNamespace(st_uid=0, st_gid=10001, st_mode=0o004)
        self.assertFalse(sync.permission_allows(group_info, is_directory=False))

    def test_canonical_modes_reject_chmod_only_changes(self):
        sync.require_canonical_mode(0o755, is_directory=True, path="dir")
        sync.require_canonical_mode(0o644, is_directory=False, path="file")
        with self.assertRaises(sync.SyncError):
            sync.require_canonical_mode(0o700, is_directory=True, path="dir")
        with self.assertRaises(sync.SyncError):
            sync.require_canonical_mode(0o600, is_directory=False, path="file")

    def test_canonical_ownership_rejects_chown_only_changes(self):
        sync.require_canonical_owner(0, 0, path="skill")
        with self.assertRaises(sync.SyncError):
            sync.require_canonical_owner(10001, 0, path="skill")
        with self.assertRaises(sync.SyncError):
            sync.require_canonical_owner(0, 999, path="skill")

    @unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "真实 chown 测试需要 POSIX root")
    def test_chown_only_change_blocks_sync_and_restore(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        file = self.target / "main" / "demo" / "SKILL.md"
        os.chown(file, 10001, 0)
        for action in (self.apply, lambda: sync.restore(self.target, self.state)):
            with self.assertRaises(sync.SyncError):
                action()
            self.assertEqual(10001, file.stat().st_uid)
            self.assertEqual("更新", file.read_text(encoding="utf-8"))

    @unittest.skipIf(os.name == "nt", "文件模式需在 POSIX 系统验证")
    def test_chmod_only_conflict_not_overwritten_or_restored(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        self.apply()
        file = self.target / "main" / "demo" / "SKILL.md"
        file.chmod(0o600)
        for action in (self.apply, lambda: sync.restore(self.target, self.state)):
            with self.assertRaises(sync.SyncError):
                action()
            self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
            self.assertEqual("更新", file.read_text(encoding="utf-8"))

    def test_unknown_supplementary_group_is_checked_conservatively(self):
        file_info = SimpleNamespace(st_uid=0, st_gid=999, st_mode=0o004)
        self.assertFalse(sync.permission_allows(file_info, is_directory=False))
        file_info.st_mode = 0o044
        self.assertTrue(sync.permission_allows(file_info, is_directory=False))
        dir_info = SimpleNamespace(st_uid=0, st_gid=999, st_mode=0o005)
        self.assertFalse(sync.permission_allows(dir_info, is_directory=True))
        dir_info.st_mode = 0o055
        self.assertTrue(sync.permission_allows(dir_info, is_directory=True))

    def test_modified_added_or_removed_files_block_update(self):
        for action in ("modified", "added", "removed"):
            with self.subTest(action=action), tempfile.TemporaryDirectory() as temp:
                base = Path(temp)
                target, state = base / "target", base / "state"
                sync.apply(self.source, target, state)
                file = target / "main" / "demo" / "SKILL.md"
                if action == "modified":
                    file.write_text("手工修改", encoding="utf-8")
                elif action == "added":
                    (target / ".local").write_text("extra", encoding="utf-8")
                else:
                    file.unlink()
                with self.assertRaises(sync.SyncError):
                    sync.apply(self.source, target, state)

    def test_existing_unmanaged_directory_not_overwritten(self):
        self.target.mkdir()
        (self.target / "local").write_text("local", encoding="utf-8")
        with self.assertRaises(sync.SyncError):
            self.apply()
        self.assertEqual("local", (self.target / "local").read_text(encoding="utf-8"))

    def test_interrupted_sync_is_detected(self):
        self.apply()
        (self.state / "skills-sync.pending").write_text("interrupted", encoding="utf-8")
        with self.assertRaises(sync.SyncError):
            self.apply()

    def test_failed_replacement_keeps_snapshot_and_blocks_retry(self):
        self.apply()
        (self.source / "main" / "demo" / "SKILL.md").write_text("更新", encoding="utf-8")
        with patch.object(sync.os, "replace", side_effect=OSError("模拟中断")):
            with self.assertRaises(OSError):
                self.apply()
        self.assertTrue((self.state / "skills-sync.pending").is_file())
        self.assertTrue(any(self.state.glob("skills-snapshot-*")))
        with self.assertRaises(sync.SyncError):
            self.apply()

    def test_check_does_not_create_files(self):
        sync.inspect(self.source, self.target, self.state)
        self.assertFalse(self.target.exists())
        self.assertFalse(self.state.exists())

    @unittest.skipUnless(hasattr(os, "symlink"), "需要符号链接支持")
    def test_symlink_is_rejected(self):
        try:
            os.symlink(self.source / "main", self.source / "link", target_is_directory=True)
        except OSError:
            self.skipTest("当前系统不允许创建符号链接")
        with self.assertRaises(sync.SyncError):
            self.apply()


if __name__ == "__main__":
    unittest.main()
