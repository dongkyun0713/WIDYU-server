# LLD (Low-Level Design)

기능별 상세 설계 문서. **PR 본문의 오라클**로 사용된다. 새 LLD는 `../templates/lld.md`를 복사해 `LLD-XXXX-<slug>.md`로 만든다.

LLD는 프로젝트 전체 기능 목록이 아니다. 단순 CRUD나 작은 버그 수정까지 모두 LLD로 만들면 문서가 코드보다 빨리 낡는다.
LLD는 리뷰어와 구현 도구가 "이 구현이 맞는가"를 판단할 기준이 필요한 기능에 작성한다.

각 LLD에는 반드시 `## 미결정 사항(Open Questions)` 섹션을 둔다.

## 작성 기준

### LLD가 필요한 경우
- 새 기능이 2개 이상 도메인/모듈을 건드린다
- 인증/인가, 결제/포인트, 개인정보, 가족 접근 제어처럼 실패 비용이 크다
- WebSocket, Redis, FCM, 외부 API, 스케줄러, 이벤트 리스너가 포함된다
- 상태 전이, 멱등성, 재시도, 중복 방지, 트랜잭션 경계가 중요하다
- API 계약, DB 모델, 예외 정책, 테스트 기준을 PR 전에 합의해야 한다

### LLD가 필요 없는 경우
- 단순 조회/생성/수정/삭제 endpoint
- Swagger 예시나 문구 보정
- DTO 필드 추가처럼 영향 범위가 명확한 작은 변경
- 기존 LLD/ADR의 규칙을 그대로 적용하는 반복 구현
- 리팩터링·테스트 보강처럼 사용자 동작이 바뀌지 않는 변경

LLD가 필요 없는 PR은 PR 본문에 `LLD: N/A - <사유>`를 남긴다.

## 커버리지 원칙

현재 LLD는 복잡도가 높은 백필 우선순위 기능부터 작성한다. 전체 도메인 기능을 빠짐없이 나열하는 것은 Swagger, ERD, policy-checklist의 역할이다.

| 영역 | LLD 기준 |
| --- | --- |
| WebSocket/실시간 위치 | 필수. 인증, Redis TTL, 안전구역 이벤트가 결합됨 |
| FCM/알림 | 필수. 외부 API, 이벤트 리스너, 설정, 실패 처리 정책이 결합됨 |
| 포인트/결제 | 필수. 금전성 상태 전이, 멱등성, 환불·포인트 환수가 있음 |
| Apple 로그인 | 필수. 외부 OAuth, 토큰 검증, 계정 연동, 개인정보가 결합됨 |
| 일반 마이페이지/단순 조회 | 보통 N/A. API/Swagger와 테스트로 충분 |
| 단순 엔티티 필드 추가 | 보통 N/A. ERD/Swagger 업데이트로 충분 |

## 백필 후보

| 우선순위 | 후보 | 이유 |
| --- | --- | --- |
| 높음 | 가족 접근 제어 AOP | 보안 경계이며 여러 도메인에 반복 적용 |
| 중간 | 앨범 잠금해제/포인트 차감 | 포인트 사용 정책과 앨범 권한이 결합 |
| 중간 | 건강/복약/걷기 스케줄 알림 | 스케줄러와 FCM 이벤트 흐름이 결합 |
| 낮음 | 일반 프로필/마이페이지 | 단순 조회·수정 중심이면 N/A 가능 |

| 번호 | 제목 | 상태 | Issue |
| --- | --- | --- | --- |
| [LLD-0028](LLD-0028-social-refresh-token-field-encryption.md) | SocialAccount refreshToken 필드 암호화 | Approved | #579 |
| [LLD-0029](LLD-0029-japan-pilot-sensitive-data-safeguards.md) | 일본 실증 민감정보 보호 장치 | Approved | #595 |
| [LLD-0027](LLD-0027-development-error-observability.md) | 개발 서버 오류 관측 | Approved | #566 |
| LLD-0001 | WebSocket 실시간 위치 추적 | Approved | - |
| LLD-0002 | FCM 푸시 알림 발송 구조 | Approved | - |
| LLD-0003 | 포인트·결제 플로우 | Approved | - |
| LLD-0004 | Apple 로그인 authorization code 교환 | Approved | - |
| LLD-0005 | 약품 검색 DB 우선 조회와 외부 API fallback | Approved | - |
| LLD-0006 | 앨범 영상 업로드 비동기 처리 파이프라인 | Approved | - |
| LLD-0007 | 약 복용 홈 일자별 조회와 복용 상태 | Approved | #357 |
| LLD-0008 | 약 복용 스케줄 버전링(수정 시 과거 보존) | Approved | #380 |
| LLD-0009 | 실시간 위치 기반 건강 일정 방문인증 | Approved | #387 |
| LLD-0010 | 심박 AI 판정과 저장 트랜잭션 경계 분리 | Approved | #420 |
| LLD-0011 | 안전구역 이탈 알림 중복 차단 | Approved | #422 |
| LLD-0012 | 앨범 영상 처리 실패 보상 삭제 | Approved | #424 |
| LLD-0013 | 앨범 알림 부수효과 격리 | Approved | #426 |
| LLD-0014 | FCM 토큰 소유자 변경 처리 | Approved | #428 |
| LLD-0015 | 결제 PG 호출과 내부 반영 경계 검증 | Approved | #430 |
| LLD-0016 | 결제 승인 멱등성 보강 | Approved | #433 |
| LLD-0017 | 부분 취소 멱등성 보장 | Approved | - |
| LLD-0018 | 마이페이지 조회·명령 책임 분리 | Draft | #441 |
| LLD-0019 | 개인화 심박 이상 감지 AI 연동 | Approved | #446 |
| LLD-0021 | 앨범 Presigned Multipart 직접 업로드 | Approved | #450 |
| LLD-0024 | 목표 홈 주간 통계 조회 파이프라인 최적화 | Approved | #489, #491, #492 |
| LLD-0025 | 임시 토큰 인증 API의 액세스 토큰 필터 분리 | Approved | #522 |
| [LLD-0026](LLD-0026-codex-primary-harness.md) | Codex 기본 하네스 전환 | Review | #549 |
