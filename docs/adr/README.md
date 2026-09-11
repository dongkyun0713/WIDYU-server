# ADR (Architecture Decision Records)

중요한 아키텍처/기술 의사결정을 기록한다. 새 ADR은 `../templates/adr.md`를 복사해 `ADR-XXXX-<slug>.md`로 만든다.

ADR은 프로젝트 전체 설명서가 아니다. 전체 도메인 구조는 ERD, API 계약은 Swagger, 구현 세부는 LLD가 담당한다.
ADR은 나중에 "왜 이 방식을 선택했는가"를 추적해야 하는 **결정**만 기록한다.

**ADR을 쓰는 기준**: "이 결정을 바꾸면 많은 파일이 바뀐다" 또는 "여러 기능에 반복 적용되는 규칙"이면 ADR 작성.
구현하다가 "이걸 어떻게 할까?" 고민이 30분 이상 걸리면 ADR 후보. 단순히 어떻게 만드냐만 결정하면 LLD에 포함.

## 작성 기준

### ADR이 필요한 경우
- 여러 도메인/모듈에 반복 적용되는 규칙
- 되돌리기 어려운 기술 선택 또는 데이터 모델 선택
- 보안, 인증, 결제, 알림, 외부 연동처럼 실패 비용이 큰 결정
- 대안이 2개 이상이고 장단점 비교가 필요한 결정
- 운영/배포/테스트 전략처럼 PR마다 반복해서 판단해야 하는 결정

### ADR이 필요 없는 경우
- 단순 CRUD endpoint 추가
- DTO 필드명 변경, Swagger 문구 변경
- 특정 기능 내부의 처리 순서만 정하는 경우
- 이미 Accepted ADR의 규칙을 그대로 적용하는 구현

이 경우에는 LLD, Swagger, ERD, policy-checklist 중 더 맞는 문서에 기록한다.

## 커버리지 원칙

ADR 커버리지는 100%를 목표로 하지 않는다. 대신 다음 영역은 반드시 ADR 또는 명시적 N/A 사유를 둔다.

| 영역 | 문서화 기준 |
| --- | --- |
| 기반 구조 | 멀티모듈, DB 규칙, 공통 페이징, 미디어 업로드처럼 전역 규칙이면 ADR |
| 인증/인가 | JWT, 가족 접근 제어, 소셜 로그인 검증처럼 보안 경계가 있으면 ADR |
| 외부 연동 | FCM, Toss Payments, Apple OAuth처럼 장애·검증·재시도 정책이 필요하면 ADR |
| 비동기/실시간 | WebSocket, Redis TTL, 이벤트 리스너, 스케줄러 정책이 있으면 ADR |
| 테스트/배포 | 반복 적용되는 테스트 더블, CI/CD, 배포 전략은 ADR |

기존 구현을 백필할 때는 모든 PR을 되짚지 않는다. 결제, 인증, 실시간 위치, 알림처럼 잘못 바꾸면 장애나 보안 문제가 생기는 영역부터 작성한다.

## 백필 후보

| 우선순위 | 후보 | 이유 |
| --- | --- | --- |
| 높음 | FCM 발송/재시도 전략 | 현재 outbox/retry가 없어 장애 시 알림 유실 가능 |
| 높음 | 포인트·결제 상태 전략 | 금전성 상태 전이와 외부 PG 연동 정책 |
| 중간 | Apple id_token 검증 전략 | 현재 payload 파싱만 수행, 서명·claim 검증 정책 필요 |
| 중간 | Redis 활용 기준 | 위치, 안전구역 알림, OAuth state 등 TTL 데이터 기준 |
| 중간 | 테스트 더블 전략 | JPA Mock/Fake/MockMvc 범위 반복 판단 방지 |
| 낮음 | WebSocket 인증 전략 분리 | ADR-0002 범위가 넓어 별도 ADR로 분리 가능 |
| 낮음 | 배포 전략 | Docker, EC2, GitHub Actions CD 흐름 기록 |

