# LLD-0026: Codex 기본 하네스 전환

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #549 |
| 관련 ADR | ADR-0022 |
| 작성자 | Codex |
| 작성일 | 2026-09-07 |

## 1. 목적 / 배경

Codex가 설계부터 구현·검증·리뷰까지 수행할 수 있도록 Claude 중심 지침과 검증 연결을 전환한다.

## 2. 범위

루트/backend/admin AGENTS.md 추가, 공통 스킬 5개 보강과 write 추가, .codex/config.toml, 검증 및 Codex 훅 어댑터, 하네스 회귀 테스트·클라이언트 통합 검사·CI, 운영 가이드가 대상이다. 파일 수는 많지만 지침과 실행 경로 연결이 하나의 목적이므로 함께 검토한다. 기존 루트/backend/admin CLAUDE.md의 내용은 보존한다. 애플리케이션 동작, 개인 설정, 세션 기록, 배포 설정은 변경하지 않는다.

## 3. 인터페이스 / API

- `bash scripts/harness/verify.sh [--base REF] [--static-only]`: 변경 범위 규칙 검사, 기본 실행은 영향 모듈 컴파일/테스트와 admin lint/build도 포함한다.
- `bash scripts/harness/run-module-tests.sh [api|domain|all|모듈 내 파일 경로]`: 호환 진입점. 인자가 없으면 작업 트리 기준 자동 선택한다.
- `python3 scripts/harness/codex-hooks.py`: stdin의 Codex hook_event_name에 따라 PreToolUse/Stop을 처리한다.
- `python3 scripts/harness/doctor.py`: 실제 Codex의 훅 정의 원본·활성화·신뢰 상태를 조회한다. 미준비이면 비영 종료한다.
- `python3 scripts/harness/smoke_codex_client.py`: 명시적 CLI 통합 검사. 임시 홈·저장소와 루프백 응답 fixture만 사용한다.
- 종료 코드: 성공 0, 검증 실패 비영(호출한 명령 코드 전달), 잘못된 인자/기준 ref는 비영. 훅 차단은 JSON 프로토콜로 반환한다.

## 4. 데이터 모델

DB 변경 없음. Git의 NUL 구분 경로 목록을 사용한다. Codex 브랜치 확인은 gitignore된 `.codex/state/branch-ack-*.txt`를 세션×브랜치 단위로 사용한다. 사용자 명령 원문 로그는 만들지 않는다.

## 5. 처리 흐름

1. 명시된 base 또는 HEAD를 검증하고 base → 현재 작업 트리 diff와 untracked 경로를 합친다. rename은 이전/이후 경로를 모두 영향 분석에 포함한다.
2. 존재하는 main Java 파일을 기존 정적 검사기에 전달한다. 검사기는 같은 base를 사용한다. 삭제 파일은 규칙 검사에서 제외하되 영향 모듈에는 포함한다.
3. backend 변경은 compileJava 후 테스트한다. domain/공통 Gradle 변경이면 domain과 API를 모두, API 변경이면 API를 실행한다. admin 변경이면 lint/build를 실행한다. 문서만 변경하면 애플리케이션 검사를 생략했다고 출력한다.
4. PreToolUse Bash는 기존 명령 가드를 적용한다. apply_patch는 main/master/develop 및 detached HEAD를 차단한다. 작업 브랜치의 세션 최초 편집도 차단하고, 현재 요청이 그 브랜치의 기존 작업을 이어가는 경우에만 세션×브랜치 ack 생성 명령을 안내한다. 새 작업이면 issue 스킬로 새 브랜치/worktree를 만든다.
5. Stop은 작업 트리 정적 검사 결과만 반환한다. 실패 시 한 번 후속 수정을 요청하고 stop_hook_active인 재진입에서는 미해결 결과를 알린다. 별도 LLM 호출은 없다.
6. 스킬 검수는 인수조건과 변경 동작을 확인한다. 커밋/PR은 해당 요청 범위일 때만 실행한다.

## 6. 예외 / 에러 처리

### 후속 보완 범위 (#549)

- patch의 Add/Update/Delete/Move 대상은 세션 worktree 안에 있어야 한다. 상대 경로는 세션 cwd 기준으로 해석하며 심볼릭 링크·중첩 저장소·다른 worktree로 향하는 대상은 차단한다. 승인된 브랜치의 ack로 다른 작업 공간을 수정할 수 없다.
- 셸의 직접 파일 쓰기(리다이렉션, tee, 파일 편집·복사·이동 명령, 인라인 스크립트의 쓰기)는 apply_patch로 유도한다. 저장소 밖 임시 출력과 현재 세션·브랜치의 ack 생성은 허용한다. 임의 프로그램의 모든 부수효과를 판별하는 보안 경계는 아니다.
- 일반 verify는 하네스 스크립트·Codex 설정·스킬·AGENTS.md·CI 변경을 감지하면 하네스 회귀 테스트를 실행한다. --static-only 및 모듈 테스트 전용 경로에서는 재귀 실행하지 않는다.
- 프로젝트 문서용 write 스킬을 추가하고 issue/pr 스킬에서 참조한다. 기존 문서 구조와 기술적 사실을 보존하면서 읽는 사람이 문제·동작·근거를 이해하도록 작성한다.
- 실제 Codex 클라이언트의 훅 탐색·신뢰·발화를 확인하는 진단 절차를 제공한다. 신뢰하지 않은 상태를 통과로 보고하지 않으며 신뢰 설정을 자동 우회하지 않는다.
- Codex의 linked worktree 훅 정의가 primary checkout에서 로드됨을 진단에 반영한다. 테스트 fixture에서만 조회한 정의의 해시를 임시 Codex 홈에 신뢰로 기록한다. 사용자의 설정·인증은 변경하거나 복사하지 않는다.

