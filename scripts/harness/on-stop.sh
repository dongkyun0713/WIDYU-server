#!/bin/bash
# Stop hook: Claude 응답 종료 시 Java 변경을 감지하면 3-Tier 검수 → 피드백 루프.
#
# 흐름:
#   Tier 0)   uncommitted main-source Java 변경 없음 → 정지 허용 (exit 0)
#   Tier 1)   정적 규칙 검사 (validate-java-rules.sh, 무료·<1초)
#              위반 발견 → exit 2 + 위반 리포트 전달 (Codex 호출 없음)
#   Tier 1.5) 컴파일 검사 (gradlew compileJava, warm ~7초)
#              실패 → exit 2 + 컴파일 오류 전달 (Codex 호출 없음)
#   Tier 2)   Codex 필요성 게이트
#              diff<30 LOC & ≤2파일 & 신규 파일 0 & 비민감 경로 → Codex 생략, exit 0
#              직전 검수 이후 diff 무변경 → Codex 생략, exit 0
#   Tier 3)   Codex 시맨틱 리뷰
#              APPROVE  → 정지 허용 (exit 0). SUGGESTIONS는 .claude/suggestions/ 에 저장
#              BLOCKER  → BLOCKERS만 Claude에 전달 (exit 2). SUGGESTIONS는 저장만
#              실행 실패 → 경고 후 정지 허용 (fail-open)
#
# 무한 루프 방지 (.claude/state/codex-round-<sid>.json 에 세션별 저장):
#   static_round        — Tier 1 위반 카운터 (최대 MAX_ROUNDS)
#   compile_round       — Tier 1.5 실패 카운터 (최대 MAX_ROUNDS)
#   round               — Tier 3 BLOCKER 카운터 (최대 MAX_ROUNDS)
#   reviewed_hash       — 검수를 마쳤거나 포기한 diff 지문. 같은 지문은 재검수하지 않는다
#   static_gave_up_hash — Tier 1 을 포기한 diff 지문. 같은 지문은 다시 막지 않는다
#
# 카운터 소진 시 상태 파일을 지우면 다음 정지에서 0부터 다시 세어 루프가 무한해진다.
# 반드시 지문을 남겨 같은 상태의 재진입을 차단할 것.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
STATE_DIR="$ROOT_DIR/.claude/state"
AUDIT_DIR="$ROOT_DIR/.claude/audit"
SUGG_DIR="$ROOT_DIR/.claude/suggestions"
MAX_ROUNDS=3

mkdir -p "$STATE_DIR" "$AUDIT_DIR" "$SUGG_DIR"

# ─── session_id 추출 ──────────────────────────────────────────────────────────

