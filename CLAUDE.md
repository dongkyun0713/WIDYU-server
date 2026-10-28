# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 도메인 상세 로직·비즈니스 플로우·DB·외부 연동·보안·**테스트 작성 규칙**·백엔드 코딩 주의사항은 [backend/CLAUDE.md](backend/CLAUDE.md)를, Admin 대시보드는 [admin/CLAUDE.md](admin/CLAUDE.md)를 참조하세요.
>
> 이슈·커밋·PR·리뷰·구현 작업은 항상 [.agents/skills](.agents/skills) 아래 해당 스킬(`issue`, `commit`, `pr`, `review`, `implement`)의 절차·컨벤션을 먼저 확인하고 그대로 따르세요. 예: 이슈 → [issue/SKILL.md](.agents/skills/issue/SKILL.md), PR → [pr/SKILL.md](.agents/skills/pr/SKILL.md) (PR 범위 원칙 포함), 커밋 → [commit/SKILL.md](.agents/skills/commit/SKILL.md).

## Project Overview

WIDYU는 시니어(부모)와 보호자(자녀·가족)가 사진·영상을 공유하는 Spring Boot 플랫폼입니다.
포인트 기반 프리미엄 콘텐츠, WebSocket 실시간 위치 추적, 건강 목표 관리 기능을 포함합니다.

**Tech Stack**: Java 21, Spring Boot 3.3.5, MySQL, Redis, WebSocket (STOMP), QueryDSL, JPA/Hibernate

## Development Commands

```bash
./gradlew build                                          # 전체 빌드
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test                                           # 전체 테스트 (모듈별: :backend:widyu-api:test)
./gradlew test --tests "PhoneNumberUtilTest"             # 단일 테스트
./gradlew compileJava                                    # QueryDSL Q-class 재생성 (엔티티 변경 후 필수)
```

- **Docker**: `./scripts/docker/{dev,prod}-up.sh`·`{dev,prod}-down.sh`, 로그 `./scripts/docker/logs.sh {dev|prod} [service]`
- **AI 심박 서비스**: `docker run -p 5000:5000 ryuchanghoon/widyu-ai-ver7:latest`
- **CI/CD** (`.github/workflows/`): PR·feature push → `widyu-api` 빌드·테스트 / develop push → EC2 배포 (DB·Redis 유지)

## Application Architecture

### Multi-Module Structure
- **`widyu-api`**: 실행 JAR — 컨트롤러, 서비스, 리포지토리, 설정. 진입점 `com.widyu.WidyuApiApplication`
- **`widyu-domain`**: 라이브러리 JAR (`bootJar` off) — JPA 엔티티(`@Entity`, `@RedisHash`), QueryDSL Q-클래스 생성 위치. 리포지토리 없음

**핵심 규칙**: 엔티티는 `widyu-domain`, 리포지토리는 `widyu-api`.

### DDD Layered Structure
```text
{domain}/
├── controller/         # REST 엔드포인트
│   └── docs/           # Swagger 문서 인터페이스 (컨트롤러와 분리)
├── application/        # 비즈니스 로직 (*Service, *Facade, strategy/)
├── repository/
├── dto/                # request/ · response/
└── validator/
```

### Key Architectural Patterns
- **Facade** — 여러 서비스를 조합하는 복잡한 오퍼레이션 (`AlbumFacade`, `HealthScheduleFacade`)
- **Strategy + Factory** — OAuth 제공자별 로그인 처리 (`SocialLoginStrategyFactory`)
- **AOP** — `@ValidateFamilyAccess`로 가디언-시니어 접근 권한 자동 검증 (`FamilyAccessAspect`가 `seniorId`를 추출해 `FamilyMembershipRepository`로 관계 확인, ADR-0002)
- **Event-Driven** — `@EventListener`로 도메인 간 결합 제거 (앨범 이벤트 → FCM 알림)
- **QueryDSL** — 복잡한 조건 쿼리, `./gradlew compileJava`로 Q-클래스 재생성
- **WebSocket STOMP** — JWT 핸드셰이크 인터셉터로 인증, `SimpMessagingTemplate`으로 브로드캐스트, 위치는 Redis 저장
- **Redis 임시 데이터** — `@RedisHash` + `@TimeToLive` (인증코드, 임시토큰, OAuth state 등)

## AI 하네스 워크플로우

Java 파일 수정 시 `on-file-edit.sh` 훅이 `scripts/harness/validate-java-rules.sh`로 아래 **훅이 차단하는 규칙 6개**를 검사합니다. 라인 단위 규칙은 HEAD 대비 **추가된 라인만** 봅니다(기존 코드의 위반은 무관한 수정을 막지 않습니다. 파일 전체 검사는 `HARNESS_FULL_FILE=1`).

