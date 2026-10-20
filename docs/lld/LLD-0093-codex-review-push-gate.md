# LLD-0093: 별도 worktree·커밋된 변경도 PR 전에 Codex 검수를 거치게 한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #790 우선 진행 지시, 2026-10-19) |
| Issue | #790 |
| 관련 ADR | ADR-0018, ADR-0021 (하네스 가드) |
| 작성자 | Claude |
| 작성일 | 2026-10-19 |

## 1. 목적 / 배경

Codex 의미 검수는 응답 종료 훅(`on-stop.sh`)이 `codex-review.sh`를 불러 실행한다. 2026-10-17~19에 별도 worktree에서 서브에이전트가 구현·커밋한 PR 4건(#776, #779, #781, #788)이 **Codex 검수를 한 번도 받지 않았다**(감사 로그에 `java_rule_check`만 있고 Codex 이벤트 0건). 사후 커밋 범위 검수에서 2건이 BLOCKER였다(#788 센서 수신 시각, #779 → #789).

원인:
1. `on-stop.sh`·`codex-review.sh`는 스크립트 위치(원본 체크아웃)에서 `git status`로 변경을 찾는다. 다른 worktree의 변경은 보이지 않는다
2. `codex-review.sh`는 커밋되지 않은 변경만 검수한다. 커밋이 끝난 변경은 대상이 아니다
3. `Stop` 훅만 있어 서브에이전트가 끝날 때는 검수가 시작되지 않는다

검수가 **언제 일어나느냐**(세션 종료)에 기대는 대신, 변경이 저장소 밖으로 나가는 지점(**push·PR 생성**)에서 검수 여부를 확인한다.

## 2. 범위

### In scope
- 변경 대상: `scripts/harness/codex-review.sh`, `scripts/harness/pre-bash-guard.sh`, `scripts/harness/on-stop.sh`, `scripts/harness/test-pre-bash-guard.py`(및 필요 시 새 테스트), 하네스 사용 문서(아래 제약 참고)
- **커밋 범위 검수 모드:** `codex-review.sh --base <ref>`가 `<ref>..HEAD` 커밋 범위(+ 미커밋 변경)를 **호출한 위치의 git 작업 트리**(`git rev-parse --show-toplevel`)에서 검수한다. 기존 인자 없는 호출(미커밋 변경 검수)은 그대로 동작한다. Codex는 읽기 전용 샌드박스(`-s read-only`)로 실행한다
- **검수 기록:** 커밋 범위 검수가 끝나면 그 작업 트리의 git 디렉터리(`git rev-parse --git-dir`, worktree마다 다름) 아래에 HEAD SHA를 키로 판정(APPROVE / BLOCKER / FAILED)을 저장하고, 감사 로그(`.claude/audit`)에 이벤트를 남긴다
- **push·PR 게이트(`pre-bash-guard.sh`):** 명령에 `git push` 또는 `gh pr create`가 있으면 대상 작업 트리를 정한다(같은 명령의 앞선 `cd <경로>`, 없으면 훅 입력의 `cwd`). 그 브랜치가 기준(`upstream/develop`, 없으면 `origin/develop`)과의 merge-base 이후 **main 소스 Java**(`backend/*/src/main/java/`)를 바꿨다면:
  - HEAD에 대한 검수 기록이 없으면 **차단**하고, 실행할 명령(`bash scripts/harness/codex-review.sh --base <merge-base>`)을 안내한다
  - 판정이 BLOCKER면 **차단**한다. 검토 끝에 수용한 경우를 위해 `WIDYU_CODEX_ACCEPT="<사유>"` 환경 변수 접두를 붙인 명령은 통과시키고 사유를 감사 로그에 남긴다
  - 판정이 FAILED(Codex 실행 실패: 네트워크·한도·타임아웃)면 **경고하고 통과**시킨다(fail-open). 실패 사실은 감사 로그에 남는다
  - main 소스 Java 변경이 없으면(문서·테스트·스크립트만) 통과한다
- **종료 훅 위치 보정:** `on-stop.sh`가 훅 입력의 `cwd`로 실제 작업 트리를 찾아 그곳의 미커밋 변경을 본다(cwd가 같은 저장소의 worktree가 아니면 기존 동작)
- 서브에이전트 운영 안내: 긴 명령(verify.sh 등)은 백그라운드로 돌리거나 범위를 나눈다. 출력 없이 600초가 지나면 서브에이전트가 중단된다(#791에서 두 번 관찰)

### Out of scope
- `SubagentStop` 훅 등록: 서브에이전트마다 Codex를 돌리면 비용이 크고, push 게이트가 같은 보장을 준다. 필요하면 후속
- Codex CLI 세션용 훅(`codex-hooks.py`)과 `feature/677`의 진행 중 하네스 변경 파일(`codex-hooks.py`, `test_codex_harness.py`, `LLD-0026`, `docs/harness/codex.md`, `AGENTS.md`, `.gitignore`): 이번에 수정하지 않는다
- 서버 쪽(GitHub Actions) 강제: 로컬 가드는 사고 방지 장치이지 보안 경계가 아니다(ADR-0018)

## 3. 인터페이스

```text
bash scripts/harness/codex-review.sh                 # 기존: 미커밋 Java 변경 검수
bash scripts/harness/codex-review.sh --base <ref>    # 신규: <ref>..HEAD + 미커밋 검수, 판정 기록
WIDYU_CODEX_ACCEPT="사유" git push ...                # BLOCKER를 검토 후 수용할 때만
```

종료 코드는 기존과 같다(0 APPROVE, 1 BLOCKER, 3 Codex 실패).

## 4. 데이터

- 판정 기록: `<git-dir>/widyu-codex-review/<head-sha>` (판정, base, 시각). git이 추적하지 않는 위치라 커밋되지 않는다
- 감사 이벤트: `codex_branch_review`(판정·base·head·작업 트리), `codex_gate`(push/PR 허용·차단·수용 사유)
- 감사 로그 위치: 대상 작업 트리의 `git-common-dir` 상위, 즉 원본 체크아웃의 `.claude/audit/audit-YYYY-MM.jsonl`. worktree에서 돌린 검수·게이트 판정도 훅 감사 로그와 같은 파일에 모인다

## 5. 처리 흐름

1. 구현 → 커밋
2. `codex-review.sh --base $(git merge-base upstream/develop HEAD)` → 판정 기록
3. `git push` / `gh pr create` → 가드가 HEAD 판정 확인 → 통과·차단
4. 커밋을 더 하면 HEAD가 바뀌어 기록이 없으므로 다시 검수해야 한다

## 6. 예외 / 에러 처리

- 가드가 대상 작업 트리나 기준 ref를 정할 수 없으면 **통과**시키고 stderr에 경고한다(가드의 "오탐보다 미탐" 원칙, 오탐은 곧 작업 중단)
- 판정 기록 파일이 손상됐으면 기록 없음으로 본다

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 다른 worktree에서 `codex-review.sh --base <ref>`를 실행하면 그 worktree의 커밋 범위를 검수하고 판정을 그 worktree의 git 디렉터리에 기록한다(가짜 `codex` 실행 파일로 테스트)
- [x] AC2: main Java를 바꾼 브랜치에서 검수 기록 없이 `git push`·`gh pr create`(앞에 `cd <worktree> &&`가 붙은 경우 포함)를 실행하면 차단되고 실행할 검수 명령이 안내된다
- [x] AC3: HEAD 판정이 APPROVE면 통과, BLOCKER면 차단, `WIDYU_CODEX_ACCEPT` 접두가 있으면 통과하고 사유가 감사 로그에 남는다, FAILED면 경고하고 통과한다
- [x] AC4: main Java 변경이 없는 브랜치(문서·테스트만)는 기록 없이도 통과한다
- [x] AC5: `on-stop.sh`가 훅 입력 `cwd`의 worktree에서 미커밋 변경을 찾는다
- [x] AC6: 기존 하네스 회귀 테스트(`test-pre-bash-guard.py`, `test-pre-edit-branch-guard.py`, `verify.sh`의 하네스 검사)가 통과한다

## 8. 영향 범위 / 마이그레이션

훅 스크립트는 원본 체크아웃(`$CLAUDE_PROJECT_DIR`)의 파일이 실행되므로, 이 변경은 머지 뒤 원본 체크아웃이 최신 develop을 받은 다음부터 적용된다. 이미 열린 PR(#788, #792, #796)은 사후 검수를 마쳤다.

신규 ADR N/A: 기존 가드 결정(ADR-0018·0021)의 범위 안에서 검수 시점을 push·PR로 옮기는 구현이다.

## 미결정 사항(Open Questions)

없음. Codex 실패 시 fail-open은 사용자에게 기본값으로 알리고 진행했다(2026-10-19).

## 10. 참고

- `scripts/harness/on-stop.sh`, `scripts/harness/codex-review.sh`, `scripts/harness/pre-bash-guard.sh`
- 사후 검수에 쓴 임시 스크립트: 세션 스크래치패드 `codex-review/review-branch.sh`(같은 판정 기준, `codex exec -s read-only`, base..HEAD)

## 11. 검증 기록

### 2026-10-19 (Claude, feature/790)

- 새 테스트 `scripts/harness/test_codex_review_gate.py` 11건 통과: 임시 저장소(develop + worktree)와 PATH 앞의 가짜 `codex`로 AC1~AC5를 확인했다. 변경 전 스크립트 3개로 되돌려 같은 테스트를 돌리면 실패 9·오류 3(서브테스트 단위)이 나와, 테스트가 변경을 실제로 잡는다
- `test-pre-bash-guard.py` 68건 통과. 명령 판정 케이스가 실행 위치의 브랜치 상태에 흔들리지 않게 훅 입력 `cwd`를 git 저장소가 아닌 임시 디렉터리로 고정했다
- `bash scripts/harness/verify.sh --base 1275e1dd`: unittest 50건(신규 11건 포함)·가드 68건·브랜치 가드 11건 통과, backend 변경 없음으로 Gradle 생략, 종료 코드 0
- 구현상 결정: `codex-review.sh`는 커밋 범위 모드에서만 `-s read-only`를 붙인다(무인자 호출의 실행 방식 유지). Codex가 VERDICT 줄 없이 끝나면 종료 코드는 기존대로 0이지만 판정 기록은 FAILED로 남겨 게이트가 승인으로 보지 않게 했다
- 게이트 한계: 대상 작업 트리의 HEAD만 본다. refspec으로 다른 브랜치를 push하거나 `gh pr create --head`로 다른 브랜치를 지정하면 그 브랜치가 아니라 HEAD를 확인한다. `git push --dry-run`·`--delete`는 게이트 대상에서 뺐다
