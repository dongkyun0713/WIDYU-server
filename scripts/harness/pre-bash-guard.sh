#!/bin/bash
# PreToolUse(Bash) 가드 — 되돌릴 수 없는 명령과 시크릿 읽기를 실행 전에 차단한다.
#
# settings.json 의 deny 와 겹치는 항목이 있다. 의도된 이중 방어다.
# deny 는 Read 도구와 명령 문자열만 보고, 이 훅은 파이프·세미콜론으로 이어진
# 명령까지 본다. ADR-0018 에서 실측했듯 어느 쪽도 보안 경계가 아니라 사고 방지 장치다.
#
# fail-closed: stdin JSON 을 파싱하지 못하면 통과가 아니라 차단한다.
# 무엇을 실행하려는지 모르는 상태에서 통과시키면 가드가 있으나 마나다.
# (브랜치 가드 pre-edit-branch-guard.sh 는 반대로 fail-open 이다. 그쪽은 막으려는
#  사고가 git switch 로 되돌려지는 반면, fail-closed 면 편집이 전면 중단된다. ADR-0021)
#
# Silent Success: 통과는 exit 0 무출력, 차단은 stderr + exit 2.
#
# 판정 구조 (PR #535 리뷰를 거치며 도달한 형태):
#   1) 따옴표 상태를 추적하며 한 번 훑어 heredoc 본문을 지우고 명령 단위로 끊는다.
#      정규식으로도, 줄 단위 파싱으로도 안 된다. 셸 따옴표는 줄을 넘기 때문이다.
#      줄 단위로 lex 하던 버전은 여러 줄에 걸친 인용 문자열에서 파싱이 깨졌고,
#      폴백 정규식이 그 안의 예시 문자열을 명령으로 오인해 차단했다.
#   2) 끊어낸 각 단위를 shlex 로 토큰화해 argv 를 만든다.
#   3) argv 의 명령 이름과 옵션 집합을 보고 판정한다. 문자열 매칭이 아니므로
#      rm -r -f, rm --recursive --force, 플래그 역순이 모두 걸린다.
#
# 오탐보다 미탐을 택한다. 이 훅은 모든 Bash 호출을 거치므로 오탐이 곧 작업 중단이다.
# 판정할 수 없는 조각은 막지 않고 넘긴다. 막으려는 대상은 적대적 우회가 아니라
# 평범한 사고이고, 보안 경계는 ADR-0018 대로 .gitignore 와 키 교체가 담당한다.
#
# push·PR 게이트 (LLD-0093): git push·gh pr create 의 대상 작업 트리가 기준 브랜치 이후
# main 소스 Java 를 바꿨다면 HEAD 에 대한 Codex 커밋 범위 검수 기록(codex-review.sh --base)을
# 요구한다. 기록 없음·BLOCKER 는 차단, FAILED(Codex 실행 실패)는 경고 후 통과(fail-open).
# 대상 트리나 기준 ref 를 정하지 못하면 경고만 하고 통과시킨다(오탐보다 미탐).
# 한계: 대상 트리의 HEAD 만 본다. 다른 브랜치를 refspec 으로 push 하거나
# gh pr create --head 로 다른 브랜치를 지정하면 그 브랜치가 아니라 HEAD 를 확인한다.
#
# 판정부를 인용 heredoc(<<'PYEOF')으로 넘긴다. 셸이 내용을 건드리지 않으므로
# 파이썬 안에서 따옴표를 자유롭게 쓸 수 있다. python3 -c 로 넣던 때는 이스케이프가
# 꼬여 실제로 문법 오류를 냈다.

set -uo pipefail

input="$(cat)"

HARNESS_DIR="$(cd "$(dirname "$0")" && pwd)"

VERDICT="$(python3 - "$input" "$HARNESS_DIR" <<'PYEOF'
import datetime, json, os, re, shlex, subprocess, sys

try:
    data = json.loads(sys.argv[1])
except Exception:
    sys.exit(9)          # fail-closed

cmd = data.get("tool_input", {}).get("command", "")
if not cmd:
    sys.exit(0)

READERS = {"cat", "head", "tail", "less", "more", "nl",
           "base64", "xxd", "od", "strings", "awk", "sed"}
