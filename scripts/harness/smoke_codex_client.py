#!/usr/bin/env python3
"""Opt-in real CLI smoke test with a loopback Responses fixture; no paid model calls."""
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading

from doctor import hook_inventory

ROOT = Path(__file__).resolve().parents[2]


def main():
    if not shutil.which("codex"):
        raise RuntimeError("Codex CLI가 필요합니다")
    with tempfile.TemporaryDirectory(prefix="widyu-codex-smoke-") as directory:
        root = Path(directory).resolve()
        fixture_home = root / "client-home"
        fixture_home.mkdir()
        (fixture_home / "config.toml").write_text(f'[projects.{json.dumps(str(root))}]\ntrust_level="trusted"\n')
        # Child-only environment; never read/copy credentials or mutate the user's home.
        fixture_env = {key: value for key, value in os.environ.items()
                       if key in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "SHELL", "SYSTEMROOT", "WINDIR", "USERPROFILE")}
        fixture_env["CODEX_HOME"] = str(fixture_home)
        shutil.copytree(ROOT / "scripts/harness", root / "scripts/harness", ignore=shutil.ignore_patterns("__pycache__"))
        (root / ".codex").mkdir()
        # This isolated fixture has no project MCP servers or application credentials.
        config = (ROOT / ".codex/config.toml").read_text().split("[mcp_servers.", 1)[0]
        instrumented_config = config.replace("command = '", "command = 'printf hook >> .codex/smoke-trace; ")
        if instrumented_config == config:
            raise RuntimeError("Codex hook command를 smoke trace용으로 계측하지 못했습니다")
        (root / ".codex/config.toml").write_text(instrumented_config)
        subprocess.run(["git", "init", "-q", "-b", "develop", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=Harness", "-c", "user.email=harness@example.invalid",
                        "commit", "--allow-empty", "-qm", "fixture"], check=True)
        inventory = hook_inventory(root, env=fixture_env)
        hooks = [h for entry in inventory["data"] for h in entry["hooks"]
                 if Path(h["sourcePath"]) == root / ".codex/config.toml"]
        if {h["eventName"] for h in hooks} != {"preToolUse", "stop"}:
            raise RuntimeError("임시 저장소의 프로젝트 훅을 발견하지 못했습니다: " + json.dumps(inventory))
        if any(h["trustStatus"] != "untrusted" for h in hooks):
            raise RuntimeError("새 fixture의 훅 신뢰가 격리되지 않았습니다")
        violation = root / "backend/widyu-api/src/main/java/Violation.java"
        violation.parent.mkdir(parents=True)
        violation.write_text("class Violation { int x = true ? 1 : 2; }\n")
        captured = []

        class Fixture(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                captured.append(body)
                turn = len(captured)
                if turn == 1:
                    item = {"type": "function_call", "id": "fc_smoke", "call_id": "call_smoke",
                            "name": "exec_command", "arguments": json.dumps({"cmd": "printf blocked > forbidden.txt", "login": False})}
                elif turn == 2:
                    item = {"type": "custom_tool_call", "id": "ct_patch", "call_id": "call_patch",
                            "name": "apply_patch", "input": "*** Begin Patch\n*** Add File: forbidden-patch.txt\n+blocked\n*** End Patch"}
                else:
                    item = {"type": "message", "id": "msg_smoke", "role": "assistant", "status": "completed",
                            "content": [{"type": "output_text", "text": "Fixture complete.", "annotations": []}]}
                response = {"id": f"resp_{turn}", "object": "response", "status": "completed", "output": [item],
                            "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}
                events = [{"type": "response.created", "response": {"id": response["id"], "status": "in_progress", "output": []}},
                          {"type": "response.output_item.added", "output_index": 0, "item": item},
                          {"type": "response.output_item.done", "output_index": 0, "item": item},
                          {"type": "response.completed", "response": response}]
                payload = "".join("data: " + json.dumps(event) + "\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        # Trust only the inspected fixture definitions in the disposable client home.
        # No --dangerously-bypass-hook-trust and no writes to the user's Codex config.
        for hook in hooks:
            with (fixture_home / "config.toml").open("a") as config_file:
                config_file.write(f"\n[hooks.state.{json.dumps(hook['key'])}]\ntrusted_hash={json.dumps(hook['currentHash'])}\n")
        try:
            result = subprocess.run([
                "codex", "exec", "--ephemeral", "--json", "-s", "workspace-write", "-C", str(root),
                "-c", 'model_provider="harness_fixture"',
                "-c", 'model="gpt-5.4"',
                "-c", 'model_providers.harness_fixture.name="Local harness fixture"',
                "-c", f'model_providers.harness_fixture.base_url="http://127.0.0.1:{server.server_port}/v1"',
                "-c", 'model_providers.harness_fixture.wire_api="responses"',
                "-c", 'model_providers.harness_fixture.requires_openai_auth=false',
                "-c", 'features.apps=false', "-c", 'features.plugins=false',
                "-c", 'features.enable_request_compression=false',
                "Run the deterministic local hook fixture."],
                capture_output=True, text=True, timeout=50, env=fixture_env)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)
        if result.returncode:
            raise RuntimeError("Codex fixture failed: " + (result.stdout + result.stderr)[-4000:])
        transcript = json.dumps(captured, ensure_ascii=False)
        if "셸 직접 파일 수정은 차단합니다" not in transcript:
            outputs = [item for body in captured for item in body.get("input", []) if item.get("type") == "function_call_output"]
            raise RuntimeError("차단 피드백이 모델 입력에 전달되지 않았습니다: " + json.dumps(outputs, ensure_ascii=False) + result.stdout[-2000:])
        if (root / "forbidden.txt").exists():
            raise RuntimeError("차단 대상 명령이 실행됐습니다")
        if (root / "forbidden-patch.txt").exists() or "작업 브랜치에서 편집하세요" not in transcript:
            outputs = [item for body in captured for item in body.get("input", []) if "_output" in item.get("type", "")]
            raise RuntimeError("실제 patch 보호 브랜치 차단에 실패했습니다: " + json.dumps(outputs, ensure_ascii=False))
        if "정적 검사가 실패했습니다" not in transcript:
            raise RuntimeError("Stop 실패가 후속 모델 입력에 전달되지 않았습니다")
        trace = (root / ".codex/smoke-trace").read_text()
        if trace.count("hook") < 4:
            raise RuntimeError("PreToolUse/Stop 발화 기록이 부족합니다")
        print("PASS: actual Codex hook discovery/trust, Bash/patch blocks, Stop failure/reentry; no model API used.")


if __name__ == "__main__":
    main()