STDIN_JSON=$(cat 2>/dev/null || true)
SESSION_ID=$(python3 -c "
import json, sys
try:
    print(json.loads(sys.argv[1]).get('session_id', ''))
except Exception:
    print('')
" "$STDIN_JSON" 2>/dev/null || true)

if [[ -z "$SESSION_ID" ]]; then
  python3 -c "
import json, datetime
from pathlib import Path
month = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m')
out = Path('$AUDIT_DIR') / ('audit-' + month + '.jsonl')
out.parent.mkdir(parents=True, exist_ok=True)
record = {'ts': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds'),
          'session_id': '', 'event': 'session_id_missing', 'hook': 'Stop'}
with open(out, 'a') as f:
    f.write(json.dumps(record) + '\n')
" 2>/dev/null || true
  exit 0
fi

SID_PREFIX="${SESSION_ID:0:8}"

# ─── 작업 트리 결정 (LLD-0093) ────────────────────────────────────────────────
#
# 스크립트는 원본 체크아웃($CLAUDE_PROJECT_DIR)에서 실행되지만 작업은 다른 worktree 에서
# 일어날 수 있다. 훅 입력 cwd 가 같은 저장소(git-common-dir 이 같은)의 작업 트리면 그곳의
# 미커밋 변경을 본다. 아니면 기존처럼 원본 체크아웃을 본다.
# 상태·감사·제안 파일은 계속 원본 체크아웃의 .claude 아래에 둔다.
WORK_DIR="$ROOT_DIR"
HOOK_CWD=$(python3 -c "
import json, sys
try:
    print(json.loads(sys.argv[1]).get('cwd') or '')
except Exception:
    print('')
" "$STDIN_JSON" 2>/dev/null || true)
if [[ -n "$HOOK_CWD" ]]; then
  CAND=$(git -C "$HOOK_CWD" rev-parse --show-toplevel 2>/dev/null || true)
  CAND_COMMON=$(git -C "${CAND:-/nonexistent}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  ROOT_COMMON=$(git -C "$ROOT_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
  if [[ -n "$CAND_COMMON" && "$CAND_COMMON" == "$ROOT_COMMON" ]]; then
    WORK_DIR="$CAND"
  fi
fi
# validate-java-rules.sh·codex-review.sh 는 호출 위치의 git 작업 트리를 본다.
cd "$WORK_DIR" || exit 0

STATE_FILE="$STATE_DIR/codex-round-${SID_PREFIX}.json"

# ─── 헬퍼: 감사 로그 ─────────────────────────────────────────────────────────
#
# 이벤트 타입 분리 (codex_review 이벤트 수 = 실제 Codex 호출 수 불변식 유지):
#   codex_review   — Tier 3에서 Codex 실제 실행 시
#   static_check   — Tier 1 정적 검사 결과
#   review_skipped — Tier 2 게이트로 Codex 생략 시

# _audit_event event_name [key=value ...]
_audit_event() {
  local event="$1"; shift
  python3 -c "
import json, datetime, sys
from pathlib import Path
root, sid, event = sys.argv[1], sys.argv[2], sys.argv[3]
record = {
    'ts': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds'),
    'session_id': sid, 'sid_prefix': sid[:8], 'event': event,
}
for kv in sys.argv[4:]:
    k, _, v = kv.partition('=')
    record[k] = int(v) if v.isdigit() else v
month = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m')
out = Path(root) / '.claude' / 'audit' / ('audit-' + month + '.jsonl')
out.parent.mkdir(parents=True, exist_ok=True)
with open(out, 'a') as f:
    f.write(json.dumps(record, ensure_ascii=False) + '\n')
" "$ROOT_DIR" "$SESSION_ID" "$event" "$@" 2>/dev/null || true
}

# _audit_codex verdict round  (Tier 3 전용, codex_review 이벤트)
_audit_codex() {
  _audit_event "codex_review" "verdict=$1" "round=$2"
}

# ─── 헬퍼: 상태 파일 (field 단위 읽기·쓰기, 다른 필드 보존) ─────────────────

# _read_state_field field [default=0]
_read_state_field() {
  local field="$1" default="${2:-0}"
  [[ -f "$STATE_FILE" ]] || { echo "$default"; return; }
  python3 -c "
import json, sys
try:
    v = json.load(open(sys.argv[1])).get(sys.argv[2])
    print(v if v is not None else sys.argv[3])
except Exception:
    print(sys.argv[3])
" "$STATE_FILE" "$field" "$default" 2>/dev/null || echo "$default"
}

# _write_state_field field value  (기존 필드 유지하며 지정 필드만 갱신)
_write_state_field() {
  local field="$1" value="$2"
  python3 -c "
import json, sys
path, sid, field, value = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
try:
    state = json.load(open(path))
except Exception:
    state = {}
state['session_id'] = sid
try:
    state[field] = int(value)
except ValueError:
    state[field] = value
json.dump(state, open(path, 'w'))
" "$STATE_FILE" "$SESSION_ID" "$field" "$value" 2>/dev/null || true
}

# ─── Tier 0: Java 변경 감지 ───────────────────────────────────────────────────

# -z 로 NUL 구분 출력을 받는다. 기본 porcelain 은 공백·개행이 든 경로를 따옴표로
# 감싸고 이스케이프해서, 줄 단위 파싱이 그런 경로를 조용히 빠뜨린다.
_changed_java() {
  git -C "$WORK_DIR" status --porcelain -z --untracked-files=all 2>/dev/null \
    | python3 -c "
import sys
data = sys.stdin.buffer.read().split(b'\0')
i = 0
while i < len(data):
    entry = data[i]
    i += 1
    if len(entry) < 4:
        continue
    # porcelain 은 항상 XY + 공백 + 경로다. 첫 공백으로 자르면 ' M path' 처럼
    # X 가 공백인 항목에서 경로가 'M path' 가 된다.
    status, path = entry[:2], entry[3:]
    # rename/copy 는 다음 NUL 필드가 원본 경로다. 대상 경로만 쓰고 원본은 건너뛴다.
    # R/C 는 X(staged)뿐 아니라 Y 자리에도 올 수 있으므로 두 바이트를 모두 본다.
    if b'R' in status or b'C' in status:
        i += 1
    sys.stdout.buffer.write(path + b'\n')
" 2>/dev/null \
    | grep -E '\.java$' \
    | grep -E '^backend/(widyu-api|widyu-domain)/src/main/java/' \
    | grep -vE '/generated/|\.harness-metrics/' || true
}

CHANGED_JAVA=$(_changed_java)

if [[ -z "$CHANGED_JAVA" ]]; then
  rm -f "$STATE_FILE"
  exit 0
fi

# ─── 현재 Java 변경 상태의 지문 ───────────────────────────────────────────────
#
# 같은 diff 를 두 번 검수하지 않기 위한 키. 검수를 마쳤거나(APPROVE) 라운드 소진으로
# 포기한 상태를 이 해시로 기록해 두면, 코드가 실제로 바뀌기 전까지 재검수하지 않는다.
# untracked 신규 파일은 diff 에 안 잡히므로 내용을 직접 이어 붙인다.

# HEAD 를 반드시 섞는다. 같은 untracked 파일을 들고 브랜치를 옮기면 diff 내용이
# 같아 해시가 일치하고, 직전 브랜치의 reviewed_hash 로 새 브랜치 검수를 건너뛴다.
_diff_hash() {
  {
    git -C "$WORK_DIR" rev-parse HEAD 2>/dev/null || echo "no-head"
    git -C "$WORK_DIR" diff HEAD -- '*src/main/java*.java'
    # untracked 신규 파일은 diff 에 안 잡히므로 내용을 직접 이어 붙인다.
    _changed_java | while IFS= read -r f; do
      if ! git -C "$WORK_DIR" ls-files --error-unmatch "$f" >/dev/null 2>&1; then
        echo "--- $f"
        cat "$WORK_DIR/$f" 2>/dev/null
      fi
    done
  } 2>/dev/null | shasum -a 256 | cut -d' ' -f1
}

DIFF_HASH=$(_diff_hash)

# ─── Tier 1: 정적 규칙 검사 ───────────────────────────────────────────────────

RULE_REPORT=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  OUT=$(bash "$ROOT_DIR/scripts/harness/validate-java-rules.sh" "$WORK_DIR/$f" 2>&1)
  RC=$?
  if [[ $RC -ne 0 ]]; then RULE_REPORT+="$OUT"$'\n'; fi
done <<< "$CHANGED_JAVA"

STATIC_WAIVED=0
if [[ -n "$RULE_REPORT" ]]; then
  # 이 diff 상태에 대해 이미 포기한 적이 있으면 다시 막지 않는다.
  # (예전에는 포기 시 상태 파일을 지워 카운터가 0으로 돌아갔고, 같은 위반으로 무한 반복됐다)
  # 단 여기서 종료하면 컴파일·의미 검수까지 함께 사라지므로, 차단만 풀고 아래로 흘려보낸다.
  if [[ "$(_read_state_field "static_gave_up_hash" "")" == "$DIFF_HASH" ]]; then
    _audit_event "static_check" "result=violation_waived"
    echo "⚠️  미해결 규칙 위반이 있습니다(자동 수정 중단됨). 컴파일·의미 검수는 계속합니다." >&2
    STATIC_WAIVED=1
  fi
fi

if [[ -n "$RULE_REPORT" && $STATIC_WAIVED -eq 0 ]]; then
  STATIC_ROUND=$(_read_state_field "static_round")
  STATIC_ROUND=$((STATIC_ROUND + 1))
  _write_state_field "static_round" "$STATIC_ROUND"
  _audit_event "static_check" "result=violation" "round=$STATIC_ROUND"

  if [[ $STATIC_ROUND -gt $MAX_ROUNDS ]]; then
    _write_state_field "static_gave_up_hash" "$DIFF_HASH"
    _write_state_field "static_round" 0
    {
      echo "⚠️  코드 규칙 위반이 ${MAX_ROUNDS}회 반복 미해결. 자동 수정 루프를 중단합니다."
      echo "    아래 항목을 수동으로 확인하세요."
      echo "----- 정적 규칙 위반 -----"
      printf '%s\n' "$RULE_REPORT"
    } >&2
    # 여기서 끝내면 이번 정지에서 컴파일·의미 검수가 통째로 사라진다.
    # 다음 정지가 온다는 보장도 없으므로 차단만 풀고 같은 실행에서 계속 진행한다.
    STATIC_WAIVED=1
  else
    {
      echo "🔁 코드 규칙 위반 감지 (라운드 ${STATIC_ROUND}/${MAX_ROUNDS}) — 아래 항목을 수정하세요."
      echo "수정 후 정지 시 다시 자동 검사됩니다."
      echo "----- 정적 규칙 위반 -----"
      printf '%s\n' "$RULE_REPORT"
    } >&2
    exit 2
  fi
fi

# 위반을 waive 하고 내려온 경우까지 pass 로 적으면 감사 로그가 자기모순이 된다.
if [[ -z "$RULE_REPORT" ]]; then
  _audit_event "static_check" "result=pass"
fi
# 통과 시 정적 카운터 리셋 — 같은 세션의 다음 수정 건이 이전 카운트를 이어받지 않게
if [[ -f "$STATE_FILE" ]]; then
  _write_state_field "static_round" 0
fi

# ─── Tier 1.5: 컴파일 검사 ────────────────────────────────────────────────────
#
# 결정적이고 warm 기준 ~7초. 컴파일도 안 되는 상태로 Codex(최대 480초)를 부르는 건
# 순수 낭비이므로 여기서 먼저 거른다.

if ! COMPILE_OUT=$("$WORK_DIR/gradlew" -p "$WORK_DIR" compileJava --console=plain -q 2>&1); then
  # 포기한 diff 는 다시 막지 않는다. 카운터만 0으로 되돌리면 다음 정지에서
  # 같은 실패로 라운드 1부터 또 차단해 루프가 끝나지 않는다.
  if [[ "$(_read_state_field "compile_gave_up_hash" "")" == "$DIFF_HASH" ]]; then
    _audit_event "compile_check" "result=fail_waived"
    echo "⚠️  컴파일 실패가 미해결 상태입니다(자동 수정 중단됨)." >&2
    exit 0
  fi

  COMPILE_ROUND=$(_read_state_field "compile_round")
  COMPILE_ROUND=$((COMPILE_ROUND + 1))
  _write_state_field "compile_round" "$COMPILE_ROUND"
  _audit_event "compile_check" "result=fail" "round=$COMPILE_ROUND"

  if [[ $COMPILE_ROUND -gt $MAX_ROUNDS ]]; then
    _write_state_field "compile_gave_up_hash" "$DIFF_HASH"
    _write_state_field "compile_round" 0
    {
      echo "⚠️  컴파일 실패가 ${MAX_ROUNDS}회 반복 미해결. 자동 수정 루프를 중단합니다."
      printf '%s\n' "$COMPILE_OUT" | tail -30
    } >&2
    exit 0
  fi

  {
    echo "🔁 컴파일 실패 (라운드 ${COMPILE_ROUND}/${MAX_ROUNDS}) — 아래 오류를 수정하세요."
    printf '%s\n' "$COMPILE_OUT" | tail -30
  } >&2
  exit 2
fi

_audit_event "compile_check" "result=pass"
_write_state_field "compile_round" 0

# ─── Tier 2: Codex 필요성 게이트 ─────────────────────────────────────────────

# diff HEAD: staged+unstaged 모두 포함 (git diff 단독은 staged 누락 → 게이트 우회 구멍)
# untracked 신규 파일은 numstat에 안 잡히지만 NEW_FILES=0 조건이 걸러줌
DIFF_LOC=$(git -C "$WORK_DIR" diff HEAD --numstat -- '*src/main/java*.java' 2>/dev/null \
  | grep -v '/generated/' \
  | awk '{a+=$1+$2} END{print a+0}')
FILE_COUNT=$(echo "$CHANGED_JAVA" | grep -c . || true)
# 신규 파일: untracked(??)와 staged 추가(A) 모두 포착
# CHANGED_JAVA 와 같은 NUL 기반 목록에서 센다. 여기만 줄 파싱을 남겨두면
# 공백이 든 경로의 신규 파일이 0개로 잡혀 소규모 변경 게이트로 검수가 새어나간다.
NEW_FILES=$(echo "$CHANGED_JAVA" | while IFS= read -r f; do
  [ -n "$f" ] || continue
  git -C "$WORK_DIR" ls-files --error-unmatch "$f" >/dev/null 2>&1 || echo "$f"
done | grep -c . || true)
# 민감 경로는 실제 패키지명 기준이어야 한다. com.widyu.pay 를 'payment' 로 적으면
# 결제 도메인 전체가 게이트를 그냥 통과한다.
SENSITIVE=$(echo "$CHANGED_JAVA" \
  | grep -cE '/com/widyu/(auth|pay)/|/global/security/' || true)

if [[ $DIFF_LOC -lt 30 && $FILE_COUNT -le 2 && $NEW_FILES -eq 0 && $SENSITIVE -eq 0 ]]; then
  echo "✅ 소규모 변경 (${DIFF_LOC} LOC, ${FILE_COUNT}개 파일) — 정적 검사 통과, Codex 생략." >&2
  _audit_event "review_skipped" "reason=small_diff" "diff_loc=$DIFF_LOC" "file_count=$FILE_COUNT"
  _write_state_field "reviewed_hash" "$DIFF_HASH"
  exit 0
fi

# 직전 검수 이후 Java 코드가 한 글자도 바뀌지 않았으면 재검수하지 않는다.
# 워킹트리 diff 는 커밋 전까지 계속 누적되므로, 이 확인이 없으면 같은 diff 를
# 세션이 끝날 때까지 반복 검수한다 (실측: Codex 호출의 33%가 중복이었다).
if [[ "$(_read_state_field "reviewed_hash" "")" == "$DIFF_HASH" ]]; then
  echo "✅ 직전 검수 이후 Java 변경 없음 — Codex 생략." >&2
  _audit_event "review_skipped" "reason=unchanged_diff" "diff_loc=$DIFF_LOC" "file_count=$FILE_COUNT"
  exit 0
fi

# Tier 2 게이트 불충족 이유를 audit에 기록 (튜닝 근거 확보)
GATE_MISS=""
[[ $DIFF_LOC -ge 30 ]]    && GATE_MISS+="diff_loc:${DIFF_LOC}"
[[ $FILE_COUNT -gt 2 ]]   && GATE_MISS+="${GATE_MISS:+,}file_count:${FILE_COUNT}"
[[ $NEW_FILES -ne 0 ]]    && GATE_MISS+="${GATE_MISS:+,}new_files:${NEW_FILES}"
[[ $SENSITIVE -ne 0 ]]    && GATE_MISS+="${GATE_MISS:+,}sensitive:${SENSITIVE}"
_audit_event "gate_miss" "reason=${GATE_MISS}" "diff_loc=$DIFF_LOC" "file_count=$FILE_COUNT" "new_files=$NEW_FILES" "sensitive=$SENSITIVE"

# ─── Tier 3: Codex 시맨틱 리뷰 ───────────────────────────────────────────────

# 직전 BLOCKER 이후 코드가 그대로면 결과도 같다. 저장해 둔 리포트를 다시 내보내고
# Codex 는 부르지 않는다. 라운드는 그대로 세어 MAX_ROUNDS 로 종결되게 둔다.
# (실측: 한 세션에서 688 LOC 짜리 동일 diff 를 연속 3회 재검수한 사례가 있다)
PREV_BLOCKED_FILE=$(_read_state_field "blocked_file" "")
if [[ "$(_read_state_field "blocked_hash" "")" == "$DIFF_HASH" && -f "$PREV_BLOCKED_FILE" ]]; then
  ROUND=$(_read_state_field "round")
  ROUND=$((ROUND + 1))
  _write_state_field "round" "$ROUND"
  _audit_event "review_skipped" "reason=unchanged_after_blocker" "round=$ROUND"

  if [[ $ROUND -gt $MAX_ROUNDS ]]; then
    _write_state_field "round" 0
    _write_state_field "reviewed_hash" "$DIFF_HASH"
    echo "⚠️  코드 변경 없이 ${MAX_ROUNDS}회 반복됐습니다. 자동 검수 루프를 중단합니다." >&2
    exit 0
  fi

  {
    echo "🔁 직전 검수 이후 Java 코드가 바뀌지 않았습니다 (라운드 ${ROUND}/${MAX_ROUNDS})."
    echo "아래 BLOCKER를 실제로 반영하거나, 반영이 불필요하다면 사용자에게 근거를 설명하세요."
    echo "----- Codex Review (BLOCKERS) -----"
    cat "$PREV_BLOCKED_FILE"
  } >&2
  exit 2
fi

echo "🔍 Codex 자동 검수 실행 중... (${DIFF_LOC} LOC, ${FILE_COUNT}개 파일)" >&2

REPORT="$(bash "$ROOT_DIR/scripts/harness/codex-review.sh")"
RC=$?

# Codex 실행 실패 → fail-open
if [[ $RC -eq 3 ]] || printf '%s' "$REPORT" | head -1 | grep -q "CODEX_FAILED"; then
  echo "⚠️  Codex 자동 검수를 실행하지 못했습니다 (네트워크/타임아웃/인증 등). 수동 검수가 필요합니다." >&2
  _audit_codex "FAILED" 0
  exit 0
fi

# SUGGESTIONS 분리 헬퍼 — APPROVE / BLOCKER 공통
_save_suggestions() {
  local body="$1"
  [[ -n "$body" ]] || return
  local sugg_file="$SUGG_DIR/suggestions-${SID_PREFIX}-$(date +%s).md"
  {
    echo "# Codex 제안 사항 ($(date '+%Y-%m-%d %H:%M'))"
    echo ""
    printf '%s\n' "$body"
  } > "$sugg_file"
  echo "💡 Codex 제안 사항 저장: ${sugg_file#$ROOT_DIR/}" >&2
}

_save_blockers() {
  local body="$1" round="$2"
  [[ -n "$body" ]] || return
  local blocker_file="$SUGG_DIR/blockers-${SID_PREFIX}-$(date +%s).md"
  {
    echo "# Codex BLOCKER ($(date '+%Y-%m-%d %H:%M')) round=${round}"
    echo ""
    printf '%s\n' "$body"
  } > "$blocker_file"
  # 코드가 안 바뀐 채 재정지하면 Codex 를 다시 부르지 않고 이 파일을 재사용한다.
  _write_state_field "blocked_file" "$blocker_file"
  _write_state_field "blocked_hash" "$DIFF_HASH"
}

# codex exec는 프롬프트를 출력에 에코하며, 프롬프트에도 VERDICT:/SUGGESTIONS: 줄이
# 있으므로 첫-매칭 분리는 에코에 걸린다. 마지막 VERDICT: 줄부터가 실제 응답이다.
FINAL_SECTION=$(printf '%s\n' "$REPORT" | awk '
  /^VERDICT:/ { n = NR }
  { lines[NR] = $0 }
  END { if (!n) exit; for (i = n; i <= NR; i++) print lines[i] }')

SUGGESTION_BODY=$(printf '%s\n' "$FINAL_SECTION" | awk 'found{print} /^SUGGESTIONS:/{found=1}')

# APPROVE → 라운드 초기화, 정지 허용
if [[ $RC -eq 0 ]]; then
  _save_suggestions "$SUGGESTION_BODY"
  # 상태 파일을 지우면 같은 diff 를 다음 정지에서 또 검수한다. 지문만 남기고 보존.
  _write_state_field "round" 0
  _write_state_field "reviewed_hash" "$DIFF_HASH"
  echo "✅ Codex 검수 통과 (APPROVE)." >&2
  _audit_codex "APPROVE" 0
  exit 0
fi

# RC == 1 → BLOCKER: 라운드 카운트
ROUND=$(_read_state_field "round")
ROUND=$((ROUND + 1))
_write_state_field "round" "$ROUND"
_audit_codex "BLOCKER" "$ROUND"

# BLOCKERS만 Claude에 전달, SUGGESTIONS는 저장만 (에코 제거된 FINAL_SECTION 기준)
BLOCKER_BODY=$(printf '%s\n' "$FINAL_SECTION" | awk '/^SUGGESTIONS:/{exit} 1')
# FINAL_SECTION이 비면(VERDICT 줄 부재) 원문 전체로 폴백 — 정보 유실 방지
if [[ -z "$BLOCKER_BODY" ]]; then
  BLOCKER_BODY="$REPORT"
fi
_save_suggestions "$SUGGESTION_BODY"
_save_blockers "$BLOCKER_BODY" "$ROUND"

if [[ $ROUND -gt $MAX_ROUNDS ]]; then
  # 포기도 지문으로 기록한다. 예전에는 상태를 지워 카운터가 0으로 돌아갔고,
  # 같은 diff 로 라운드 1부터 다시 돌아 한 세션에서 Codex 를 12회까지 호출했다.
  _write_state_field "round" 0
  _write_state_field "reviewed_hash" "$DIFF_HASH"
  {
    echo "⚠️  Codex 검수를 ${MAX_ROUNDS}회 반복했으나 여전히 BLOCKER가 있습니다."
    echo "    자동 수정 루프를 중단합니다. 아래 리포트를 수동으로 확인하세요."
    echo "----- Codex Review (BLOCKERS) -----"
    printf '%s\n' "$BLOCKER_BODY"
  } >&2
  exit 0
fi

{
  echo "🔁 Codex 자동 검수 결과: BLOCKER 발견 (라운드 ${ROUND}/${MAX_ROUNDS})"
  echo "아래 결함을 반영해 코드를 수정하세요. 수정 후 정지 시 다시 자동 검수됩니다."
  echo "수정할 항목이 없다고 판단되면 사용자에게 근거와 함께 확인을 요청하세요."
  echo "----- Codex Review (BLOCKERS) -----"
  printf '%s\n' "$BLOCKER_BODY"
} >&2
exit 2