SQUOTE, DQUOTE = "'", '"'


def scan(text):
    """따옴표 상태를 추적하며 한 번 훑어 명령 단위 문자열 목록을 만든다.

    - 따옴표 안의 구분자와 << 는 구분자가 아니다.
    - heredoc 본문은 실행되는 명령이 아니라 데이터이므로 버린다.
      (커밋 메시지에 위험 명령을 설명으로 적었다고 막으면 안 된다)
    """
    out, cur = [], []
    pending = []          # 이 줄에서 열린 heredoc 구분자들
    i, n = 0, len(text)

    def flush():
        s = "".join(cur).strip()
        if s:
            out.append(s)
        del cur[:]

    while i < n:
        c = text[i]

        if c == "\\" and i + 1 < n:
            cur.append(text[i:i + 2]); i += 2; continue

        if c == SQUOTE:                    # 작은따옴표 안은 전부 리터럴
            j = text.find(SQUOTE, i + 1)
            if j == -1:
                cur.append(text[i:]); break
            cur.append(text[i:j + 1]); i = j + 1; continue

        if c == DQUOTE:                    # 큰따옴표는 역슬래시 이스케이프만 존중
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2; continue
                if text[j] == DQUOTE:
                    break
                j += 1
            j = min(j, n - 1)
            cur.append(text[i:j + 1]); i = j + 1; continue

        # heredoc 시작. 구분자를 기억해 두고 줄이 끝나면 본문을 건너뛴다.
        if text.startswith("<<", i) and not text.startswith("<<<", i):
            j = i + 2
            if j < n and text[j] == "-":
                j += 1
            while j < n and text[j] in " \t":
                j += 1
            q = ""
            if j < n and text[j] in (SQUOTE, DQUOTE):
                q = text[j]; j += 1
            k = j
            while k < n and (text[k].isalnum() or text[k] == "_"):
                k += 1
            delim = text[j:k]
            if q and k < n and text[k] == q:
                k += 1
            if delim:
                pending.append(delim)
                i = k
                continue

        if text.startswith("&&", i) or text.startswith("||", i):
            flush(); i += 2; continue

        if c in ";|&":
            flush(); i += 1; continue

        if c == "\n":
            flush(); i += 1
            while pending:                 # 열린 heredoc 본문을 통째로 건너뛴다
                delim = pending.pop(0)
                while i < n:
                    e = text.find("\n", i)
                    line = text[i:] if e == -1 else text[i:e]
                    i = n if e == -1 else e + 1
                    if line.strip() == delim:
                        break
            continue

        cur.append(c); i += 1

    flush()
    return out


def argv_of(segment):
    """명령 단위 문자열에서 (앞쪽 환경변수 대입, argv) 를 뽑는다. 파싱 못 하면 None."""
    try:
        lx = shlex.shlex(segment, posix=True, punctuation_chars=True)
        lx.whitespace_split = True
        toks = list(lx)
    except ValueError:
        return None

    argv, skip, env = [], False, {}
    for t in toks:
        if skip:
            skip = False; continue
        if t and t[0] in "<>" or (t[:-1].isdigit() and t.endswith(">")):
            skip = True; continue          # 리다이렉션 연산자와 그 대상
        argv.append(t)

    i = 0                                  # 앞쪽 환경변수 대입은 명령이 아니다
    while i < len(argv):
        head, _, value = argv[i].partition("=")
        if "=" in argv[i] and head and (head[0].isalpha() or head[0] == "_"):
            env[head] = value
            i += 1
        else:
            break
    return env, argv[i:]


def shorts(args):
    s = set()
    for a in args:
        if a.startswith("-") and not a.startswith("--") and len(a) > 1:
            s.update(a[1:])
    return s


def longs(args):
    return {a[2:].split("=")[0] for a in args if a.startswith("--") and len(a) > 2}


def operands(args):
    return [a for a in args if not a.startswith("-")]


def is_secret(path):
    base = path.rsplit("/", 1)[-1]
    if base == ".env" or base.startswith(".env."):
        return True
    if base.endswith(".pem") or base.endswith(".p8"):
        return True
    if "/firebase/" in path and path.endswith(".json"):
        return True
    return False


