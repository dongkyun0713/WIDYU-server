#!/usr/bin/env python3
"""pre-bash-guard.sh 회귀 테스트.

실행: python3 scripts/harness/test-pre-bash-guard.py

케이스를 셸이 아니라 이 파일 안에 데이터로 두는 이유:
테스트 명령 문자열에 위험 패턴을 그대로 쓰면 살아 있는 가드가 러너 실행 자체를 막는다.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

GUARD = Path(__file__).with_name("pre-bash-guard.sh")

# (기대 exit, 라벨, 명령)  — 2=차단, 0=통과
CASES = [
    # ── 되돌릴 수 없는 명령 ──────────────────────────────────────────────
    (2, "rm -rf", "rm -rf ./build"),
    (2, "rm -fr 플래그 순열", "rm -fr /tmp/x"),
    (2, "&& 뒤 rm -rf", "cd /tmp && rm -rf ."),
    (2, "; 뒤 rm -Rf", "echo x | xargs; rm -Rf out"),
    (2, "mysql 마이그레이션 삭제", "rm scripts/mysql/add_x.sql"),
    (2, "git rm sql", "git rm scripts/mysql/add_x.sql"),
    (2, "임의 .sql 삭제", "rm migrations/001.sql"),
    (2, "force push", "git push --force origin develop"),
    (2, "push -f (짧은 플래그)", "git push -f"),
    (2, "reset --hard", "git reset --hard HEAD~1"),
    (2, "clean -fd", "git clean -fd"),
    (2, "compose 볼륨 삭제", "docker compose down -v"),
    (2, "compose 볼륨 삭제 (hyphen)", "docker-compose down -v"),

    # ── Bash 경유 시크릿 읽기 ────────────────────────────────────────────
    (2, "firebase 키", "cat backend/widyu-api/src/main/resources/firebase/k.json"),
    (2, "firebase 빌드 복제본", "base64 backend/widyu-api/bin/main/firebase/k.json"),
    (2, ".env cat", "cat .env"),
    (2, ".env tail 우회", "tail -1 .env"),
    (2, ".env sed 우회", "sed -n 1p .env"),
    (2, "pem 인증서", "echo hi && cat ./certs/key.pem"),

    # ── 통과해야 하는 일상 명령 ──────────────────────────────────────────
    (0, "ls", "ls -la scripts/harness"),
    (0, "gradlew build", "./gradlew build"),
    (0, "gradlew test", "./gradlew :backend:widyu-api:test"),
    (0, "git status", "git status --porcelain"),
    (0, "git diff", "git diff --cached --stat"),
    (0, "정상 push -u", "git push -u origin feature/525"),
    (0, "정상 push", "git push origin develop"),
    (0, "git commit", "git commit -m 'fix: x'"),
    (0, "git add", "git add .claude/settings.json"),
    (0, "git switch", "git switch -c feature/526"),
    (0, "reset 무플래그", "git reset HEAD~1"),
    (0, "clean -n (드라이런)", "git clean -n"),
    (0, "compose down (볼륨 유지)", "docker compose down"),
    (0, "compose up", "docker compose up -d"),
    (0, "문서 cat", "cat docs/adr/README.md"),
    (0, "스크립트 cat", "cat scripts/harness/on-stop.sh"),
    (0, "grep", "grep -c run-module-tests .claude/settings.json"),
    (0, "sed 일반 파일", "sed -n 1,50p CLAUDE.md"),
    (0, "awk 일반 파일", "awk '{print}' build.gradle.kts"),
    (0, "tmp 파일 rm", "rm /tmp/scratch.txt"),
    (0, "rm -f 단독", "rm -f /tmp/a.log"),
    (0, "gh pr view", "gh pr view 528 --json title"),
    (0, "find", "find . -name '*.java' | head"),
    (0, "echo 안의 위험 문자열", 'echo "rm -rf 는 막혀 있습니다"'),

    # ── heredoc 회귀 ─────────────────────────────────────────────────────
    # 커밋 메시지·이슈 본문에 위험 명령을 설명으로 적는 건 실행이 아니다.
    # 이걸 막으면 가드가 정상 작업을 방해한다 (실제로 한 번 발생했다).
    (0, "REGRESSION 커밋 메시지에 위험 명령 언급",
     "git commit -F - <<'EOF'\n"
     "fix(harness): 파괴적 명령 게이트 추가\n\n"
     "deny 에 아래가 없었다.\n"
     "docker compose down -v\ngit reset --hard\ngit push --force\nrm -rf\ngit clean -fd\n\n"
     "Refs #525\nEOF"),
    (0, "REGRESSION 이슈 본문 heredoc",
     "cat > /tmp/body.md <<'EOF'\n## 배경\n"
     "git reset --hard 와 docker compose down -v 가 deny 에 없습니다.\nEOF"),
    # 반대로 heredoc 이 끝난 뒤 줄바꿈으로 이어진 진짜 명령은 잡아야 한다.
    (2, "heredoc 종료 후 실제 위험 명령",
     "cat > /tmp/x.md <<'EOF'\n안전한 본문\nEOF\nrm -rf /tmp/x.md"),

    # ── 우회·오탐 회귀 (PR #535 리뷰) ────────────────────────────────────
    # 따옴표 안의 <<EOF 는 heredoc 연산자가 아니다. 연산자로 오인하면
    # 그 뒤의 진짜 명령이 본문으로 취급돼 통째로 숨는다.
    (2, "따옴표 안 <<EOF 로 위험 명령 은닉",
     "printf '%s\\n' '<<EOF'\nrm -rf ./build\nEOF"),
    # 플래그를 나눠 써도 같은 삭제다.
    (2, "rm 플래그 분리 (-r -f)", "rm -r -f ./build"),
    (2, "rm 롱옵션", "rm --recursive --force ./build"),
    # reader 와 secret 이 각각 다른 명령에 있으면 시크릿을 읽는 게 아니다.
    (0, "reader 와 secret 이 다른 명령", "cat README.md && echo .env"),
    # settings.json 이 Read(./.env.*) 를 막으므로 Bash 도 같은 범위여야 한다.
    (2, ".env.local 읽기", "cat .env.local"),
    (2, ".env.production 읽기", "base64 .env.production"),

    # ── 토큰 분석으로 넘어오면서 확인한 우회 시도 ────────────────────────
    (2, "rm 플래그 역순 (-f -r)", "rm -f -r ./build"),
    (2, "rm 섞인 플래그 (-rvf)", "rm -rvf ./build"),
    (2, "환경변수 대입 뒤 rm", "FOO=bar rm -rf x"),
    (2, "절대경로 rm", "/bin/rm -rf x"),
    (2, "공백 여러 개", "git   push   --force"),
    # 서브커맨드 위치를 고정하면 옵션이 앞에 올 때 밀린다.
    (2, "compose 옵션 뒤 down -v", "docker compose -f a.yml down -v"),
    (2, "상대경로 우회한 .env", "cat ./config/../.env"),

    (0, "따옴표 안 문자열은 실행이 아님", 'echo "cat .env"'),
    (0, "force-with-lease 는 허용", "git push --force-with-lease"),
    (0, "-r 만 (force 없음)", "rm -r ./build"),
    (0, ".envrc 는 시크릿 아님", "cat .envrc"),

    # ── 여러 줄에 걸친 인용 문자열 (줄 단위 파싱이 깨지던 자리) ──────────
    # 셸 따옴표는 줄을 넘는다. 줄 단위로 lex 하면 여는 줄과 닫는 줄 양쪽에서
    # "No closing quotation" 이 나고, 폴백 정규식이 본문 속 예시를 명령으로
    # 오인해 차단했다. 실제로 PR 답글을 쓰다가 막혔다.
    (0, "REGRESSION 여러 줄 인용 문자열 안의 위험 명령 예시",
     "gh api -X POST repos/o/r/pulls/1/comments -f body='옵션을 토큰으로 봅니다.\n\n"
     "플래그 역순 `rm -f -r`, 섞인 플래그 `rm -rvf` 도 막힙니다.\n\n"
     "`git reset --hard` 와 `docker compose down -v` 도 마찬가지입니다.'"),
    (0, "REGRESSION 함수 정의 + 여러 줄 인자",
     "reply() {\n  gh api -X POST \"$1\" -f body=\"$2\"\n}\n"
     "reply 123 '아래를 막습니다.\nrm -rf ./build\n'"),
    # 반대로 따옴표 밖에 진짜로 있으면 잡아야 한다.
    (2, "여러 줄 인용 문자열 뒤의 진짜 명령",
     "gh api -f body='설명입니다.\n여러 줄입니다.'\nrm -rf ./build"),
]


# push·PR 게이트(LLD-0093)는 cwd 의 git 브랜치를 본다. 명령 판정 케이스가 실행 위치의
# 브랜치 상태에 흔들리지 않게 git 저장소가 아닌 임시 디렉터리를 cwd 로 준다.
# 게이트 자체는 test_codex_review_gate.py 가 임시 저장소로 검증한다.
NEUTRAL_CWD = tempfile.mkdtemp(prefix="guard-cwd-")


def run(cmd):
    payload = json.dumps({"session_id": "t", "cwd": NEUTRAL_CWD,
                          "tool_input": {"command": cmd}})
    return subprocess.run(["bash", str(GUARD)], input=payload,
                          capture_output=True, text=True)


def main():
    failed = []
    for expect, label, cmd in CASES:
        got = run(cmd).returncode
        if got != expect:
            failed.append((label, expect, got, cmd))
            print(f"!!FAIL  exp={expect} got={got}  {label}")

    # fail-closed: 무엇을 실행하려는지 모르면 통과가 아니라 차단이어야 한다.
    for label, payload in [("깨진 JSON", '{"tool_input": {broken'), ("빈 입력", "")]:
        p = subprocess.run(["bash", str(GUARD)], input=payload,
                           capture_output=True, text=True)
        if p.returncode != 2:
            failed.append((f"fail-closed {label}", 2, p.returncode, payload))
            print(f"!!FAIL  exp=2 got={p.returncode}  fail-closed {label}")

    total = len(CASES) + 2
    print(f"\n총 {total}건 · 통과 {total - len(failed)} · 실패 {len(failed)}")
    for label, expect, got, cmd in failed:
        print(f"  - {label}: exp={expect} got={got}  {cmd[:80]!r}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
