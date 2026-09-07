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

루트/backend/admin 지침, 공통 스킬 5개, .codex/config.toml, 검증 및 Codex 훅 어댑터, 하네스 회귀 테스트·CI, 운영 가이드가 대상이다. 파일 수는 많지만 지침 이동과 실행 경로 연결이 하나의 목적이므로 함께 검토한다. 애플리케이션 동작, 개인 설정, 세션 기록, 배포 설정은 변경하지 않는다.

## 3. 인터페이스 / API

- `bash scripts/harness/verify.sh [--base REF] [--static-only]`: 변경 범위 규칙 검사, 기본 실행은 영향 모듈 컴파일/테스트와 admin lint/build도 포함한다.
- `bash scripts/harness/run-module-tests.sh [api|domain|all|모듈 내 파일 경로]`: 호환 진입점. 인자가 없으면 작업 트리 기준 자동 선택한다.
- `python3 scripts/harness/codex-hooks.py`: stdin의 Codex hook_event_name에 따라 PreToolUse/Stop을 처리한다.
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

잘못된 Git ref/입력, 정적 규칙 위반, Gradle/npm 실패를 통과로 바꾸지 않는다. 사용자 작업이 있는 폴더에서 stash/reset/switch로 작업을 옮기지 않고 별도 worktree를 사용한다. 필요한 도구나 서비스가 없으면 실행하지 못한 검증을 구분해 보고한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 루트/backend/admin AGENTS.md만으로 공통·영역별 규칙을 읽을 수 있고 Claude 진입점은 이를 참조한다.
- [x] 기존 스킬의 Claude 구현 전제·강제 LLD 중단·중복 승인·미커밋 diff 누락을 해소한다.
- [x] staged/unstaged/untracked, 공백 경로, 삭제, rename, 명시 base의 커밋된 변경을 감지한다.
- [x] domain 변경은 API 테스트까지 선택하고 문서만 변경하면 Gradle을 실행하지 않는다.
- [x] 잘못된 ref, 규칙 위반, 테스트 명령 실패가 비영으로 반환된다.
- [x] Codex 훅의 정상/차단/잘못된 입력/Stop 재진입을 회귀 테스트한다. 기존 명령 가드도 통과한다.
- [x] 작업 브랜치의 최초 편집을 차단하고 동일 세션×브랜치 ack만 통과시킨다. 다른 세션·유사 브랜치·보호 브랜치는 기존 ack를 공유하거나 우회하지 못한다.
- [x] TOML 파싱, 스킬 frontmatter, 로컬 문서 링크 및 git diff --check가 통과한다.
- [x] CI가 공통 정적 검사와 하네스 회귀 테스트를 실행하도록 연결된다.
- [x] 공식 문서·공개 사례·설정 적용 및 한계를 운영 가이드에 기록한다.

## 8. 영향 범위 / 마이그레이션

새 Codex 세션에서 프로젝트 설정을 로드하고 /hooks에서 새 훅을 검토·신뢰한다. 세션 최초 apply_patch에서 현재 작업을 확인하고 필요하면 안내된 `.codex/state` ack를 생성한다. 기존 Figma MCP 환경변수 이름은 그대로 유지한다. Claude의 공유 설정과 스크립트는 호환 경로로 유지한다. 애플리케이션/DB 배포는 없다.

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
