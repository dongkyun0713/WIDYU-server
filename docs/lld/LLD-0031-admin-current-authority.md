# LLD-0031: 관리자 인증의 현재 권한·활성 상태 검증

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #605 |
| 관련 ADR | ADR-0002 |
| 작성자 | Codex |
| 작성일 | 2026-09-14 |

## 1. 목적 / 배경

관리자 refresh는 일반 회원의 유효한 refresh token으로도 ADMIN 토큰을 생성했다. 관리자 로그인·재발급·기존 관리자 토큰 사용 시 DB의 현재 역할과 ACTIVE 상태를 확인한다.

## 2. 범위

- 변경 모듈: widyu-api. 관리자 인증 서비스, 관리자 검증기, JWT 필터 및 회귀 테스트.
- 일반 회원 세션 버전·로그아웃·탈퇴 폐기는 #603, WebSocket 목적지 인가는 #602에서 다룬다.
- ADR: N/A — 기존 ADR-0002의 역할 인가를 현재 DB 상태까지 검증하는 보완이며 새 인증 방식 도입은 없다.

## 3. 인터페이스 / API

- POST `/api/v1/auth/admin/login`: 기존 email/password 요청, memberId/accessToken 응답, refresh 쿠키 유지.
- POST `/api/v1/auth/admin/refresh`: 기존 admin_refresh_token 쿠키·응답 유지.
- `/api/v1/admin/**`: 필터에서 현재 DB 관리자 여부 확인 후 기존 hasRole 규칙 적용.
- ADMIN claim은 어느 REST 경로에서 사용하든 검증한다. USER claim을 DB 역할만으로 ADMIN으로 승격하지 않는다.

## 4. 데이터 모델

ERD-0001 Member.role/status를 사용한다. 신규 필드·마이그레이션은 없다.

## 5. 처리 흐름

1. 로그인은 계정·비밀번호 확인 후 실제 Member 역할·상태를 검증하고 토큰 발급.
2. refresh는 Redis 토큰 검증 후 memberId로 DB 조회. ADMIN/ACTIVE인 경우에만 발급.
3. JWT 필터는 서명·만료 확인 후 ADMIN claim이면 같은 검증기로 DB 조회. 승인 후에만 컨텍스트 설정.
4. 검증 실패 시 컨텍스트를 비우고 필터 체인을 중단. DB 조회 실패도 권한을 부여하지 않는다.
5. 검증기 조회는 readOnly 트랜잭션. 기존 로그인·재발급 트랜잭션 유지.

## 6. 예외 / 에러 처리

- 잘못된 이메일·비밀번호, 비어 있거나 유효하지 않은 refresh는 기존 오류 유지.
- DB 회원 미존재, ADMIN 아님, ACTIVE 아님: FORBIDDEN(403).
- 필터의 code/message와 관리자 성공 응답의 기존 래퍼 없는 DTO 유지.

## 7. 인수조건

- [x] 일반 회원·비활성 관리자 refresh 거절.
- [x] 비활성 관리자 로그인 거절, 활성 관리자 로그인·refresh 정상.
- [x] 기존 ADMIN 토큰으로 강등·정지·삭제 후 요청하면 403이며 컨트롤러에 도달하지 않음.
- [x] 실제 SecurityFilterChain에서 USER 토큰은 관리자 API 403, 정상 ADMIN은 성공.
- [x] 임시 토큰 API의 필터 제외 계약 유지.
- [x] 관리자 인증 Swagger에 역할·상태 오류 명시.
- [x] 관련 단위·MVC 테스트와 변경 범위 검증 통과.

## 8. 영향 범위 / 마이그레이션

ADMIN 토큰 요청마다 DB 조회 1회 추가. 잘못 발급된 ADMIN 토큰도 실제 일반 회원이면 거절한다. 진행 중인 요청을 소급 취소하지 않고 다음 요청에서 현재 상태를 검증한다. 전체 기기 폐기·경합 제어는 #603 범위다.

## 9. 미결정 사항 (Open Questions)

없음. 현재 관리자 권한과 활성 상태를 확인한다는 사용자 요청을 구현한다.

## 10. 참고

- [인증 ADR](../adr/ADR-0002-auth-jwt-family-access.md)
- [ERD](../erd/ERD-0001-initial-domain.md)

## 11. 검증 / 자체 검수

2026-09-14 변경 범위 하네스 통과: 정적 검사·compileJava·API 549건 중 543건 통과, Redis 미실행 6건, 실패 0건. 관리자·필터·임시 토큰 집중 회귀 31건 통과. 최초 전체 검사의 테스트 패키지 경계 위반은 MVC 통합 테스트를 integration 패키지로 이동한 뒤 재검증했다. 기존 아키텍처 검사 규칙은 유지했다.

자체 review: 인수조건·역할/상태 실패 동작·API 호환을 확인해 코드 검수 APPROVE. 사람의 출시 승인이나 운영 검증을 뜻하지 않는다. 실제 DB 부하 및 운영 두 계정 호출은 미실행이다.
