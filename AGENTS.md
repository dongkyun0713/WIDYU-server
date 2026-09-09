# WIDYU 작업 지침

WIDYU는 시니어와 보호자가 사진·영상을 공유하는 플랫폼이다. 포인트 결제, 실시간 위치, 건강 목표를 포함한다. Java 21 / Spring Boot 3.3.5 / MySQL / Redis를 사용한다. Codex를 기본 구현 에이전트로 사용한다.

## 지침 지도

- 공통 규칙의 원본은 이 파일이다. `CLAUDE.md`는 Claude 호환 진입점이다.
- **backend 수정 전 [backend/AGENTS.md](backend/AGENTS.md)**, **admin 수정 전 [admin/AGENTS.md](admin/AGENTS.md)**를 읽는다. 루트에서 시작한 세션이 하위 지침을 모두 자동 로드한다고 가정하지 않는다.
- 관련 [ADR](docs/adr/README.md), [LLD](docs/lld/README.md), [ERD](docs/erd/ERD-0001-initial-domain.md)를 필요한 범위만 읽는다.
- 하네스 설정·검증·Claude 호환 동작은 [운영 가이드](docs/harness/codex.md)를 읽는다.
- 이슈·PR·설계·운영 문서를 작성하거나 다듬을 때는 [write 스킬](.agents/skills/write/SKILL.md)을 적용한다.
- 사용자 요청이 저장소 지침·스킬보다 우선한다. 적용 경로의 더 구체적인 지침을 따른다.

## 작업 흐름

`Team Discussion → ADR → LLD → Test Scenario → Implementation → Review → PR/CI/Deploy`

1. `git status --short --branch`, 현재 diff, 관련 이슈·설계를 확인한다. 기존 사용자 변경을 보존한다.
2. 새 개발 작업은 [issue 스킬](.agents/skills/issue/SKILL.md)로 관련 이슈와 작업 브랜치를 준비한다. 이미 해당 이슈의 브랜치에서 이어가는 작업은 재생성하지 않는다. main/master/develop에서 직접 수정하지 않는다. 다른 작업의 미커밋 변경이 있으면 별도 worktree를 사용한다.
3. 변경 범위와 검증 가능한 완료 조건을 정하고 [implement 스킬](.agents/skills/implement/SKILL.md)을 따른다. ADR/LLD 필요 여부는 각각의 README 기준을 따른다. 필요한 문서는 직접 초안을 작성한다. 작은 변경에는 LLD N/A 사유를 남길 수 있다.
4. 합의된 범위 안의 구현·테스트·수정은 계속 진행한다. 미결정 항목 중 정책·API 계약처럼 사용자 결정이 필요한 것만 질문하고 독립 작업은 진행한다.
5. `bash scripts/harness/verify.sh`로 검사하고 [review 스킬](.agents/skills/review/SKILL.md)로 인수조건을 검수한다. 이미 실행한 동일 범위의 검증은 불필요하게 반복하지 않는다.
6. 커밋 요청이 포함되면 [commit 스킬](.agents/skills/commit/SKILL.md), PR 생성·갱신 요청이면 [pr 스킬](.agents/skills/pr/SKILL.md)을 적용한다. 이미 받은 권한을 다시 묻지 않는다. 머지·배포는 별도 요청 범위에 따른다.

## 구조와 명령

- `backend/widyu-domain`: JPA/Redis 엔티티와 QueryDSL Q 클래스, 라이브러리 JAR.
- `backend/widyu-api`: Controller / Service / Facade / Repository / 설정, 진입점 `com.widyu.WidyuApiApplication`.
- `admin`: 별도 React + TypeScript SPA.
- 도메인 패키지는 `controller/docs`, `application`, `repository`, `dto/request`, `dto/response`, `validator`로 나눈다.

저장소 루트에서 실행한다.