| 번호 | 제목 | 상태 | 날짜 |
| --- | --- | --- | --- |
| [ADR-0024](ADR-0024-development-observability-stack.md) | 개발 서버 오류 관측에 Grafana Loki 사용 | Accepted | 2026-09-09 |
| [ADR-0001](ADR-0001-multi-module-structure.md) | widyu-api + widyu-domain 멀티모듈 구조 채택 | Accepted | 2025-08-01 |
| [ADR-0002](ADR-0002-auth-jwt-family-access.md) | 인증/인가 전략 — JWT + @ValidateFamilyAccess AOP | Accepted | 2026-07-05 |
| [ADR-0003](ADR-0003-db-entity-design.md) | DB/엔티티 설계 기준 (PK, Enum, FK, soft delete) | Accepted | 2026-07-05 |
| [ADR-0004](ADR-0004-media-upload-strategy.md) | 미디어 업로드 전략 — 서버 직접 S3 업로드 + @Async | Accepted | 2026-07-05 |
| [ADR-0005](ADR-0005-cursor-pagination.md) | 커서 기반 페이징 전략 | Accepted | 2026-07-05 |
| [ADR-0006](ADR-0006-medicine-search-fallback-fulltext.md) | 약품 검색 전략 — 자체 DB 우선 조회 + 외부 API fallback + FULLTEXT | Accepted | 2026-07-05 |
| [ADR-0007](ADR-0007-location-event-visit-verification.md) | 실시간 위치 이벤트 기반 방문인증 처리 | Accepted | 2026-07-10 |
| [ADR-0008](ADR-0008-heart-rate-ai-transaction-boundary.md) | 심박 AI 판정과 저장 트랜잭션 경계 분리 | Accepted | 2026-07-20 |
| [ADR-0009](ADR-0009-safe-zone-alert-deduplication.md) | 안전구역 이탈 알림 원자 중복 차단 | Accepted | 2026-07-21 |
| [ADR-0010](ADR-0010-album-video-failure-compensation.md) | 앨범 영상 처리 실패 보상 삭제 정책 | Accepted | 2026-07-21 |
| [ADR-0011](ADR-0011-album-notification-side-effect-isolation.md) | 앨범 알림 부수효과 격리 정책 | Accepted | 2026-07-21 |
| [ADR-0012](ADR-0012-payment-cancel-idempotency.md) | 부분 취소 멱등성 및 직렬화 정책 | Accepted | 2026-07-25 |
| [ADR-0013](ADR-0013-heart-rate-personalized-ai-contract.md) | 개인화 심박 AI 단건 계약 연동 | Accepted | 2026-07-26 |
| [ADR-0015](ADR-0015-album-presigned-multipart-upload.md) | 앨범 미디어 Presigned Multipart 직접 업로드 | Accepted | 2026-07-27 |
| [ADR-0018](ADR-0018-claude-code-sandbox-not-adopted.md) | Claude Code 샌드박스 미도입 — 비밀 파일 차단과 테스트 실행의 트레이드오프 | Accepted | 2026-08-20 |
| [ADR-0019](ADR-0019-goal-home-query-pipeline.md) | 목표 홈 주간 통계 기간 벌크 조회 | Accepted | 2026-08-20 |
| [ADR-0020](ADR-0020-family-access-dual-path.md) | 가족 접근 인가 이중 경로를 인정하고 정적 검증으로 강제 | Accepted | 2026-08-20 |
| [ADR-0021](ADR-0021-branch-guard-pretooluse.md) | 이슈·브랜치 없는 코드 수정을 PreToolUse 훅으로 차단 | Accepted | 2026-08-25 |
| [ADR-0022](ADR-0022-codex-primary-harness.md) | Codex 주 구현 에이전트와 공통 하네스 | Accepted | 2026-09-07 |
