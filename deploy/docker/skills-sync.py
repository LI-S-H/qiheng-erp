#!/usr/bin/env python3
"""同步服务器技能目录；外层部署锁负责串行化发布操作。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import time
import uuid


class SyncError(Exception):
    """检测到冲突或无法安全同步。"""


def manifest(root):
    """计算完整文件树清单；不跟随任何链接或特殊文件。"""
    if root.is_symlink() or not root.is_dir():
        raise SyncError(f"技能目录无效或为符号链接：{root}")
    entries = {}
    for current, directories, files in os.walk(root, followlinks=False):
        base = Path(current)
        for name in sorted(directories + files):
            path = base / name
            relative = path.relative_to(root).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISDIR(mode):
                entries[relative] = "directory"
            elif stat.S_ISREG(mode):
                digest = hashlib.sha256()
                with path.open("rb") as content:
                    for chunk in iter(lambda: content.read(1024 * 1024), b""):
                        digest.update(chunk)
                entries[relative] = "sha256:" + digest.hexdigest()
            else:
                raise SyncError(f"技能目录包含符号链接或特殊文件：{path}")
    return entries


def permission_allows(info, is_directory):
    """依 POSIX owner、group、other 次序判断容器用户读取权限。"""
    mode = stat.S_IMODE(info.st_mode)
    required = 0o5 if is_directory else 0o4
    if info.st_uid == 10001:
        bits = (mode >> 6) & 0o7
    elif info.st_gid == 10001:
        bits = (mode >> 3) & 0o7
    else:
        # 容器可能还有 Docker Socket 等附加组；未知所属组时两类权限都要满足。
        bits = ((mode >> 3) & mode) & 0o7
    return bits & required == required


def require_canonical_mode(mode, is_directory, path):
    """采用固定权限，任何 chmod 改动都须先人工检查。"""
    expected = 0o755 if is_directory else 0o644
    if stat.S_IMODE(mode) != expected:
        raise SyncError(f"技能路径权限已变化，需先人工检查：{path}")


def require_canonical_owner(uid, gid, path):
    """受控技能树统一由 root 管理，避免无提示覆盖归属变更。"""
    if uid != 0 or gid != 0:
        raise SyncError(f"技能路径归属不是 root:root，请管理员检查：{path}")


def ensure_container_readable(target):
    """检查现有技能目录和文件可供 UID/GID 10001 或其他用户读取。"""
    if os.name == "nt":
        return
    for current, directories, files in os.walk(target, followlinks=False):
        for directory in [Path(current), *(Path(current) / name for name in directories)]:
            info = directory.stat()
            require_canonical_owner(info.st_uid, info.st_gid, path=directory)
            require_canonical_mode(info.st_mode, is_directory=True, path=directory)
            if not permission_allows(info, is_directory=True):
                raise SyncError(f"技能目录对容器用户不可读或不可遍历：{directory}")
        for name in files:
            path = Path(current) / name
            info = path.stat()
            require_canonical_owner(info.st_uid, info.st_gid, path=path)
            require_canonical_mode(info.st_mode, is_directory=False, path=path)
            if not permission_allows(info, is_directory=False):
                raise SyncError(f"技能文件对容器用户不可读：{path}")


def inspect(source, target, state):
    """检查来源、目标与基线；此步骤不修改文件。"""
    source, target, state = map(Path, (source, target, state))
    paths = [path.absolute() for path in (source, target, state)]
    if any(a == b or a in b.parents or b in a.parents
           for i, a in enumerate(paths) for b in paths[i + 1:]):
        raise SyncError("来源、目标和状态目录必须互相独立")
    if state.is_symlink() or target.is_symlink():
        raise SyncError("目标或状态目录不能是符号链接")
    if (state / "skills-sync.pending").exists():
        raise SyncError("上次技能同步中断，请检查状态目录中的快照后人工恢复")
    desired = manifest(source)
    base_file = state / "skills-sync.baseline.json"
    current = manifest(target) if target.exists() else None
    if current is not None:
        ensure_container_readable(target)
    if base_file.is_symlink():
        raise SyncError("技能基线不能是符号链接")
    if base_file.exists():
        try:
            baseline = json.loads(base_file.read_text(encoding="utf-8"))
        except (ValueError, OSError) as error:
            raise SyncError("技能基线无法读取") from error
        if current != baseline:
            raise SyncError("服务器技能目录与上次同步基线不同，请检查手工修改")
    elif current is not None and current != desired:
        raise SyncError("服务器技能目录已存在且未建立基线，请先人工检查")
    if current is not None:
        removed = [path for path, kind in current.items()
                   if path not in desired or ((kind == "directory") != (desired[path] == "directory"))]
        if removed:
            raise SyncError(f"仓库版本将删除或替换服务器技能路径，需先明确授权：{removed[0]}")
    return desired, current


def write_json_atomic(path, value):
    """通过同目录临时文件提交状态，避免写入半份 JSON。"""
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex)
    with temporary.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def replace_tree(source, target, before, after):
    """逐项替换且保留根 inode；调用方须确保部署期间没有外部编辑。"""
    expected = dict(before)

    def verify():
        if manifest(target) != expected or manifest(source) != after:
            raise SyncError("技能目录或快照在同步期间变化，请人工检查中断记录")
        ensure_container_readable(target)

    for relative, kind in sorted(before.items(), key=lambda item: item[0].count("/"), reverse=True):
        if relative not in after or after[relative] != kind and (kind == "directory" or after[relative] == "directory"):
            verify()
            path = target / relative
            if kind == "directory":
                path.rmdir()
            else:
                path.unlink()
            del expected[relative]
    for relative, kind in sorted(after.items(), key=lambda item: item[0].count("/")):
        if expected.get(relative) == kind:
            continue
        verify()
        destination = target / relative
        if kind == "directory":
            destination.mkdir(mode=0o755, exist_ok=False)
            if os.name == "posix":
                os.chown(destination, 0, 0)
            destination.chmod(0o755)
        else:
            temporary = destination.with_name(".skills-sync-" + uuid.uuid4().hex)
            previous_mtime = destination.stat().st_mtime_ns if destination.exists() else None
            shutil.copyfile(source / relative, temporary)
            if os.name == "posix":
                os.chown(temporary, 0, 0)
            temporary.chmod(0o644)
            if previous_mtime is not None and destination.name == "SKILL.md":
                # 指纹使用毫秒精度的 mtime 和文件大小；同大小修改也必须触发刷新。
                next_mtime = max(time.time_ns(), previous_mtime + 1_000_000_000)
                os.utime(temporary, ns=(next_mtime, next_mtime))
            os.replace(temporary, destination)
        expected[relative] = kind
    verify()


def apply(source, target, state):
    """同步完整目录；保留目标根目录，失败时保留状态和旧快照供检查。"""
    source, target, state = map(Path, (source, target, state))
    if os.name == "posix" and os.geteuid() != 0:
        raise SyncError("技能同步需要管理员以 sudo 执行")
    desired, current = inspect(source, target, state)
    if current == desired and (state / "skills-sync.baseline.json").is_file():
        return False
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="skills-stage-", dir=state))
    snapshot = state / ("skills-snapshot-" + uuid.uuid4().hex)
    shutil.copytree(source, stage / "files", symlinks=True)
    if manifest(stage / "files") != desired:
        raise SyncError("准备同步期间来源发生变化，请重试")
    if current is not None:
        shutil.copytree(target, snapshot, symlinks=True)
        if manifest(snapshot) != current:
            raise SyncError("准备同步期间服务器技能发生变化，请重试")
    # 重新核对，避免准备期间的手工修改被覆盖。
    if inspect(source, target, state) != (desired, current):
        raise SyncError("技能目录在准备期间发生变化，请重试")
    had_baseline = (state / "skills-sync.baseline.json").exists()
    marker = state / "skills-sync.pending"
    with marker.open("x", encoding="utf-8") as pending:
        json.dump({"snapshot": str(snapshot) if current is not None else None,
                   "stage": str(stage), "target": str(target)}, pending)
        pending.flush()
        os.fsync(pending.fileno())
    target_created = not target.exists()
    target.mkdir(mode=0o755, parents=True, exist_ok=True)
    if target_created:
        if os.name == "posix":
            os.chown(target, 0, 0)
        target.chmod(0o755)
    # 不替换顶层目录，避免现有 Docker bind mount 继续指向旧 inode。
    replace_tree(stage / "files", target, current or {}, desired)
    write_json_atomic(state / "skills-sync.baseline.json", desired)
    write_json_atomic(state / "skills-sync.last.json", {
        "target": str(target.absolute()), "before": current, "after": desired,
        "snapshot": str(snapshot.absolute()) if current is not None else None,
        "had_baseline": had_baseline, "restored": False,
    })
    marker.unlink()
    return True


def restore(target, state):
    """安全恢复最后一次同步的旧快照；首次创建不自动删除目标目录。"""
    target, state = map(Path, (target, state))
    if os.name == "posix" and os.geteuid() != 0:
        raise SyncError("技能恢复需要管理员以 sudo 执行")
    marker = state / "skills-sync.pending"
    if marker.exists() or marker.is_symlink():
        raise SyncError("存在中断记录，禁止自动恢复，请人工检查")
    last = state / "skills-sync.last.json"
    if last.is_symlink() or state.is_symlink() or target.is_symlink():
        raise SyncError("目标、状态目录或同步记录不能是符号链接")
    try:
        journal = json.loads(last.read_text(encoding="utf-8"))
        baseline = json.loads((state / "skills-sync.baseline.json").read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        raise SyncError("同步记录或基线无法读取") from error
    if journal.get("restored") or journal.get("target") != str(target.absolute()):
        raise SyncError("没有适用于当前目标的可恢复同步记录")
    before, after = journal.get("before"), journal.get("after")
    if before is None:
        raise SyncError("同步前目录不存在，保留当前目录并请人工处理；未执行恢复")
    removed = [path for path, kind in after.items()
               if path not in before or ((kind == "directory") != (before[path] == "directory"))]
    if removed:
        raise SyncError(f"恢复将删除或替换服务器技能路径，需先明确授权：{removed[0]}")
    snapshot = Path(journal["snapshot"])
    if snapshot.parent != state.absolute() or not snapshot.name.startswith("skills-snapshot-"):
        raise SyncError("快照位置无效")
    if baseline != after or manifest(target) != after:
        raise SyncError("当前技能与同步后基线不一致，禁止覆盖手工修改")
    ensure_container_readable(target)
    if manifest(snapshot) != before:
        raise SyncError("旧技能快照不完整或已变化，禁止恢复")
    ensure_container_readable(snapshot)
    with marker.open("x", encoding="utf-8") as pending:
        json.dump({"operation": "restore", "snapshot": str(snapshot), "target": str(target)}, pending)
        pending.flush()
        os.fsync(pending.fileno())
    replace_tree(snapshot, target, after, before)
    if journal.get("had_baseline"):
        write_json_atomic(state / "skills-sync.baseline.json", before)
    else:
        (state / "skills-sync.baseline.json").unlink()
    journal["restored"] = True
    write_json_atomic(last, journal)
    marker.unlink()


def main():
    parser = argparse.ArgumentParser(description="同步服务器 AI 技能目录")
    parser.add_argument("action", choices=("check", "apply", "restore"))
    parser.add_argument("source")
    parser.add_argument("target")
    parser.add_argument("state")
    args = parser.parse_args()
    try:
        changed = None
        if args.action == "check":
            inspect(args.source, args.target, args.state)
        elif args.action == "apply":
            changed = apply(args.source, args.target, args.state)
        else:
            restore(args.target, args.state)
    except (SyncError, OSError) as error:
        print(f"技能同步失败：{error}", file=sys.stderr)
        return 1
    print({"check": "技能同步检查通过", "apply": "技能同步完成" if changed else "技能无需同步", "restore": "技能恢复完成"}[args.action])
    return 0


if __name__ == "__main__":
    sys.exit(main())
