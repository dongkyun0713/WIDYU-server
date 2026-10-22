# LLD-0094: Apple 로그인 이메일과 전화번호 보강의 신뢰 경계

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #805 |
| 관련 ADR | ADR-0002 (JWT 인증/인가) |
| 작성자 | Claude |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

Apple 로그인은 요청 본문의 `profile.email`을 `id_token` 이메일보다 우선했다(LLD-0004 §5-2 9단계). 이 이메일은 신규 Apple 계정의 기존 회원 매칭(`findExistingMemberByUserInfo`)과 계정 연동 임시 토큰(`socialTemporaryToken`)의 대상 회원 결정에 쓰인다. 클라이언트가 보낸 값 하나로 다른 회원에 연동될 수 있다. 같은 값이 `SocialAccount.email`에 저장되면, 나중에 다른 제공자로 로그인한 회원이 이 계정에 매칭되는 반대 방향 위험도 생긴다.

`PATCH /api/v1/auth/guardians/apple/phone-number`는 SMS 임시 토큰과 요청 본문 이메일만으로 그 이메일의 Apple 회원 전화번호를 덮어썼다. 요청자와 Apple 회원의 결합이 없고, 이미 전화번호가 있는 회원도 바뀌며, 사용한 임시 회원이 남아 재사용된다. 또 임시 토큰을 검사하기 전에 회원을 조회해 토큰 없이도 응답 코드(404/401)로 Apple 회원 이메일의 존재 여부를 알 수 있었다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (auth/oauth/apple, `SocialLoginService`, `MemberRepository`, `GuardianAuthDocs`)
- Apple 로그인의 이메일은 서버가 Apple 토큰 엔드포인트에서 받은 `id_token`의 `email`만 쓴다. `profile.email`은 읽지 않는다. `profile.name`은 그대로 쓴다.
- 전화번호 보강은 임시 토큰 검증 → 회원 조회 순서로 바꾸고, 대상 회원의 전화번호가 비어 있을 때만 반영한다. 빈 번호 확인과 갱신은 조건부 UPDATE 한 문장(`MemberRepository.fillPhoneNumberIfEmpty`)으로 한다. 반영하면 커밋 뒤에 임시 회원을 삭제한다.

### Out of scope
- Apple `id_token` 서명(JWKS)·`iss/aud/exp` 검증. 서버가 자기 client secret으로 Apple에 TLS로 직접 요청해 받은 토큰이라 이번 위협(클라이언트 입력 신뢰)과 무관하다. LLD-0004 후속 과제로 남는다.
- 전화번호 보강 요청과 Apple 로그인 세션의 결합(Open Questions).
- 전화번호 중복(`Member.phoneNumber` 유일 제약 없음) 검사. 별도 이슈로 다룬다.

## 3. 인터페이스 / API

요청·응답 형식은 바뀌지 않는다.

```http
POST /api/v1/auth/guardians/sign-in/social?provider=apple
```
- `profile.email`은 받기만 하고 무시한다. 필드는 하위 호환을 위해 남긴다.
- `id_token`에 이메일이 없으면 기존과 같이 `SOCIAL_EMAIL_NOT_PROVIDED`(400)다. 이전에는 `profile.email`로 채워 통과했다.

```http
PATCH /api/v1/auth/guardians/apple/phone-number
Authorization: Bearer {temporaryToken}
{ "email": "guardian@example.com" }
```
- 대상 Apple 회원에 전화번호가 이미 있으면 403 `AUTH_4030`(`FORBIDDEN`, 기존 코드)이다.

## 4. 데이터 모델

변경 없음.

## 5. 처리 흐름

### 5-1. Apple 로그인 이메일 (`AppleLoginStrategy`)
1. authorization code를 Apple `/auth/token`에서 교환하고 `id_token` payload를 읽는다.
2. `email = id_token.email`. `profile`이 있어도 이메일은 덮어쓰지 않는다.
3. `processUserInfo`는 `profile.name`만 보강하고, 이메일은 2의 값을 그대로 둔다.
4. 이후 매칭·연동 분기(LLD-0004 §5-3·5-4)는 같다.

### 5-2. 전화번호 보강 (`SocialLoginService.updatePhoneNumberIfAppleSignUp`, 호출자 `GuardianAuthService` 트랜잭션)
1. `TemporaryMemberUtil`로 임시 토큰과 임시 회원을 확인한다. 실패하면 회원을 조회하지 않는다.
2. `request.email`로 `provider=APPLE` 소셜 계정을 가진 회원을 찾는다. 없으면 `MEMBER_NOT_FOUND`.
3. `UPDATE member SET phone_number = :임시회원번호 WHERE id = :id AND (phone_number IS NULL OR TRIM(phone_number) = '')`를 실행한다. 갱신 행이 0이면(이미 번호가 있음) `FORBIDDEN`. 번호와 임시 회원은 그대로다. 확인과 갱신이 한 문장이라 두 요청이 동시에 빈 번호를 보고 둘 다 반영하는 일이 없다(먼저 커밋한 쪽만 1행, 나머지는 0행).
4. 임시 회원 삭제를 커밋 뒤(`TransactionSynchronization.afterCommit`)로 등록한다. 번호 반영이 롤백되면 임시 토큰을 다시 쓸 수 있다. 커밋 뒤 삭제가 실패하면 WARN만 남기며, 남은 임시 회원은 30분 TTL로 사라지고 같은 회원에 다시 써도 3에서 거부된다. 트랜잭션 동기화가 없으면 바로 삭제한다.

