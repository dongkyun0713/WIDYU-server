# LLD-0060: 비활성 회원의 기존 토큰 차단

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #603 (일부) |
| 관련 ADR | ADR-0002 |
| 작성자 | 김동균 (Claude Code) |
| 작성일 | 2026-09-28 |

## 1. 목적 / 배경

JWT 인증 필터는 토큰 서명과 만료만 확인하고 회원 상태는 보지 않는다. 그래서 탈퇴하거나 관리자가 정지한(`Status.INACTIVE`) 회원도, 이미 받은 access token이 만료될 때까지 모든 API를 쓸 수 있다. 관리자 토큰만 요청마다 DB에서 상태를 확인한다(`AdminAccessValidator`). WebSocket 핸드셰이크도 상태를 확인하지 않는다.

## 2. 범위

### In scope

- 변경 모듈: widyu-domain(`ErrorCode`), widyu-api
- REST: `USER` 역할 토큰이면 요청마다 회원이 `ACTIVE`인지 확인하고, 아니면 401 `AUTH_4020`을 반환한다. `ADMIN`은 기존 `AdminAccessValidator`가 이미 같은 확인을 한다.
- WebSocket: 핸드셰이크(JWT 헤더·일회용 WS 토큰 두 경로)에서 회원이 `ACTIVE`가 아니면 연결을 거부한다.

### Out of scope (#603에 남김)

- 이미 열려 있는 WebSocket 세션의 즉시 종료
- 로그인·토큰 재발급 시점의 상태 확인. 발급돼도 위 필터에서 막힌다.
- 로그아웃·비밀번호 변경 시 다른 기기 토큰 폐기(토큰 버전 등)
- 정지와 탈퇴 상태 구분

## 3. 인터페이스 / API

모든 인증 API 공통으로, 비활성 회원 토큰이면 다음을 반환한다.

```json
{ "code": "AUTH_4020", "message": "탈퇴했거나 이용이 정지된 계정입니다." }
```

HTTP 401. 필터가 직접 쓰는 응답 형식은 기존 `writeErrorResponse`와 같다.

## 4. 데이터 모델

변경 없음. `MemberRepository.existsByIdAndStatus(id, status)` 조회를 추가한다(PK 조건).

## 5. 처리 흐름

1. `JwtAuthenticationFilter`가 access token을 검증한다(기존).
2. 역할이 `ADMIN`이면 `AdminAccessValidator.validateMemberId`(기존), `USER`면 `ActiveMemberValidator.validate(memberId)`를 호출한다. 활성이 아니면 `BusinessException(INACTIVE_MEMBER)` → 401 응답.
3. `JwtHandshakeInterceptor`는 memberId를 얻은 뒤 `ActiveMemberValidator.isActive`가 false면 `false`를 반환해 핸드셰이크를 거부한다.

## 6. 예외 / 에러 처리

- 새 에러 코드: `INACTIVE_MEMBER(401, "AUTH_4020", "탈퇴했거나 이용이 정지된 계정입니다.")`
- 회원 행이 없으면 활성이 아닌 것으로 본다.

## 7. 인수조건 (Acceptance Criteria)

- [x] `USER` 토큰의 회원이 `ACTIVE`가 아니면 REST 요청이 401 `AUTH_4020`으로 거부되고 컨트롤러까지 가지 않는다.
- [x] `USER` 토큰의 회원이 `ACTIVE`면 기존처럼 인증된다.
- [x] `ADMIN` 토큰은 기존 관리자 확인만 거치고 추가 조회를 하지 않는다.
- [x] WebSocket 핸드셰이크는 비활성 회원이면 JWT·WS 토큰 경로 모두 거부한다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- `USER` 토큰 요청마다 `SELECT ... WHERE id = ? AND status = ?`가 한 번 늘어난다(PK). 관리자 토큰은 이미 요청마다 조회하고 있다.
- DDL 변경 없음.
- 앱은 `AUTH_4020`을 로그아웃 처리해야 한다. 기존에도 401이면 재로그인으로 보내므로 추가 작업은 없을 것으로 보지만 앱 확인이 필요하다.

## 9. 미결정 사항 (Open Questions)

- 없음. 범위 밖 항목은 #603에서 이어서 정한다.

## 10. 참고

- LLD-0031(관리자 현재 권한 확인)
