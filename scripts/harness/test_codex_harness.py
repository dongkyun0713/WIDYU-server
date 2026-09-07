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
from unittest import mock

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
doctor = load("doctor", "doctor.py")


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "repo with spaces"
        self.root.mkdir()
        target = self.root / "scripts/harness"
        target.mkdir(parents=True)
        for name in ("verify.py", "verify.sh", "validate-java-rules.sh",
                     "pre-bash-guard.sh", "codex-hooks.py", "shell_edit_policy.py", "run-module-tests.sh"):
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
        self.write("admin/README.md")
        self.write("backend/widyu-api/README.md")
        self.assertEqual(self.check(), 0)
        self.assertFalse((self.root / "commands.log").exists())

    def test_harness_changes_run_regressions_and_propagate_failure(self):
        self.write("scripts/harness/new_policy.py")
        with mock.patch.object(verify, "test_harness") as regression:
            self.assertEqual(self.check(), 0)
            regression.assert_called_once_with(self.root)
        with mock.patch.object(verify, "test_harness", side_effect=subprocess.CalledProcessError(4, "regression")):
            self.assertEqual(self.check(), 4)

    def test_harness_change_selection_and_nonrecursive_static_check(self):
        for path in ("scripts/harness/removed.py", ".agents/skills/write/SKILL.md",
                     "backend/AGENTS.md", ".codex/config.toml", ".github/workflows/ci.yml"):
            self.assertTrue(verify.harness_changed([path]), path)
        self.write("scripts/harness/new_policy.py")
        with mock.patch.object(verify, "test_harness") as regression:
            self.assertEqual(self.check("--static-only"), 0)
            self.assertEqual(self.check("--tests-only"), 0)
            regression.assert_not_called()

    def test_harness_runner_executes_all_suites(self):
        with mock.patch.object(verify, "run") as run:
            verify.test_harness(self.root)
        commands = [c.args[1] for c in run.call_args_list]
        self.assertIn("unittest", commands[0])
        self.assertEqual(commands[1][-1], "scripts/harness/test-pre-bash-guard.py")
        self.assertEqual(commands[2][-1], "scripts/harness/test-pre-edit-branch-guard.py")

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

    def acknowledge(self):
        ack = hooks.ack_path(self.root, SESSION_A, "feature/549")
        ack.parent.mkdir(parents=True, exist_ok=True)
        ack.write_text("continue\n")
        return ack

    def test_patch_rejects_cross_worktree_and_move_escape(self):
        other = Path(self.tmp.name) / "other"
        self.git("worktree", "add", "-qb", "develop", str(other))
        self.acknowledge()
        for operation in ("Add File", "Update File", "Delete File", "Move to"):
            patch = f"*** Begin Patch\n*** {operation}: {other}/file.txt\n*** End Patch"
            self.assertEqual(self.pre(command=patch)["decision"], "block", operation)

    def test_patch_rejects_symlink_and_nested_repository(self):
        outside = Path(self.tmp.name) / "outside"
        outside.mkdir()
        (self.root / "link").symlink_to(outside, target_is_directory=True)
        nested = self.root / "nested"
        nested.mkdir()
        subprocess.run(["git", "init", "-q", str(nested)], check=True)
        self.acknowledge()
        for path in ("link/new.txt", "nested/new.txt", "../outside/new.txt", ".git/config"):
            patch = f"*** Begin Patch\n*** Add File: {path}\n+text\n*** End Patch"
            self.assertEqual(self.pre(command=patch)["decision"], "block", path)

    def test_patch_allows_subdirectory_and_new_paths(self):
        (self.root / "backend").mkdir()
        self.acknowledge()
        payload = {"hook_event_name": "PreToolUse", "tool_name": "apply_patch",
                   "cwd": str(self.root / "backend"), "session_id": SESSION_A,
                   "tool_input": {"command": "*** Begin Patch\n*** Add File: new dir/file.txt\n+text\n*** End Patch"}}
        self.assertEqual(hooks.handle(payload, self.root), {})

    def test_bash_source_writes_are_blocked_even_after_ack(self):
        self.acknowledge()
        for command in ("printf x > source.txt", "echo x >> source.txt", "cat <<'EOF' > source.txt\nx\nEOF",
                        "sed -i '' s/old/new/ source.txt", "tee source.txt", "cp a b", "mv a b",
                        "git apply patch.diff", "python3 -c 'open(\"source.txt\", \"w\").write(\"x\")'",
                        "python3 - <<'PY'\nfrom pathlib import Path\nPath('source.txt').write_text('x')\nPY"):
            self.assertEqual(self.pre("Bash", command)["decision"], "block", command)

    def test_protected_bash_write_blocked_but_read_and_build_allowed(self):
        self.git("switch", "-qc", "develop")
        self.assertEqual(self.pre("Bash", "printf x > source.txt")["decision"], "block")
        for command in ("git status --short", "rg 'write' scripts", "./gradlew test",
                        "python3 -m unittest discover", "git log -1 2>&1", "echo x > /dev/null"):
            self.assertEqual(self.pre("Bash", command), {}, command)

    def test_bash_ack_and_temporary_output_are_allowed(self):
        import shlex
        ack = hooks.ack_path(self.root, SESSION_A, "feature/549")
        command = f"mkdir -p {shlex.quote(str(ack.parent))} && printf '%s\\n' continue > {shlex.quote(str(ack))}"
        self.assertEqual(self.pre("Bash", command), {})
        self.assertEqual(self.pre("Bash", f"printf text > {shlex.quote(str(Path(self.tmp.name) / 'body.md'))}"), {})
        self.assertEqual(self.pre("Bash", "printf text > /tmp/widyu-harness-test-body.md"), {})
        self.assertEqual(self.pre("Bash", "printf x > .codex/config.toml")["decision"], "block")

    def test_bash_changed_directory_and_quoted_message(self):
        import shlex
        (self.root / "backend").mkdir()
        command = f"cd {shlex.quote(str(self.root / 'backend'))} && printf x > source.txt"
        self.assertEqual(self.pre("Bash", command)["decision"], "block")
        self.assertEqual(self.pre("Bash", "git commit -m '설명\ntee source.txt\npython3 -c example'"), {})

    def test_bash_quoted_operators_and_heredoc_examples(self):
        for command in ("rg '>' scripts", "git commit -m 'example <<EOF'", "cat <<'EOF'\ntee source.txt\nEOF\ngit status",
                        "cat <<< 'text'", "git status # example <<EOF\ngit log -1"):
            self.assertEqual(self.pre("Bash", command), {}, command)
        for command in ("echo '<<EOF'\nprintf x > source.txt", "cat <<'EOF'\ndata\nEOF\nprintf x > source.txt",
                        "bash -lc 'printf x > source.txt'", "python3 -c'print(1)'", "sed -i.bak s/a/b/ source.txt"):
            self.assertEqual(self.pre("Bash", command)["decision"], "block", command)

    def test_bash_temp_symlink_does_not_allow_repository_write(self):
        import shlex
        link = Path(self.tmp.name) / "output"
        link.symlink_to(self.root / "source.txt")
        self.assertEqual(self.pre("Bash", f"printf x > {shlex.quote(str(link))}")["decision"], "block")

    def test_doctor_reports_missing_untrusted_disabled_and_ready(self):
        hooks_list = [{"sourcePath": str(self.root / ".codex/config.toml"), "eventName": event,
                       "enabled": True, "trustStatus": "trusted", "matcher": "^(Bash|apply_patch)$"}
                      for event in ("preToolUse", "stop")]
        def diagnose(items):
            with contextlib.redirect_stdout(io.StringIO()):
                return doctor.readiness({"data": [{"hooks": items, "errors": []}]}, self.root)
        self.assertEqual(diagnose([]), 1)
        self.assertEqual(diagnose(hooks_list), 0)
        hooks_list[0]["trustStatus"] = "untrusted"
        self.assertEqual(diagnose(hooks_list), 1)
        hooks_list[0]["trustStatus"] = "trusted"
        hooks_list[0]["enabled"] = False
        self.assertEqual(diagnose(hooks_list), 1)
        hooks_list[0]["enabled"] = True
        hooks_list[0]["matcher"] = "^apply_patch$"
        self.assertEqual(diagnose(hooks_list), 1)

    def test_doctor_linked_worktree_uses_primary_source(self):
        other = Path(self.tmp.name) / "linked"
        self.git("worktree", "add", "-qb", "feature/550", str(other))
        hooks_list = [{"sourcePath": str(self.root / ".codex/config.toml"), "eventName": event,
                       "enabled": True, "trustStatus": "trusted", "matcher": "^(Bash|apply_patch)$"}
                      for event in ("preToolUse", "stop")]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(doctor.readiness({"data": [{"hooks": hooks_list}]}, other), 0)

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
