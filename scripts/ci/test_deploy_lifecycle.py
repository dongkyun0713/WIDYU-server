"""DEV workflow의 실제 SSH 본문을 Docker/curl 대역으로 실행한다. 실제 배포·발송 없음."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
FORWARD = ROOT / "scripts/ci/forward-deploy-events.sh"
WORKFLOW = ROOT / ".github/workflows/deploy-dev.yml"

FAKE_TOOL = '''
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
path = Path(os.environ["FAKE_STATE"])
state = json.loads(path.read_text())
state["commands"].append([name] + args)
code = 0
if name == "docker":
    if args[0] == "inspect":
        if not state["exists"]:
            code = 1
        elif "-f" in args:
            if args[2] == "{{.State.Running}}":
                print(str(state["running"]).lower())
            else:
                print("synthetic/app:previous")
    elif "stop" in args:
        if os.environ.get("FAKE_STOP_FAIL"):
            code = 1
        elif not os.environ.get("FAKE_STOP_UNCONFIRMED"):
            state["running"] = False
    elif "up" in args and args[-1] == "widyu-api":
        state["attempts"] += 1
        fail = os.environ.get("FAKE_START_FAIL")
        if fail == "all" or (fail == "new" and state["attempts"] == 1):
            code = 1
        else:
            state["exists"] = True
            state["running"] = True
elif name == "curl":
    if os.environ.get("FAKE_HTTP_FAIL"):
        code = 22
elif name == "sed":
    env = Path(".env")
    env.write_text("\\n".join("IMAGE_TAG=previous" if line.startswith("IMAGE_TAG=") else line
                             for line in env.read_text().splitlines()) + "\\n")
path.write_text(json.dumps(state))
sys.exit(code)
'''


def remote_body():
    workflow = WORKFLOW.read_text()
    return textwrap.dedent(workflow.split("<<'REMOTE_SCRIPT' |", 1)[1].split("\n", 1)[1]
                           .split("          REMOTE_SCRIPT", 1)[0])


class DeployLifecycleTest(unittest.TestCase):
    def run_deployment(self, exists=True, running=True, **options):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "bin"
            binary.mkdir()
            for name in ("docker", "curl", "sleep", "sed"):
                executable = binary / name
                executable.write_text(f"#!{sys.executable}\n" + FAKE_TOOL)
                executable.chmod(0o700)
            state = root / "state.json"
            state.write_text(json.dumps({"exists": exists, "running": running, "attempts": 0, "commands": []}))
            source_env = root / "source.env"
            source_env.write_text("SYNTHETIC=true\n")
            script = remote_body().replace("${{ env.ORACLE_DEPLOY_PATH }}", str(root))
            script = script.replace("${{ env.ORACLE_ENV_FILE_PATH }}", str(source_env))
            script = script.replace("${{ env.DOCKER_IMAGE_NAME }}", "synthetic/app")
            script = script.replace("${{ github.sha }}", "new")
            script = script.replace("${{ env.ORACLE_CERTBOT_EMAIL }}", "test@example.invalid")
            result = subprocess.run(["bash", "-se"], input=script, text=True, capture_output=True,
                                    env={"PATH": f"{binary}:{os.environ['PATH']}", "FAKE_STATE": str(state), **options},
                                    timeout=10)
            commands = json.loads(state.read_text())["commands"]
            events = [line for line in result.stdout.splitlines() if line.startswith("WIDYU_DEPLOY_EVENT=")]
            return result, events, commands

    def test_running_app_stop_then_verified_restart(self):
        result, events, commands = self.run_deployment()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_stopped", "WIDYU_DEPLOY_EVENT=app_restarted"])
        stop = next(i for i, cmd in enumerate(commands) if "stop" in cmd)
        start = next(i for i, cmd in enumerate(commands) if "up" in cmd and cmd[-1] == "widyu-api")
        health = next(i for i, cmd in enumerate(commands) if cmd[0] == "curl")
        self.assertLess(stop, start)
        self.assertLess(start, health)

    def test_first_deploy_does_not_claim_existing_app_was_stopped(self):
        result, events, _ = self.run_deployment(exists=False, running=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_restarted"])

    def test_already_stopped_app_does_not_claim_a_new_stop(self):
        result, events, _ = self.run_deployment(running=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_restarted"])

    def test_unconfirmed_stop_does_not_announce_stop_or_continue(self):
        result, events, commands = self.run_deployment(FAKE_STOP_UNCONFIRMED="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, [])
        self.assertFalse(any("up" in cmd and cmd[-1] == "widyu-api" for cmd in commands))

    def test_health_failure_never_announces_restart(self):
        result, events, _ = self.run_deployment(FAKE_HTTP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_stopped"])

    def test_rollback_is_reported_but_deployment_still_fails(self):
        result, events, commands = self.run_deployment(FAKE_START_FAIL="new")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_stopped", "WIDYU_DEPLOY_EVENT=app_rollback"])
        self.assertIn(["docker", "pull", "synthetic/app:previous"], commands)

    def test_failed_rollback_does_not_announce_recovery(self):
        result, events, _ = self.run_deployment(FAKE_START_FAIL="all")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_stopped"])

    def test_rollback_without_healthy_http_does_not_announce_recovery(self):
        result, events, _ = self.run_deployment(FAKE_START_FAIL="new", FAKE_HTTP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events, ["WIDYU_DEPLOY_EVENT=app_stopped"])


class ForwardEventsTest(unittest.TestCase):
    def test_only_exact_phase_markers_generate_messages(self):
        result = subprocess.run(["bash", str(FORWARD)], input="ordinary log\n"
                                "WIDYU_DEPLOY_EVENT=app_stopped\nWIDYU_DEPLOY_EVENT=unknown\n"
                                "WIDYU_DEPLOY_EVENT=app_restarted\n", text=True, capture_output=True,
                                env={"PATH": os.environ["PATH"], "DRY_RUN": "1", "COMMIT_MESSAGE": "synthetic"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count('"embeds"'), 2)
        self.assertIn("서버 배포로 인한 앱 중단", result.stdout)
        self.assertIn("앱 재가동 완료", result.stdout)
        self.assertIn("ordinary log", result.stdout)

    def test_pipeline_preserves_ssh_failure_when_notification_has_no_webhook(self):
        command = 'printf "WIDYU_DEPLOY_EVENT=app_stopped\\n"; exit 42'
        result = subprocess.run(["bash", "-o", "pipefail", "-c", 'bash -c "$1" | bash "$2"',
                                 "test", command, str(FORWARD)], text=True, capture_output=True,
                                env={"PATH": os.environ["PATH"], "COMMIT_MESSAGE": "synthetic"})
        self.assertEqual(result.returncode, 42)
        self.assertIn("skipping deploy notification", result.stdout)

    def test_webhook_failure_does_not_fail_the_event_forwarder(self):
        with tempfile.TemporaryDirectory() as directory:
            curl = Path(directory) / "curl"
            curl.write_text("#!/bin/sh\nexit 22\n")
            curl.chmod(0o700)
            result = subprocess.run(["bash", str(FORWARD)], input="WIDYU_DEPLOY_EVENT=app_stopped\n",
                                    text=True, capture_output=True,
                                    env={"PATH": f"{directory}:{os.environ['PATH']}", "COMMIT_MESSAGE": "synthetic",
                                         "DISCORD_DEPLOY_WEBHOOK_URL": "https://example.invalid/synthetic-webhook"})
        self.assertEqual(result.returncode, 0)
        self.assertIn("Deploy notification failed", result.stdout)
        self.assertNotIn("synthetic-webhook", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