| 목적 | 명령 |
| --- | --- |
| 변경 범위 검증 | `bash scripts/harness/verify.sh` |
| 브랜치 전체 검증 | `bash scripts/harness/verify.sh --base "$(git merge-base origin/develop HEAD)"` |
| 정적 검사만 | `bash scripts/harness/verify.sh --static-only` |
| API 테스트 | `./gradlew :backend:widyu-api:test` |
| Domain + API 테스트 | `bash scripts/harness/run-module-tests.sh domain` |
| 단일 테스트 | `./gradlew :backend:widyu-api:test --tests 'com.widyu.<패키지>.<테스트클래스>'` |
| Q 클래스 재생성 | `./gradlew compileJava` (엔티티 변경 시 필수) |
| 로컬 실행 | `./gradlew :backend:widyu-api:bootRun --args='--spring.profiles.active=local'` |
| 전체 빌드 | `./gradlew build` |
| 하네스 회귀 검사 | `python3 -m unittest discover -s scripts/harness -p 'test_*.py'` |
| Codex 훅 로드·신뢰 진단 | `python3 scripts/harness/doctor.py` |
| 실제 CLI 훅 통합 검사 | `python3 scripts/harness/smoke_codex_client.py` (로컬 응답 서버 사용) |

소스·문서 수정은 대상 worktree에서 `apply_patch`로 수행한다. 셸 직접 편집과 인라인 인터프리터는 Codex 가드가 차단한다. 임시 문안은 저장소 밖 임시 경로에 리다이렉션으로 작성할 수 있다. 기존 검사 스크립트·Gradle/npm 실행은 허용한다. 다른 worktree를 만들었다면 그 작업 디렉터리로 Codex 세션을 전환한 뒤 수정한다.

## 코드 규칙

정적 검사기는 일부 패턴만 감지하므로 통과를 규칙 충족의 증명으로 보지 않는다.

- 삼항 연산자 금지. if/else 또는 early return을 사용한다.
- Service/Facade에서 DTO를 직접 new 하지 않고 from()/of() 팩토리를 사용한다.
- Controller에서 Repository를 직접 import하지 않는다.
- 엔티티는 widyu-domain, Repository/Service/Controller는 widyu-api에 둔다.
- @Async 메서드에 @Transactional을 선언한다. 프록시·파일 수명 관련 상세 규칙은 backend 지침을 따른다.
- 여러 서비스를 조합하면 Facade, 도메인 간 알림은 이벤트를 사용한다.

## Code Review Rules

- LLD 인수조건과 실제 변경 동작을 비교한다. 재현 조건·영향·파일/라인을 제시하고 취향성 제안을 결함으로 보고하지 않는다.
- 인증·가족 접근, 결제·포인트는 권한과 멱등성/동시성, 비동기·외부 호출은 트랜잭션 경계와 실패 후 상태를 확인한다.
- 상태 검증을 우선하고 외부 호출·이벤트·삭제 같은 부수효과에는 verify를 허용한다.
- 수정한 코드의 검수와 사람의 승인·배포 승인을 구분한다.

## 완료와 작업 재개

완료 시 무엇이 바뀌었는지, 실행한 검증과 결과, 미실행 이유·남은 문제를 보고한다. 테스트 실패나 도구 오류를 통과로 바꾸지 않는다. 컨텍스트 정리 시 이슈·브랜치/worktree, ADR/LLD, 결정 근거, 변경 파일, 검증 결과, 남은 작업을 보존한다. 긴 작업의 재개 기록이 필요하면 이슈/LLD를 갱신하고 개인 메모는 `.codex/RESUME.md`에 둔다. 재개 시 현재 diff와 적용 영역 AGENTS.md를 확인한다.

## 보안

시크릿을 읽어 출력하거나 커밋하지 않는다. `.env*`, 운영/secret 설정, Firebase 자격증명, pem/p8 파일을 보호한다. `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `FCM_CREDENTIALS_PATH` 및 MCP 토큰은 환경변수로 전달한다. 문서·훅·gitignore는 OS 접근 제어를 대체하지 않는다. 사용자 작업을 되돌리는 reset/clean/stash나 강제 push로 문제를 해결하지 않는다.
