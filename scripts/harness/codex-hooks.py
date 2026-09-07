#!/usr/bin/env python3
"""Codex protocol adapter; never launches an LLM."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("shell_edit_policy", Path(__file__).with_name("shell_edit_policy.py"))
shell_policy = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shell_policy)


def block(reason):
    return {"decision": "block", "reason": reason}


def ack_path(root, session_id, branch):
    key = hashlib.sha256(f"{session_id}\0{branch}".encode()).hexdigest()[:16]
    slug = re.sub(r"[^A-Za-z0-9._-]", "-", branch)
    return root / ".codex/state" / f"branch-ack-{slug}-{key}.txt"


def check_patch_paths(command, cwd, root):
    """Check every patch destination before consulting a branch acknowledgement."""
    lines = command.strip().splitlines()
    if not lines or lines[0] != "*** Begin Patch" or lines[-1] != "*** End Patch":
        return block("patch 형식을 확인할 수 없습니다. apply_patch 형식으로 수정하세요.")
    for line in lines:
        for prefix in ("*** Add File: ", "*** Update File: ", "*** Delete File: ", "*** Move to: "):
            if not line.startswith(prefix):
                continue
            value = line[len(prefix):]
            if not value:
                return block("patch 대상 경로가 비어 있습니다.")
            target = (cwd / value).resolve()
            if not target.is_relative_to(root) or target == root:
                return block("현재 worktree 밖의 patch는 차단합니다. 대상 worktree에서 세션을 시작하세요: " + value)
            # Use the nearest existing parent: new files/directories have no Git metadata yet.
            parent = target.parent
            while not parent.exists():
                parent = parent.parent
            result = subprocess.run(["git", "-C", str(parent), "rev-parse", "--show-toplevel"],
                                    capture_output=True, text=True)
            if result.returncode or Path(result.stdout.strip()).resolve() != root:
                return block("다른 저장소/worktree의 patch 대상입니다: " + value)
            if ".git" in target.relative_to(root).parts:
                return block("Git 메타데이터는 patch로 수정할 수 없습니다.")
    return {}


def check_edit_branch(payload, root):
    result = subprocess.run(["git", "branch", "--show-current"],
                            cwd=root, capture_output=True, text=True)
    branch = result.stdout.strip()
    if result.returncode or not branch or branch in ("main", "master", "develop"):
        return block("작업 브랜치에서 편집하세요. issue 스킬로 이슈·브랜치를 준비합니다. "
                     "현재: " + (branch or "detached/확인 불가"))

    session_id = payload.get("session_id")
    if not isinstance(session_id, str) or not session_id:
        return block("세션 식별자를 확인할 수 없어 작업 브랜치 편집을 차단했습니다.")

    ack = ack_path(root, session_id, branch)
    if ack.is_file():
        return {}

    state_dir = shlex.quote(str(ack.parent))
    ack_file = shlex.quote(str(ack))
    ack_value = shlex.quote(f"continue {branch}")
    reason = f"""이 세션은 아직 '{branch}' 브랜치에서 작업하기로 확인하지 않았습니다.

지금 요청이 둘 중 무엇인지 확인하세요.

1) 새 작업이다
   issue 스킬로 새 이슈와 feature/{{이슈번호}} 브랜치/worktree를 만든 뒤 수정합니다.

2) '{branch}'가 맡은 기존 작업을 이어가는 것이 현재 사용자 지시로 확인된다
   아래 명령으로 이 세션과 브랜치에만 유효한 확인 파일을 만든 뒤 계속합니다.
   mkdir -p {state_dir} && printf '%s\\n' {ack_value} > {ack_file}

현재 요청과 브랜치의 작업이 같은지 불명확하면 사용자에게 확인하세요. 새 기능을 기존
작업 브랜치에 추가하기 위해 확인 파일을 만들면 안 됩니다."""
    return block(reason)


def handle(payload, root=ROOT):
    if not isinstance(payload, dict):
        return block("하네스 입력은 JSON 객체여야 합니다.")
    event = payload.get("hook_event_name")
    if event == "PreToolUse":
        root = root.resolve()
        cwd_value = payload.get("cwd", str(root))
        if not isinstance(cwd_value, str) or not Path(cwd_value).is_absolute():
            return block("세션 cwd는 절대 경로여야 합니다.")
        cwd = Path(cwd_value).resolve()
        if not cwd.is_relative_to(root):
            return block("세션 cwd와 훅의 worktree가 다릅니다. 대상 worktree에서 세션을 시작하세요.")
        tool = payload.get("tool_name")
        if tool not in ("Bash", "apply_patch"):
            return {}
        tool_input = payload.get("tool_input")
        if not isinstance(tool_input, dict) or not isinstance(tool_input.get("command"), str):
            return block("하네스가 tool_input.command를 읽지 못했습니다. 입력 형식을 확인하세요.")
        if tool == "Bash":
            result = subprocess.run(
                ["bash", str(root / "scripts/harness/pre-bash-guard.sh")],
                input=json.dumps(payload), text=True, capture_output=True, cwd=root)
            if result.returncode:
                return block(result.stderr.strip() or "명령 가드 실행 실패")
            branch = subprocess.run(["git", "-C", str(root), "branch", "--show-current"],
                                    capture_output=True, text=True).stdout.strip()
            session_id = payload.get("session_id")
            ack = None
            if isinstance(session_id, str) and session_id and branch not in ("", "main", "master", "develop"):
                ack = ack_path(root, session_id, branch)
            reason = shell_policy.check(tool_input["command"], cwd, root, ack)
            if reason:
                return block(reason)
        else:
            result = check_patch_paths(tool_input["command"], cwd, root)
            if result:
                return result
            return check_edit_branch(payload, root)
        return {}
    if event == "Stop":
        if not isinstance(payload.get("stop_hook_active", False), bool):
            return block("stop_hook_active는 boolean이어야 합니다.")
        result = subprocess.run(["bash", str(root / "scripts/harness/verify.sh"), "--static-only"],
                                cwd=root, capture_output=True, text=True)
        if not result.returncode:
            return {}
        reason = "정적 검사가 실패했습니다. 원인을 수정하고 재검증하세요.\n" + (
            result.stdout + result.stderr)[-12000:]
        if payload.get("stop_hook_active"):
            return {"systemMessage": "미해결 검증 실패: 반복 후속 요청은 생략합니다. "
                    "완료 보고에 실패를 명시하세요.\n" + reason}
        return block(reason)
    return {}


def main():
    try:
        result = handle(json.load(sys.stdin))
    except (ValueError, OSError, RuntimeError) as error:
        result = block(f"하네스 실행 실패: {error}")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