## 6. 예외 / 에러 처리

| 상황 | 에러 |
| --- | --- |
| `id_token`에 이메일 없음 (`profile.email`이 있어도) | SOCIAL_EMAIL_NOT_PROVIDED (400) |
| 임시 토큰 없음·형식 오류 | UNAUTHORIZED (401) |
| 임시 토큰 만료 | TEMPORARY_TOKEN_EXPIRED (401) |
| 이메일의 Apple 회원 없음 | MEMBER_NOT_FOUND (404) |
| 이미 전화번호가 있는 Apple 회원 | FORBIDDEN (403, 이번에 추가한 거부 조건) |

새 에러 코드는 없다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: `profile.email`이 `id_token` 이메일과 다르면 `id_token` 이메일로 회원을 매칭·저장한다. `profile.name`은 반영한다.
- [x] AC2: `id_token`에 이메일이 없으면 `profile.email`로 채우지 않고 `SOCIAL_EMAIL_NOT_PROVIDED`로 실패한다.
- [x] AC3: 전화번호가 없는 Apple 회원은 임시 토큰의 전화번호로 보강되고, 커밋 뒤 임시 회원이 삭제된다.
- [x] AC4: 전화번호가 있는 Apple 회원의 보강 요청은 `FORBIDDEN`이고 번호와 임시 회원이 바뀌지 않는다. 빈 번호 확인과 갱신은 조건부 UPDATE 한 문장이다.
- [x] AC5: 임시 토큰이 없으면 회원을 조회하지 않고 실패한다.
- [x] AC6: Swagger 설명이 위 동작(임시 토큰, 403, profile.email 무시)과 맞다.
- [x] AC7: 트랜잭션 안에서는 커밋 전에 임시 회원을 삭제하지 않는다.

## 8. 영향 범위 / 마이그레이션

- **LLD-0004 정정:** §5-2 9단계와 인수조건 "클라이언트가 전달한 `profile.email`은 `id_token` email보다 우선한다"를 이 문서가 대체한다. 이 우선순위가 매칭 기준을 클라이언트에 넘겨 위 결함을 만들었다.
- **클라이언트 영향:** `id_token`에 이메일이 없고 `profile.email`만 보내던 Apple 계정(예: Apple at Work & School 계정)은 로그인이 400으로 바뀐다. 일반 Apple ID는 `id_token`에 이메일(실제 또는 가림 주소)이 들어 있어 영향이 없다.
- 보강 뒤 같은 임시 토큰으로 다른 API를 부르면 `MEMBER_NOT_FOUND`가 된다.
- 스키마 변경 없음.

## 9. 미결정 사항 (Open Questions)

- [ ] 전화번호 보강을 Apple 로그인 세션과 결합할지. 지금은 이메일만으로 대상을 고르므로, 아직 번호가 빈 Apple 회원은 이메일을 아는 다른 SMS 인증자도 채울 수 있다. 채워진 번호는 전화번호·이름으로 기존 회원을 찾는 다른 가입·연동 경로의 매칭 기준이 되므로 계정 탈취로 이어질 수 있다(Codex 검수 BLOCKER, 2026-10-22). 결합하려면 클라이언트가 Apple 로그인으로 받은 액세스 토큰과 임시 토큰을 함께 보내는 등 요청 형식이 바뀌어야 한다. 이 PR은 클라이언트 계약을 유지하는 범위에서 위험을 "번호가 빈 Apple 회원"으로 줄였고, 결합은 후속 이슈 #838로 다룬다. 클라이언트 팀 확인 필요.
- [ ] 보강 직후 같은 임시 토큰을 다시 쓰는 클라이언트 흐름이 있는지. 있으면 임시 회원 삭제 시점을 조정해야 한다. 클라이언트 팀 확인 필요.
- [ ] `id_token`에 이메일이 없는 Apple 계정을 허용할지. 허용하면 매칭에 쓰지 않는 별도 경로가 필요하다.

## 10. 참고

- LLD-0004 (Apple 로그인 authorization code 교환)
- Apple: [Authenticating users with Sign in with Apple](https://developer.apple.com/documentation/sign_in_with_apple/authenticating-users-with-sign-in-with-apple) — `id_token`의 `email` 클레임
- `AppleLoginStrategy.java`, `SocialLoginService.java`, `GuardianAuthDocs.java`
