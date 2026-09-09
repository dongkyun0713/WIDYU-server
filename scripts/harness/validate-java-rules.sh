#!/bin/bash
# WIDYU 코드 규칙 검사 (AGENTS.md 기반)
# 사용법: bash scripts/harness/validate-java-rules.sh <java-file-path>
#
# 라인 단위 규칙(1·2·6)은 HEAD 대비 추가된 라인만 검사한다. 파일 전체를 보면
# 내가 쓰지 않은 기존 위반(현재 518개 중 40개)까지 걸려, 무관한 한 줄 수정에도
# 정지가 차단된다. 파일 배치 규칙(3·4·5)은 성격상 파일 전체 기준을 유지한다.
# HARNESS_FULL_FILE=1 이면 기존 방식대로 파일 전체를 검사한다.

FILE="$1"
if [[ ! -f "$FILE" ]]; then exit 0; fi

DIFF_BASE="${HARNESS_DIFF_BASE:-HEAD}"
if ! git rev-parse --verify "$DIFF_BASE^{commit}" >/dev/null 2>&1; then
    echo "[HARNESS] 유효하지 않은 검사 기준: $DIFF_BASE" >&2
    exit 1
fi

BASENAME=$(basename "$FILE")
ERRORS=0

echo "[HARNESS] 규칙 검사: ${FILE#*/WIDYU-server/}"

# 추가된 라인 번호 집합. 파일로 넘긴다 — 인자로 넘기면 큰 파일에서 ARG_MAX 를 넘겨
# awk 가 죽고, 그러면 위반이 하나도 안 잡힌 것처럼 조용히 통과해 버린다.
ADDED_FILE=$(mktemp -t harness-added.XXXXXX)
# 삭제 지점까지 포함한 "건드린 라인". 순수 삭제(@@ -19 +18,0 @@)는 추가 라인이 0이라
# ADDED_FILE 에 아무것도 안 남는다. @Transactional 을 지워 규칙을 깨는 변경이 그렇다.
TOUCHED_FILE=$(mktemp -t harness-touched.XXXXXX)
trap 'rm -f "$ADDED_FILE" "$TOUCHED_FILE"' EXIT

if [[ "${HARNESS_FULL_FILE:-0}" != "1" ]]; then
    HUNKS=$(git diff "$DIFF_BASE" -U0 -- "$FILE" 2>/dev/null | grep '^@@' || true)
    printf '%s\n' "$HUNKS" \
        | awk '/^@@/ {
                 match($0, /\+[0-9]+(,[0-9]+)?/)
                 spec = substr($0, RSTART + 1, RLENGTH - 1)
                 split(spec, a, ",")
                 count = (a[2] == "" ? 1 : a[2])
                 for (i = 0; i < count; i++) print a[1] + i
               }' > "$ADDED_FILE"
    # 삭제 앵커는 @Transactional 을 지운 hunk 에만 붙인다. 모든 삭제에 앵커를 달면
    # 기존 위반 바로 앞의 무관한 주석 한 줄만 지워도 규칙6이 되살아나, 레거시가
    # 무관한 수정을 막지 않게 하려던 목적 자체가 무너진다.
    {
        cat "$ADDED_FILE"
        git diff "$DIFF_BASE" -U0 -- "$FILE" 2>/dev/null \
            | awk '/^@@/ {
                     match($0, /\+[0-9]+(,[0-9]+)?/)
                     spec = substr($0, RSTART + 1, RLENGTH - 1)
                     split(spec, a, ",")
                     anchor = a[1]
                     next
                   }
                   /^-[[:space:]]*@Transactional/ { print anchor; print anchor + 1 }'
    } | sort -un > "$TOUCHED_FILE"
    # 추적되지 않는 신규 파일은 diff 가 비어 있으므로 전체 라인을 대상으로 한다.
    if [[ ! -s "$ADDED_FILE" && ! -s "$TOUCHED_FILE" ]] \
        && ! git ls-files --error-unmatch "$FILE" >/dev/null 2>&1; then
        awk '{print NR}' "$FILE" | tee "$ADDED_FILE" > "$TOUCHED_FILE"
    fi
fi

# grep -n 출력(N:내용)을 추가된 라인만 남기고 거른다.
filter_added() {
    if [[ "${HARNESS_FULL_FILE:-0}" == "1" ]]; then cat; return; fi
    if [[ ! -s "$ADDED_FILE" ]]; then return; fi
    awk -v af="$ADDED_FILE" '
        BEGIN { while ((getline l < af) > 0) keep[l] = 1 }
        { split($0, p, ":"); if (p[1] in keep) print }
    '
}