응답 종료 시에는 `on-stop.sh`가 정적 규칙 → `compileJava` → Codex 시맨틱 검수를 순서대로 실행합니다. 같은 diff는 두 번 검수하지 않고, `auth`·`pay`·`global/security` 변경은 크기와 무관하게 항상 Codex 검수를 거칩니다. 훅 입력의 `cwd`가 같은 저장소의 다른 worktree면 그 worktree의 미커밋 변경을 봅니다.

**push·PR 게이트** (LLD-0093): 커밋된 변경과 서브에이전트 작업은 종료 훅이 놓칠 수 있어, `git push`·`gh pr create` 직전에 `pre-bash-guard.sh`가 Codex 검수 기록을 확인합니다. 브랜치가 `upstream/develop`(없으면 `origin/develop`)과의 merge-base 이후 `backend/*/src/main/java/`를 바꿨다면 HEAD에 대한 검수 기록이 있어야 합니다.

- 커밋을 마친 뒤 그 작업 트리에서 `bash scripts/harness/codex-review.sh --base "$(git merge-base upstream/develop HEAD)"`를 실행합니다. 판정은 `<git-dir>/widyu-codex-review/<HEAD SHA>`에, 감사 이벤트는 원본 체크아웃의 `.claude/audit/`에 남습니다. 커밋을 더하면 HEAD가 바뀌어 다시 검수해야 합니다
- 기록 없음·BLOCKER는 차단, Codex 실행 실패(FAILED)는 경고 후 통과합니다. BLOCKER를 검토 끝에 수용할 때만 `WIDYU_CODEX_ACCEPT="<사유>" git push ...`로 통과시키며 사유가 감사 로그에 남습니다
- 서브에이전트는 출력 없이 600초가 지나면 중단됩니다. Codex 검수·`verify.sh`·전체 Gradle 테스트처럼 긴 명령은 백그라운드로 실행하고 출력 파일을 확인합니다
- 훅은 원본 체크아웃의 스크립트로 실행되므로, 이 게이트는 원본 체크아웃이 최신 develop을 받은 뒤부터 적용됩니다

작업 순서:

1. 관련 도메인 문서 확인 (backend/CLAUDE.md → 해당 도메인 섹션)
2. 변경 계획 + 완료 조건 작성 (어떤 파일·왜, 그리고 "무엇이 되면 done"인지 검증 가능한 성공 기준으로)
3. 코드 수정 (훅이 규칙 자동 검사)
4. 테스트: `bash scripts/harness/run-module-tests.sh`
5. 엔티티 변경 시 `./gradlew compileJava`

## 코드 작성 원칙

훅이 차단하는 규칙 — `validate-java-rules.sh`의 규칙 1~6과 일대일 대응합니다.

1. **삼항 연산자 금지** — 조건 분기는 `if/else` 또는 early return으로 작성한다
2. **DTO 팩토리 메서드** — 서비스에서 `new DTO(...)` 직접 생성 금지. DTO의 `from()`/`of()` 정적 팩토리를 호출한다. 중첩 타입 생성(`new XResponse.Item(...)`)도 대상이다
3. **Controller → Repository 직접 접근 금지** — Service를 경유한다
4. **`@Entity`는 widyu-domain에만** 둔다
5. **Repository는 widyu-api에만** 둔다
6. **`@Async` 메서드에 `@Transactional` 필수** — 새 스레드는 호출자 트랜잭션을 전파받지 못한다

훅이 검사하지 않는 권장 규칙 — 리뷰에서 확인합니다.

- **Early return** — 조건이 맞지 않으면 일찍 반환해 중첩을 줄인다

<!-- 단일 책임·의미 있는 이름 항목은 검증 기준이 없어 제거했습니다 (#486). 되살릴 때는
     "PR 리뷰에서 위반을 가리킬 수 있는가" 기준을 통과하는 문장으로 다시 쓰세요. -->

# Compact instructions

압축할 때 아래를 우선 보존합니다.

- 현재 작업의 LLD·ADR 인수조건, 미해결 Codex BLOCKER 지적
- 확정한 설계와 그 근거 (기각한 대안 포함)
- 변경한 파일 경로와 남은 작업

탐색 흔적, 막다른 길, 중복된 도구 출력은 버려도 됩니다.

압축 후 백엔드 코드를 계속 다루면 `backend/CLAUDE.md`를 다시 읽으세요. nested CLAUDE.md는
루트와 달리 자동 재주입되지 않아, 참조 문장만 남고 도메인 규칙·테스트 작성 규칙은 사라집니다.