def verdict(argv):
    if not argv:
        return None
    name = argv[0].rsplit("/", 1)[-1]
    args = argv[1:]
    sub = args[0] if args else ""

    if name == "git" and sub == "rm":
        name, args = "rm", args[1:]

    if name == "rm":
        sh, lo = shorts(args), longs(args)
        if (sh & set("rR") or "recursive" in lo) and ("f" in sh or "force" in lo):
            return "rm -rf 는 막혀 있습니다. 지울 대상이 확실하면 사용자에게 요청하세요."
        for op in operands(args):
            if "scripts/mysql/" in op or op.endswith(".sql"):
                return "SQL 마이그레이션 파일 삭제는 막혀 있습니다. 필요하면 사용자에게 요청하세요."

    if name == "git" and sub == "push":
        rest = args[1:]
        if "f" in shorts(rest) or "force" in longs(rest):
            return "force push 는 막혀 있습니다."

    if name == "git" and sub == "reset" and "hard" in longs(args[1:]):
        return "git reset --hard 는 되돌릴 수 없습니다. 막혀 있습니다."

    if name == "git" and sub == "clean":
        rest = args[1:]
        if shorts(rest) & set("fd") or longs(rest) & {"force", "d"}:
            return "git clean 은 추적되지 않는 파일을 지웁니다. 막혀 있습니다."

    # 서브커맨드 위치를 고정하면 옵션이 앞에 올 때 밀린다 (docker compose -f a.yml down -v)
    compose_down = (
        (name == "docker" and sub == "compose" and "down" in operands(args))
        or (name == "docker-compose" and "down" in operands(args))
    )
    if compose_down and ("v" in shorts(args) or "volumes" in longs(args)):
        return "compose 볼륨 삭제는 DB 데이터까지 지웁니다. 막혀 있습니다."

    # Read() deny 는 Read 도구에만 걸린다. Bash 경유 읽기를 여기서 막는다.
    # 시크릿 경로가 "이 reader 의 인자"일 때만 막아야 오탐이 없다.
    if name in READERS:
        for op in operands(args):
            if is_secret(op):
                return "시크릿 파일(firebase 키·.env·인증서)을 Bash 로 읽는 것은 막혀 있습니다."

    return None


JAVA_MAIN = re.compile(r"^backend/[^/]+/src/main/java/.+\.java$")
REVIEW_SCRIPT = os.path.join(sys.argv[2], "codex-review.sh")


def git_out(tree, *args):
    try:
        r = subprocess.run(["git", "-C", tree, *args], capture_output=True,
                           text=True, timeout=5)
    except Exception:
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def gate_kind(argv):
    """push·PR 생성이면 (종류, git -C 경로) 를, 아니면 (None, None) 을 돌려준다."""
    name, args, tree = argv[0].rsplit("/", 1)[-1], argv[1:], None
    if name == "git" and len(args) >= 2 and args[0] == "-C":
        tree, args = args[1], args[2:]
    if name == "git" and args[:1] == ["push"]:
        rest = args[1:]
        # 원격 브랜치 삭제와 드라이런은 커밋을 내보내지 않는다.
        if shorts(rest) & set("dn") or longs(rest) & {"delete", "dry-run"}:
            return None, None
        return "git push", tree
    if name == "gh" and args[:2] == ["pr", "create"]:
        return "gh pr create", None
    return None, None


def audit(top, **fields):
    try:
        common = os.path.join(top, git_out(top, "rev-parse", "--git-common-dir"))
        now = datetime.datetime.now(datetime.timezone.utc)
        out = os.path.join(os.path.dirname(os.path.abspath(common)), ".claude", "audit")
        os.makedirs(out, exist_ok=True)
        sid = data.get("session_id", "")
        record = {"ts": now.isoformat(timespec="milliseconds"), "session_id": sid,
                  "sid_prefix": sid[:8], "event": "codex_gate", **fields}
        with open(os.path.join(out, "audit-" + now.strftime("%Y-%m") + ".jsonl"), "a") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception:
        pass


