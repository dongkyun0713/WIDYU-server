#!/usr/bin/env python3
"""Inspect project hook readiness through the installed Codex app-server (no model call)."""
import argparse
import json
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]


def hook_inventory(root, executable="codex", env=None):
    process = subprocess.Popen([executable, "app-server", "--stdio"], cwd=root,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, env=env)
    messages = queue.Queue()

    def receive():
        for line in process.stdout:
            messages.put(line)
        messages.put(None)

    reader = threading.Thread(target=receive, daemon=True)
    reader.start()

    def request(identifier, method, params):
        process.stdin.write(json.dumps({"id": identifier, "method": method, "params": params}) + "\n")
        process.stdin.flush()
        deadline = time.monotonic() + 20
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Codex app-server 응답 시간 초과")
            try:
                line = messages.get(timeout=remaining)
            except queue.Empty as error:
                raise TimeoutError("Codex app-server 응답 시간 초과") from error
            if line is None:
                raise RuntimeError("Codex app-server가 응답 전에 종료됐습니다")
            message = json.loads(line)
            if message.get("id") == identifier:
                if "error" in message:
                    raise RuntimeError("설치된 Codex가 " + method + " 요청을 처리하지 못했습니다")
                return message["result"]

    try:
        request(1, "initialize", {"clientInfo": {"name": "widyu_harness_doctor", "version": "1.0"},
                                  "capabilities": {"experimentalApi": True}})
        process.stdin.write('{"method":"initialized"}\n')
        process.stdin.flush()
        return request(2, "hooks/list", {"cwds": [str(root)]})
    finally:
        process.stdin.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=1)
        process.stdout.close()


def readiness(result, root):
    common = subprocess.check_output(["git", "-C", str(root), "rev-parse", "--git-common-dir"], text=True).strip()
    # Codex deliberately inherits hook definitions from the primary checkout.
    source_root = (root / common).resolve().parent
    expected = source_root / ".codex/config.toml"
    print(f"[HARNESS] 훅 정의 원본: {expected}")
    found = {}
    errors = False
    for entry in result.get("data", []):
        errors = errors or bool(entry.get("errors"))
        for hook in entry.get("hooks", []):
            if Path(hook.get("sourcePath", "")).resolve() != expected:
                continue
            ready = hook.get("enabled") is True and hook.get("trustStatus") in ("trusted", "managed")
            event = hook.get("eventName")
            if event == "preToolUse":
                try:
                    ready = ready and all(re.search(hook.get("matcher") or ".*", name) for name in ("Bash", "apply_patch"))
                except re.error:
                    ready = False
            found[event] = found.get(event, True) and ready
            print(f"[HARNESS] {event}: enabled={hook.get('enabled')}, trust={hook.get('trustStatus')}")
    if errors or not all(found.get(event, False) for event in ("preToolUse", "stop")):
        print("[HARNESS] NOT READY: 대상 worktree에서 Codex /hooks를 열어 프로젝트 훅의 로드·활성화·신뢰를 확인하세요.")
        if source_root != root:
            print("[HARNESS] 연결된 worktree의 훅 정의는 원본 checkout에서 읽습니다. 원본 checkout에 하네스 변경이 반영됐는지 확인하세요.")
        return 1
    print("[HARNESS] READY: 훅 로드·신뢰 확인. 실제 도구 차단/Stop 발화 검증은 별도로 수행하세요.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        return readiness(hook_inventory(root), root)
    except (OSError, ValueError, KeyError, RuntimeError, TimeoutError, subprocess.CalledProcessError) as error:
        print(f"[HARNESS] NOT READY: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