# "시작-끝: 내용" 형식을 구간 안에 추가된 라인이 하나라도 있으면 남긴다.
# 애노테이션 블록처럼 위반 지점과 변경 지점이 다른 규칙에 쓴다.
filter_added_range() {
    if [[ "${HARNESS_FULL_FILE:-0}" == "1" ]]; then cat; return; fi
    if [[ ! -s "$TOUCHED_FILE" ]]; then return; fi
    awk -v af="$TOUCHED_FILE" '
        BEGIN { while ((getline l < af) > 0) keep[l] = 1 }
        {
            split($0, p, ":"); split(p[1], r, "-")
            for (i = r[1]; i <= r[2]; i++) if (i in keep) { print; break }
        }
    '
}

# ──────────────────────────────────────────────
# 규칙 1: 삼항 연산자 금지
# ──────────────────────────────────────────────
TERNARY=$(grep -n ' ? ' "$FILE" \
    | grep -v '^\s*//' \
    | grep -v '^\s*\*' \
    | grep -v '? extends' \
    | grep -v '? super' \
    | grep -v '".*?.*"' \
    | filter_added)
if [[ -n "$TERNARY" ]]; then
    echo "❌ [규칙1] 삼항 연산자 금지 → if/else 또는 early return으로 변경"
    echo "$TERNARY" | sed 's/^/   /'
    ((ERRORS++))
fi

# ──────────────────────────────────────────────
# 규칙 2: DTO 직접 생성 금지 (Service/Facade)
# ──────────────────────────────────────────────
if [[ "$BASENAME" == *Service* || "$BASENAME" == *Facade* ]]; then
    DTO_NEW=$(grep -nE 'new [A-Z][a-zA-Z]*(Response|Request|Dto|Res|Req)\(' "$FILE" \
        | grep -v '^\s*//' \
        | filter_added)
    if [[ -n "$DTO_NEW" ]]; then
        echo "❌ [규칙2] DTO 직접 생성 금지 → DTO에 from()/of() 팩토리 메서드 추가"
        echo "$DTO_NEW" | sed 's/^/   /'
        ((ERRORS++))
    fi
fi

# ──────────────────────────────────────────────
# 규칙 3: Controller → Repository 직접 접근 금지
# ──────────────────────────────────────────────
if [[ "$FILE" == *"/controller/"* ]]; then
    REPO_IMPORT=$(grep -n 'import.*\.repository\.' "$FILE" | grep -v '^\s*//')
    if [[ -n "$REPO_IMPORT" ]]; then
        echo "❌ [규칙3] Controller에서 Repository 직접 import 금지 (계층 분리 원칙)"
        echo "$REPO_IMPORT" | sed 's/^/   /'
        ((ERRORS++))
    fi
fi

# ──────────────────────────────────────────────
# 규칙 4: @Entity는 widyu-domain에만 위치
# ──────────────────────────────────────────────
if [[ "$FILE" == *"widyu-api"* ]]; then
    ENTITY=$(grep -n '^@Entity' "$FILE")
    if [[ -n "$ENTITY" ]]; then
        echo "❌ [규칙4] @Entity는 widyu-domain에 위치해야 합니다"
        ((ERRORS++))
    fi
fi

# ──────────────────────────────────────────────
# 규칙 5: Repository는 widyu-api에만 위치
# ──────────────────────────────────────────────
if [[ "$FILE" == *"widyu-domain"* && "$BASENAME" == *Repository* ]]; then
    echo "❌ [규칙5] Repository는 widyu-api에 위치해야 합니다"
    ((ERRORS++))
fi

# ──────────────────────────────────────────────
# 규칙 6: @Async 메서드에 @Transactional 확인
# ──────────────────────────────────────────────
# @Async 다음 줄만 보면 @Async→@EventListener→@Transactional 순서를 오탐한다.
# 메서드 시그니처에 닿을 때까지 애노테이션 블록 전체에서 @Transactional 을 찾는다.
# 위반은 메서드 선언 라인에 찍히지만 변경은 @Async 를 붙이거나 @Transactional 을
# 지운 애노테이션 라인에서 일어난다. 블록 전체 구간을 기준으로 걸러야 놓치지 않는다.
ASYNC_NO_TX=$(awk '
    /^[[:space:]]*@Async/ { in_block=1; has_tx=0; start=NR; next }
    in_block && /^[[:space:]]*@Transactional/ { has_tx=1; next }
    in_block && /^[[:space:]]*(@|\/\/|\*|$)/ { next }
    in_block && /^[[:space:]]*(public|protected|private)/ {
        if (!has_tx) print start"-"NR": "$0" ← @Async인데 @Transactional 없음"
        in_block=0; next
    }
    { in_block=0 }
' "$FILE" | filter_added_range)
if [[ -n "$ASYNC_NO_TX" ]]; then
    echo "❌ [규칙6] @Async 메서드에 @Transactional 누락"
    echo "$ASYNC_NO_TX" | sed 's/^/   /'
    ((ERRORS++))
fi

# ──────────────────────────────────────────────
# 결과
# ──────────────────────────────────────────────
if [[ $ERRORS -gt 0 ]]; then
    echo "⛔ 규칙 위반 ${ERRORS}건 — 수정 필요"
    exit 1
else
    echo "✅ 규칙 검사 통과"
fi