잘못된 Git ref/입력, 정적 규칙 위반, Gradle/npm 실패를 통과로 바꾸지 않는다. 사용자 작업이 있는 폴더에서 stash/reset/switch로 작업을 옮기지 않고 별도 worktree를 사용한다. 필요한 도구나 서비스가 없으면 실행하지 못한 검증을 구분해 보고한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] Codex는 루트/backend/admin AGENTS.md에서 공통·영역별 규칙을 읽고, 기존 CLAUDE.md 상세 내용은 삭제·축약 없이 유지한다.
- [x] 기존 스킬의 LLD·ERD 확인, 구현·테스트 규칙, 리뷰 체크리스트를 보존하면서 Codex 기본 구현과 정확한 diff 범위를 지원한다.
- [x] staged/unstaged/untracked, 공백 경로, 삭제, rename, 명시 base의 커밋된 변경을 감지한다.
- [x] domain 변경은 API 테스트까지 선택하고 문서만 변경하면 Gradle을 실행하지 않는다.
- [x] 잘못된 ref, 규칙 위반, 테스트 명령 실패가 비영으로 반환된다.
- [x] Codex 훅의 정상/차단/잘못된 입력/Stop 재진입을 회귀 테스트한다. 기존 명령 가드도 통과한다.
- [x] 작업 브랜치의 최초 편집을 차단하고 동일 세션×브랜치 ack만 통과시킨다. 다른 세션·유사 브랜치·보호 브랜치는 기존 ack를 공유하거나 우회하지 못한다.
- [x] TOML 파싱, 스킬 frontmatter, 로컬 문서 링크 및 git diff --check가 통과한다.
- [x] CI가 공통 정적 검사와 하네스 회귀 테스트를 실행하도록 연결된다.
- [x] 공식 문서·공개 사례·설정 적용 및 한계를 운영 가이드에 기록한다.
- [x] 셸 리다이렉션·직접 편집·인라인 실행은 차단하며, 조회·검사·정확한 ack·저장소 밖 임시 출력은 허용한다.
- [x] 다른 worktree·중첩 저장소·심볼릭 링크·이동 대상의 경로 이탈을 ack 이전에 차단한다.
- [x] 하네스 변경의 기본 verify가 회귀 테스트를 실행하고 실패 종료 코드를 전달한다. 정적/모듈 전용 경로는 재귀하지 않는다.
- [x] 실제 CLI가 fixture의 Bash/patch를 차단하고 Stop 실패·재진입을 처리한다. 로컬 환경의 미준비 상태와 fixture 통과를 구분한다.
- [x] write 스킬을 추가하고 issue/pr에서 참조한다. 가독성 개선이 사실·인수조건·검증 결과를 바꾸지 않도록 규정한다.

## 8. 영향 범위 / 마이그레이션

새 Codex 세션에서 프로젝트 설정을 로드하고 /hooks에서 새 훅을 검토·신뢰한다. 세션 최초 apply_patch에서 현재 작업을 확인하고 필요하면 안내된 `.codex/state` ack를 생성한다. 기존 Figma MCP 환경변수 이름은 그대로 유지한다. Claude의 CLAUDE.md, 공유 설정과 스크립트는 그대로 유지한다. 애플리케이션/DB 배포는 없다.

## 9. 미결정 사항 (Open Questions)

없음. 훅 신뢰와 사용자별 모델·권한 선택은 로컬 운영 설정이다.

## 10. 참고

[ADR-0022](../adr/ADR-0022-codex-primary-harness.md), [운영 가이드](../harness/codex.md)

## 검증 기록 (2026-09-07)

- 신규 하네스 회귀 테스트 18건, 기존 Bash 가드 68건, Claude 브랜치 가드 11건 통과.
- TOML/훅 구조, CI YAML, 스킬 5개 frontmatter, 변경 Markdown 17개 로컬 링크, Bash 문법, git diff --check 통과.
- backend 하위 디렉터리에서 config에 등록된 PreToolUse/Stop 명령에 공식 입력 형식을 전달하는 smoke test 통과. 실제 Codex 세션의 훅 신뢰/발화 검증은 /hooks 설정 후 확인해야 한다.
- verify.sh는 이번 변경에 애플리케이션 코드가 없음을 확인하고 Gradle/npm을 생략했다. 스크립트 테스트는 임시 Git 저장소와 Gradle 실행 대역을 사용하며 실제 애플리케이션 테스트 통과를 의미하지 않는다.
- CI 구성은 로컬 문법·동작 검증을 마쳤으며 GitHub Actions 원격 실행은 아직 하지 않았다.

## 후속 검증 기록 (2026-09-08)

- 공통 verify로 신규/확장 회귀 32건, 기존 Bash 68건, Claude 브랜치 11건 통과. 총 111건.
- 실제 Codex CLI 0.153.4 + 로컬 Responses fixture에서 훅 발견·신뢰, Bash/patch 차단, Stop 실패 전달·재진입 통과. 실제 모델 API 호출 없음.
- 현재 feature/549 linked worktree의 doctor는 NOT READY다. primary checkout에 새 훅 정의가 없어 로드되지 않는 것을 실제 hooks/list와 OpenAI의 worktree 테스트로 확인했다. 머지 후 원본 checkout 갱신 및 /hooks 신뢰가 필요하다.
- 이전 커밋 8f929ee의 원격 harness/backend/admin CI는 통과했다. 이 기록을 새 커밋의 CI 결과로 재사용하지 않는다.
