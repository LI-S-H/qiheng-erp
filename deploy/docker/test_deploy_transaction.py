"""发布事务的行为测试，不依赖真实 Docker、数据库或远端仓库。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("deploy.sh")
BASH = shutil.which("bash")
if os.name == "nt" and Path("E:/Develop/Git/Git/bin/bash.exe").is_file():
    BASH = "E:/Develop/Git/Git/bin/bash.exe"


@unittest.skipUnless(BASH, "需要 Bash")
class DeployTransactionTest(unittest.TestCase):
    def run_entry(self, action, files, existing_helper=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            src, stub, target = root / "source", root / "stub", root / "root"
            src.mkdir()
            stub.mkdir()
            shutil.copyfile(SCRIPT, src / "deploy.sh")
            for name, content in files.items():
                path = (target if name.startswith("config/") else src) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            if existing_helper is not None:
                (target / "bin").mkdir(parents=True, exist_ok=True)
                (target / "bin" / "skills-sync.py").write_text(existing_helper, encoding="utf-8")
            for name in ("docker", "flock", "python3"):
                path = stub / name
                path.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
                path.chmod(0o755)
            env = dict(os.environ, TEST_ROOT=directory, TEST_ACTION=action, MSYS_NO_PATHCONV="1")
            harness = r'''set -e
if command -v cygpath >/dev/null; then TEST_ROOT=$(cygpath -u "$TEST_ROOT"); fi
export PATH="$TEST_ROOT/stub:$PATH"
export DEPLOY_ROOT="$TEST_ROOT/root"
exec bash "$TEST_ROOT/source/deploy.sh" --lock-held "$TEST_ACTION"
'''
            result = subprocess.run([BASH, "-c", harness], env=env, text=True,
                                    encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT)
            helper = target / "bin" / "skills-sync.py"
            return result.returncode, result.stdout, helper.read_text() if helper.is_file() else None, (target / "config" / ".env").exists()

    def test_init_installs_helper_only_when_absent(self):
        files = {"skills-sync.py": "new-helper", ".env.example": "MYSQL_PASSWORD=CHANGE_ME\n",
                 "compose.infra.yaml": "services: {}\n"}
        code, output, helper, _ = self.run_entry("init", files)
        self.assertEqual(0, code, output)
        self.assertEqual("new-helper", helper)
        code, output, helper, _ = self.run_entry("init", files, existing_helper="existing-helper")
        self.assertEqual(0, code, output)
        self.assertEqual("existing-helper", helper)

    def test_init_missing_helper_does_not_create_configuration(self):
        code, output, _, config_exists = self.run_entry("init", {".env.example": "x\n"})
        self.assertNotEqual(0, code, output)
        self.assertFalse(config_exists)

    def test_private_configuration_cannot_redirect_deploy_root(self):
        code, output, _, _ = self.run_entry("status", {
            "config/.env": "DEPLOY_ROOT=/different-root\nMYSQL_ROOT_PASSWORD=x\nMYSQL_PASSWORD=x\nREDIS_PASSWORD=x\n"})
        self.assertNotEqual(0, code, output)
        self.assertIn("DEPLOY_ROOT", output)

    def test_application_exports_custom_deploy_root_to_compose(self):
        source = SCRIPT.read_text(encoding="utf-8")
        start = source.index("application() {")
        end = source.index("database() {", start)
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, TEST_ROOT=directory, MSYS_NO_PATHCONV="1")
            harness = r'''set -Eeuo pipefail
if command -v cygpath >/dev/null; then TEST_ROOT=$(cygpath -u "$TEST_ROOT"); fi
DEPLOY_ROOT="$TEST_ROOT/custom"; RELEASES_DIR="$DEPLOY_ROOT/releases"; CONFIG_FILE="$DEPLOY_ROOT/config/.env"
v=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
mkdir -p "$RELEASES_DIR/$v/deploy/docker"
touch "$RELEASES_DIR/$v/deploy/docker/compose.app.yaml"
fail() { printf 'failed:%s\n' "$*"; exit 1; }
timeout() { printf 'root=%s\nversion=%s\n' "$DEPLOY_ROOT" "$APP_VERSION"; }
''' + source[start:end] + '\napplication "$v" config --quiet\n'
            result = subprocess.run([BASH, "-c", harness], env=env, text=True,
                                    encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("/custom", result.stdout)
            self.assertIn("version=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", result.stdout)

    def run_scenario(self, failure="none", previous=True):
        source = SCRIPT.read_text(encoding="utf-8")
        start = source.index("# BEGIN SKILLS DEPLOY TRANSACTION")
        end = source.index("# END SKILLS DEPLOY TRANSACTION", start)
        functions = source[start:end]
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ, TEST_ROOT=directory, FAILURE=failure,
                       PREVIOUS="yes" if previous else "no", MSYS_NO_PATHCONV="1")
            harness = r'''
set -Eeuo pipefail
if command -v cygpath >/dev/null; then TEST_ROOT=$(cygpath -u "$TEST_ROOT"); fi
STATE_DIR="$TEST_ROOT/state"; DEPLOY_ROOT="$TEST_ROOT/root"; RELEASES_DIR="$TEST_ROOT/releases"
mkdir -p "$STATE_DIR" "$DEPLOY_ROOT/bin" "$RELEASES_DIR"
candidate_version=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
current_version=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
[[ $PREVIOUS == yes ]] || current_version=''
log() { printf 'log:%s\n' "$*"; }
fail() { printf 'failed:%s\n' "$*"; exit 1; }
application() {
  printf 'app:%s:%s\n' "$1" "$2"
  [[ $FAILURE != "$2" ]]
}
activate() { printf 'activate:%s\n' "$1"; [[ $FAILURE != activate ]]; }
save_version() { printf '%s\n' "$2" > "$STATE_DIR/$1"; }
''' + functions + r'''
skills_sync() { printf 'sync:%s\n' "$1"; [[ $FAILURE != "$1" ]]; }
publish_candidate
printf 'state:'; cat "$STATE_DIR/skills-deploy.state"
printf 'current:'; cat "$STATE_DIR/current"
'''
            result = subprocess.run([BASH, "-c", harness], env=env,
                                    text=True, encoding="utf-8", errors="replace",
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            state = Path(directory, "state", "skills-deploy.state")
            return result.returncode, result.stdout, state.read_text() if state.exists() else ""

    def test_success_checks_builds_stops_syncs_then_activates(self):
        code, output, state = self.run_scenario()
        self.assertEqual(0, code, output)
        self.assertLess(output.index("sync:check"), output.index("app:"))
        self.assertLess(output.index(":build"), output.index(":stop"))
        self.assertLess(output.index(":stop"), output.index("sync:apply"))
        self.assertLess(output.index("sync:apply"), output.index("activate:"))
        self.assertIn("phase=complete", state)

    def test_conflict_or_build_failure_never_stops_old_app(self):
        for failure in ("check", "build"):
            with self.subTest(failure=failure):
                code, output, state = self.run_scenario(failure)
                self.assertNotEqual(0, code, output)
                self.assertNotIn(":stop", output)
                self.assertEqual("", state)

    def test_stop_sync_and_activation_failures_leave_recovery_marker(self):
        for failure in ("stop", "apply", "activate"):
            with self.subTest(failure=failure):
                code, output, state = self.run_scenario(failure)
                self.assertNotEqual(0, code, output)
                self.assertIn("phase=running", state)
                self.assertNotIn("activate:aaaaaaaa", output)

    def test_failed_candidate_is_stopped_and_old_is_not_reactivated(self):
        code, output, state = self.run_scenario("activate")
        self.assertNotEqual(0, code, output)
        self.assertIn("app:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb:stop", output)
        self.assertIn("phase=running", state)

    def test_existing_running_transaction_blocks_new_deployment(self):
        source = SCRIPT.read_text(encoding="utf-8")
        start = source.index("# BEGIN SKILLS DEPLOY TRANSACTION")
        end = source.index("# END SKILLS DEPLOY TRANSACTION", start)
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "skills-deploy.state").write_text("phase=running\n", encoding="utf-8")
            env = dict(os.environ, TEST_ROOT=directory, MSYS_NO_PATHCONV="1")
            harness = r'''set -Eeuo pipefail
if command -v cygpath >/dev/null; then TEST_ROOT=$(cygpath -u "$TEST_ROOT"); fi
STATE_DIR="$TEST_ROOT"
fail() { printf 'failed:%s\n' "$*"; exit 1; }
''' + source[start:end] + '\nassert_no_interrupted_deploy\n'
            result = subprocess.run([BASH, "-c", harness], env=env, text=True,
                                    encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT)
            self.assertNotEqual(0, result.returncode, result.stdout)
            self.assertIn("failed:", result.stdout)

    def test_first_deploy_does_not_attempt_to_stop_previous_release(self):
        code, output, state = self.run_scenario(previous=False)
        self.assertEqual(0, code, output)
        self.assertNotIn(":stop", output)
        self.assertIn("phase=complete", state)


if __name__ == "__main__":
    unittest.main()
