"""Run with: python3 -m unittest discover -s scripts/harness -p 'test_*.py'."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SESSION_A = "6dc2af4e-1bf0-42e2-a238-000000000001"
SESSION_B = "6dc2af4e-1bf0-42e2-a238-000000000002"


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verify = load("verify", "verify.py")
hooks = load("hooks", "codex-hooks.py")


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo with spaces"
        self.root.mkdir()
        target = self.root / "scripts/harness"
        target.mkdir(parents=True)
        for name in ("verify.py", "verify.sh", "validate-java-rules.sh",
                     "pre-bash-guard.sh", "codex-hooks.py", "run-module-tests.sh"):
            shutil.copy(HERE / name, target / name)
        self.git("init", "-q", "-b", "feature/549")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Harness test")
        self.write(".gitignore", "commands.log\n__pycache__/\n.codex/state/\n")
        self.write("gradlew", '#!/bin/sh\nprintf "%s\\n" "$*" >> commands.log\nexit 0\n')
        (self.root / "gradlew").chmod(0o755)
        self.commit()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE)

    def write(self, path, text="data\n"):
        dest = self.root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)
        return dest

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def check(self, *args):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return verify.main(list(args), self.root)

    def test_worktree_staged_untracked_and_unusual_paths(self):
        self.write("tracked.txt")
        self.commit()
        self.write("tracked.txt", "changed\n")
        self.write("staged file.txt")
        self.git("add", "staged file.txt")
        self.write("new\n한글.java")
        _, paths = verify.changed_paths(self.root)
        self.assertEqual(paths, ["new\n한글.java", "staged file.txt", "tracked.txt"])

    def test_rename_and_deletion_preserve_affected_modules(self):
        old = "backend/widyu-domain/src/main/java/Old.java"
        self.write(old)
        self.write("backend/widyu-api/src/main/resources/removed.yml")
        self.commit()
        self.git("mv", old, "moved.java")
        self.git("rm", "backend/widyu-api/src/main/resources/removed.yml")
        _, paths = verify.changed_paths(self.root)
        self.assertIn(old, paths)
        self.assertIn("moved.java", paths)
        self.assertEqual(verify.affected(paths)[0], ["domain", "api"])

    def test_committed_change_uses_explicit_base(self):
        base = self.git("rev-parse", "HEAD").decode().strip()
        self.write("backend/widyu-api/src/main/java/New.java", "class New { int x = true ? 1 : 2; }\n")
        self.commit()
        self.assertEqual(verify.changed_paths(self.root)[1], [])
        self.assertNotEqual(self.check("--base", base, "--static-only"), 0)

    def test_untracked_rule_violation_blocks(self):
        self.write("backend/widyu-api/src/main/java/New.java", "class New { int x = true ? 1 : 2; }\n")
        self.assertNotEqual(self.check("--static-only"), 0)

    def test_legacy_violation_does_not_block_unrelated_addition(self):
        path = "backend/widyu-api/src/main/java/Old.java"
        self.write(path, "class Old { int x = true ? 1 : 2; }\n")
        self.commit()
        self.write(path, "class Old { int x = true ? 1 : 2; }\n// documentation\n")
        self.assertEqual(self.check("--static-only"), 0)

    def test_invalid_ref_fails(self):
        self.assertNotEqual(self.check("--base", "does-not-exist"), 0)

    def test_domain_runs_compile_and_both_tests(self):
        self.write("backend/widyu-domain/src/main/resources/example.yml")
        self.assertEqual(self.check(), 0)
        commands = (self.root / "commands.log").read_text().splitlines()
        self.assertEqual(len(commands), 2)
        self.assertIn("compileJava", commands[0])
        self.assertIn(":backend:widyu-domain:test", commands[1])
        self.assertIn(":backend:widyu-api:test", commands[1])

    def test_api_test_only_and_failure_exit_propagation(self):
        self.write("gradlew", '#!/bin/sh\nprintf "%s\\n" "$*" >> commands.log\nexit 7\n')
        self.assertEqual(self.check("--tests-only", "--module", "api"), 7)
        self.assertEqual((self.root / "commands.log").read_text(), ":backend:widyu-api:test --console=plain\n")

    def test_docs_skip_application_commands(self):
        self.write("admin/AGENTS.md")
        self.write("backend/widyu-api/README.md")
        self.assertEqual(self.check(), 0)
        self.assertFalse((self.root / "commands.log").exists())

    def test_shared_build_and_admin_selection(self):
        self.assertEqual(verify.affected(["gradle/libs.versions.toml"]), (["domain", "api"], False))
        self.assertEqual(verify.affected(["admin/src/App.tsx"]), ([], True))
        self.assertEqual(verify.affected(["backend/widyu-api/src/test/java/Test.java"]), (["api"], False))

    def test_compatibility_wrapper_detects_new_domain_file(self):
        self.write("backend/widyu-domain/src/test/java/NewTest.java")
        result = subprocess.run(["bash", str(self.root / "scripts/harness/run-module-tests.sh")],
                                cwd=self.root / "backend", capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn(":backend:widyu-api:test", (self.root / "commands.log").read_text())

    def pre(self, tool="apply_patch", command="*** Begin Patch\n*** End Patch",
            session=SESSION_A):
        return hooks.handle({"hook_event_name": "PreToolUse", "tool_name": tool,
                             "tool_input": {"command": command},
                             "session_id": session}, self.root)

    def test_feature_branch_requires_session_ack(self):
        result = self.pre()
        self.assertEqual(result["decision"], "block")
        self.assertIn("새 작업", result["reason"])
        self.assertIn("기존 작업", result["reason"])
        ack = hooks.ack_path(self.root, SESSION_A, "feature/549")
        ack.parent.mkdir(parents=True)
        ack.write_text("continue\n")
        self.assertEqual(self.pre(), {})

    def test_ack_is_not_shared_by_session_or_similar_branch(self):
        ack = hooks.ack_path(self.root, SESSION_A, "feature/549")
        ack.parent.mkdir(parents=True)
        ack.write_text("continue\n")
        self.assertEqual(self.pre(), {})
        self.assertEqual(self.pre(session=SESSION_B)["decision"], "block")
        self.git("switch", "-qc", "feature-549")
        self.assertNotEqual(ack, hooks.ack_path(self.root, SESSION_A, "feature-549"))
        self.assertEqual(self.pre()["decision"], "block")

    def test_protected_and_detached_are_blocked_even_with_ack(self):
        for branch in ("develop", "main", "master"):
            self.git("switch", "-qc", branch)
            ack = hooks.ack_path(self.root, SESSION_A, branch)
            ack.parent.mkdir(parents=True, exist_ok=True)
            ack.write_text("continue\n")
            self.assertEqual(self.pre()["decision"], "block")
        self.git("checkout", "--detach", "-q")
        self.assertEqual(self.pre()["decision"], "block")

    def test_missing_session_id_blocks_feature_edit(self):
        payload = {"hook_event_name": "PreToolUse", "tool_name": "apply_patch",
                   "tool_input": {"command": "*** Begin Patch\n*** End Patch"}}
        self.assertEqual(hooks.handle(payload, self.root)["decision"], "block")

    def test_bash_guard_reuses_existing_policy(self):
        self.assertEqual(self.pre("Bash", "git status --short"), {})
        self.assertEqual(self.pre("Bash", "git reset --hard")["decision"], "block")

    def test_malformed_input_blocks(self):
        self.assertEqual(hooks.handle([], self.root)["decision"], "block")
        self.assertEqual(hooks.handle({"hook_event_name": "PreToolUse", "tool_name": "Bash"}, self.root)["decision"], "block")
        result = subprocess.run(["python3", str(self.root / "scripts/harness/codex-hooks.py")],
                                input="{invalid", text=True, capture_output=True)
        self.assertEqual(json.loads(result.stdout)["decision"], "block")

    def test_stop_success_failure_and_reentry(self):
        payload = {"hook_event_name": "Stop", "stop_hook_active": False}
        self.assertEqual(hooks.handle(payload, self.root), {})
        self.write("backend/widyu-api/src/main/java/New.java", "class New { int x = true ? 1 : 2; }\n")
        self.assertEqual(hooks.handle(payload, self.root)["decision"], "block")
        payload["stop_hook_active"] = True
        result = hooks.handle(payload, self.root)
        self.assertNotIn("decision", result)
        self.assertIn("미해결", result["systemMessage"])
        self.assertFalse((self.root / "commands.log").exists())


if __name__ == "__main__":
    unittest.main()