def push_gate(env, argv, cwd):
    kind, ctree = gate_kind(argv)
    if not kind:
        return None
    tree = os.path.join(cwd, os.path.expanduser(os.path.expandvars(ctree))) if ctree else cwd

    def skip(why):
        sys.stderr.write(f"경고: Codex 검수 게이트를 건너뜁니다 — {why}\n")
        return None

    top = git_out(tree, "rev-parse", "--show-toplevel")
    if not top:
        return skip(f"git 작업 트리를 찾지 못했습니다({tree})")
    head = git_out(top, "rev-parse", "HEAD")
    base = None
    for ref in ("upstream/develop", "origin/develop"):
        if git_out(top, "rev-parse", "--verify", "-q", ref + "^{commit}"):
            base = git_out(top, "merge-base", ref, "HEAD")
            break
    if not head or not base:
        return skip("기준 브랜치(upstream/develop·origin/develop)와의 merge-base 를 찾지 못했습니다")
    changed = git_out(top, "diff", "--no-renames", "--name-only", base, "HEAD")
    if changed is None:
        return skip("기준 대비 변경 목록을 읽지 못했습니다")
    if not any(JAVA_MAIN.match(p) and "/generated/" not in p for p in changed.splitlines()):
        return None

    verdict_ = None
    git_dir = git_out(top, "rev-parse", "--absolute-git-dir")
    try:
        with open(os.path.join(git_dir, "widyu-codex-review", head)) as f:
            verdict_ = json.load(f).get("verdict")
    except Exception:
        pass                               # 기록 없음·손상은 같은 취급
    info = {"command": kind, "head": head, "base": base, "tree": top, "verdict": verdict_ or "NONE"}
    review = f"cd {shlex.quote(top)} && bash {shlex.quote(REVIEW_SCRIPT)} --base {base}"

    if verdict_ == "APPROVE":
        audit(top, result="allow", **info)
        return None
    if verdict_ == "FAILED":
        audit(top, result="allow_failed_open", **info)
        sys.stderr.write("경고: 이 HEAD 의 Codex 검수가 실행 실패(FAILED)로 끝났습니다. "
                         f"수동 검수가 필요합니다. 재시도: {review}\n")
        return None
    if verdict_ == "BLOCKER":
        reason = env.get("WIDYU_CODEX_ACCEPT", "").strip()
        if reason:
            audit(top, result="accept", reason=reason[:200], **info)
            return None
        audit(top, result="block", **info)
        return (f"HEAD({head[:12]})의 Codex 검수 판정이 BLOCKER 입니다. BLOCKER 를 고쳐 커밋한 뒤 "
                f"다시 검수하세요: {review}  검토 끝에 수용한다면 명령 앞에 "
                f"WIDYU_CODEX_ACCEPT=\"<사유>\" 를 붙이세요.")
    audit(top, result="block", **info)
    return (f"main 소스 Java 를 바꾼 브랜치를 Codex 검수 없이 {kind} 하려 합니다"
            f"(HEAD {head[:12]}에 검수 기록 없음). 먼저 실행하세요: {review}  "
            "(Codex 는 수 분 걸릴 수 있습니다. 서브에이전트는 백그라운드로 실행하세요)")


cwd = data.get("cwd") or os.getcwd()
for seg in scan(cmd):
    parsed = argv_of(seg)
    if parsed is None:
        continue          # 판정 불가한 조각은 막지 않는다
    env, argv = parsed
    msg = verdict(argv)
    if msg:
        print(msg)
        sys.exit(2)
    if argv and argv[0] == "cd":           # 같은 명령의 앞선 cd 가 대상 작업 트리를 정한다
        cwd = os.path.join(cwd, os.path.expanduser(os.path.expandvars(argv[1] if len(argv) > 1 else "~")))
        continue
    msg = push_gate(env, argv, cwd) if argv else None
    if msg:
        print(msg)
        sys.exit(2)

sys.exit(0)
PYEOF
)"
rc=$?

if [[ $rc -eq 9 || $rc -gt 2 ]]; then
  echo "차단: PreToolUse 가드가 명령을 파싱하지 못했습니다(python3 미설치 또는 입력 손상). 안전을 위해 실행을 막습니다." >&2
  exit 2
fi

if [[ $rc -eq 2 ]]; then
  echo "차단: ${VERDICT}" >&2
  exit 2
fi

exit 0
