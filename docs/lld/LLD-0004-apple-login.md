# LLD-0004: Apple 로그인 authorization code 교환

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | - |
| 관련 ADR | ADR-0002 (JWT 인증/인가), ADR-0003 (DB 설계) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-05 |

## 1. 목적 / 배경

보호자 회원은 Apple OAuth로 로그인할 수 있어야 한다.
Apple은 최초 동의 시에만 이름·이메일을 안정적으로 제공하고, 모바일 앱은 Apple 콜백을 받은 뒤 백엔드에 authorization code를 전달한다.
백엔드는 플랫폼별 Apple client id로 client secret을 생성해 code를 토큰으로 교환하고, `id_token` payload의 subject를 `SocialAccount.oauthId`로 저장한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (auth/oauth/apple, auth/callback), widyu-domain (SocialAccount)
- Apple OAuth form callback 수신 후 모바일 앱 intent URL redirect
- 보호자 Apple 소셜 로그인 (`provider=apple`)
- 플랫폼별 client secret 생성 (iOS / Android client id)
- authorization code → Apple token 교환
- `id_token` payload 파싱 후 Apple subject/email 추출
- `SocialAccount` 저장 및 기존 회원/신규 회원/계정 연동 분기
- Apple 최초 가입 후 전화번호 보강
- 회원 탈퇴 시 Apple refresh token revoke

### Out of scope
- Apple `id_token` JWKS 서명 검증, `iss/aud/exp` 검증 (현재 미구현, 후속 ADR/LLD 필요)
- Apple 로그인 UI 및 모바일 SDK 처리
- Kakao/Naver 소셜 로그인 세부 설계
- refresh token 갱신 플로우

## 3. 인터페이스 / API

```http
POST /api/v1/auth/callback/apple
Content-Type: application/x-www-form-urlencoded
```

Apple이 호출하는 callback endpoint. 응답 body 대신 모바일 앱 intent URL로 redirect한다.

Form parameters:
- `code`: Apple authorization code
- `id_token`: Apple id token
- `state`: 현재 서비스 로직에서는 사용하지 않음
- `error`: Apple callback error

Redirect:
```text
intent://callback?code={code}&id_token={id_token}#Intent;package={callback.package-name};scheme={callback.schemes.apple};end
```

에러가 있으면 `intent://callback?error={error}...` 형식으로 redirect한다.

```http
POST /api/v1/auth/guardians/sign-in/social?provider=apple
```

모바일 앱이 Apple callback에서 받은 code를 백엔드에 전달한다.

Request:
```json
{
  "authorizationCode": "c123456...",
  "platform": "ios",
  "profile": {
    "email": "guardian@example.com",
    "name": "홍길동"
  }
}
```

- `authorizationCode`: 필수
- `platform`: `ios` 또는 `android`; 누락·알 수 없는 값은 현재 `ios`로 처리
- `profile.email`, `profile.name`: Apple 최초 동의 시 클라이언트가 전달할 수 있는 보강 정보

Response: 신규/기존 Apple 계정 로그인 성공
```json
{
  "isSuccess": true,
  "result": {
    "isFirst": true,
    "memberId": 1,
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "profile": {
      "name": "홍길동",
      "phoneNumber": null,
      "email": "guardian@example.com",
      "providers": ["apple"]
    }
  }
}
```

Response: 같은 이메일의 다른 계정이 있어 연동이 필요한 경우
```json
{
  "isSuccess": true,
  "result": {
    "isFirst": false,
    "profile": {
      "name": "홍길동",
      "email": "guardian@example.com",
      "providers": ["local"]
    },
    "socialTemporaryToken": "eyJ..."
  }
}
```

```http
PATCH /api/v1/auth/guardians/apple/phone-number
Authorization: Bearer {temporaryToken}
```

Apple 최초 가입 후 임시 회원 토큰의 전화번호를 Apple 회원에 보강한다.

Request:
```json
{ "email": "guardian@example.com" }
```

## 4. 데이터 모델

### 엔티티 (widyu-domain)

**SocialAccount** (`social_account` 테이블):
```
social_account
├── id (PK, IDENTITY)
├── email (String)
├── provider (String, not null)       ← "apple"
├── oauthId (String, not null)        ← Apple id_token.sub
├── refresh_token (String, length=1000)
├── is_first (boolean)
└── member_id (FK, ManyToOne)

unique(provider, oauthId)  ← uk_provider_user
```

**Member**:
```
member
├── member_type = GUARDIAN
├── name
├── phone_number  ← Apple 응답에는 없음, 별도 PATCH로 보강 가능
└── social_accounts
```

### 설정

`oauth.apple`:
```
ios-client-id
android-client-id
team-id
key-id
private-key
redirect-uri
```

`callback`:
```
package-name
schemes.apple
```

### DTO

- `SocialLoginRequest`: `accessToken`, `authorizationCode`, `refreshToken`, `profile(email,name)`, `platform`
- `AppleTokenRequest`: `client_id`, `client_secret`, `code`, `grant_type=authorization_code`, `redirect_uri`
- `AppleTokenResponse`: `access_token`, `token_type`, `expires_in`, `refresh_token`, `id_token`
- `AppleIdTokenPayload`: `iss`, `aud`, `exp`, `iat`, `sub`, `email`, `email_verified`, `is_private_email`
- `SocialLoginResponse`: `isFirst`, `memberId`, `accessToken`, `refreshToken`, `profile`, `socialTemporaryToken`

## 5. 처리 흐름

### 5-1. Apple callback redirect (`OAuthCallbackService`)

```
1. Apple이 POST /api/v1/auth/callback/apple 호출
2. error가 있으면 query param error만 구성
3. error가 없으면 code, id_token을 URL encode
4. callback.package-name, callback.schemes.apple로 intent URL 생성
5. HttpServletResponse.sendRedirect(intentUrl)
```

콜백 endpoint는 백엔드 로그인 처리를 완료하지 않는다. 모바일 앱으로 code를 전달하는 중계 역할만 한다.

### 5-2. Apple 소셜 로그인 (`SocialLoginService.socialLogin`)

```
1. GuardianAuthController: POST /sign-in/social?provider=apple
2. SocialLoginStrategyFactory에서 AppleLoginStrategy 선택
3. validateLoginRequest()
   - authorizationCode 누락/blank면 APPLE_AUTHORIZATION_CODE_IS_BLANK
4. AppleJwtUtils.generateClientSecret(platform)
   - platform=ios     → oauth.apple.ios-client-id를 sub/client_id로 사용
   - platform=android → oauth.apple.android-client-id를 sub/client_id로 사용
   - ES256, kid=keyId, iss=teamId, aud=https://appleid.apple.com, exp=now+5분
5. Apple /auth/token에 form-url-encoded 요청
   - client_id, client_secret, code, grant_type=authorization_code, redirect_uri
6. AppleTokenResponse 파싱
7. id_token을 JWT 세 부분으로 split하고 payload만 Base64Url decode
8. AppleIdTokenPayload.sub → SocialClientResponse.oauthId
9. email 결정 (LLD-0094에서 정정)
   - id_token payload email만 사용한다. request.profile.email은 무시한다
10. name 결정
   - request.profile.name이 있으면 사용
   - 없으면 "익명의 사용자"
11. email 없으면 SOCIAL_EMAIL_NOT_PROVIDED
12. provider+oauthId로 기존 SocialAccount 조회
```

현재 구현은 `id_token`의 payload를 파싱하지만 Apple 공개키 기반 서명 검증과 `iss/aud/exp` 검증은 수행하지 않는다.

### 5-3. 기존 회원 로그인

```
1. findMemberByProvider(provider=apple, oauthId)
2. Member.markSocialAsNotFirst(provider, oauthId)
3. JwtTokenProvider.generateTokenPair(memberId, USER, "apple")
4. SocialLoginResponse.of(isFirst, memberId, accessToken, refreshToken, profile)
```

주의: `markSocialAsNotFirst()` 호출 후 `isFirst`를 읽기 때문에 기존 회원 응답의 `isFirst`는 false가 된다.

### 5-4. 신규 회원 또는 계정 연동 분기

```
1. 전화번호가 있으면 phoneNumber로 기존 회원 조회
2. email이 있으면 socialAccount.email로 기존 회원 조회
3. 기존 회원이 있고 local/social 계정이 이미 있으면:
   - SocialTemporaryTokenService.createSocialTemporaryToken(memberId, provider, oauthId, email)
   - accessToken/refreshToken 없이 socialTemporaryToken과 profile 반환
4. 충돌 계정이 없으면:
   - Member.createMember(GUARDIAN, name, phoneNumber)
   - SocialAccount.createSocialAccount(email, "apple", oauthId, appleRefreshToken, member)
   - memberRepository.save(member)
   - JWT pair 발급
```

Apple 로그인 응답에는 전화번호가 없으므로 `phoneNumber`는 일반적으로 null이다.

### 5-5. Apple 전화번호 보강

```
1. PATCH /api/v1/auth/guardians/apple/phone-number
2. request.email로 provider=apple SocialAccount를 가진 Member 조회
3. Authorization 헤더에서 temporary token 추출
4. TemporaryMemberUtil로 임시 회원 정보 검증
5. temporaryMember.phoneNumber를 Member.phoneNumber에 반영
   (LLD-0094에서 정정: 임시 토큰을 먼저 검증하고, 회원 번호가 비어 있을 때만 반영하며, 반영 뒤 임시 회원을 삭제한다)
```

### 5-6. Apple 계정 탈퇴 revoke

```
1. MemberWithdrawService가 SocialAccount(provider=apple)를 순회
2. SocialAccount.refreshToken이 없으면 APPLE_WITHDRAW_ERROR
3. AppleJwtUtils.generateClientSecret() 호출
   - platform 인자를 전달하지 않으므로 기본값 ios client id 사용
4. Apple /auth/revoke에 refresh token revoke 요청
```

## 6. 예외 / 에러 처리

| 상황 | 에러 |
|------|------|
| authorizationCode 없음 | APPLE_AUTHORIZATION_CODE_IS_BLANK |
| Apple token endpoint 비 2xx 응답 | APPLE_COMMUNICATION_ERROR |
| Apple token response 파싱 실패 | APPLE_TOKEN_RESPONSE_INVALID |
| id_token 형식 오류 또는 payload 파싱 실패 | APPLE_COMMUNICATION_ERROR |
| email 없음 | SOCIAL_EMAIL_NOT_PROVIDED |
| Apple private key 파싱 실패 | APPLE_PRIVATE_KEY_PARSING_FAILED |
| 전화번호 보강 대상 Apple 회원 없음 | MEMBER_NOT_FOUND |
| 임시 토큰 누락/만료/불일치 | INVALID_TEMPORARY_TOKEN 또는 TEMPORARY_TOKEN_EXPIRED |
| 이미 연동된 provider | SOCIAL_PROVIDER_ALREADY_LINKED |
| 다른 회원에 이미 연동된 소셜 계정 | SOCIAL_ACCOUNT_ALREADY_LINKED |
| Apple refresh token revoke 실패 | APPLE_WITHDRAW_ERROR |

## 7. 인수조건 (Acceptance Criteria)

- [x] `authorizationCode`가 없으면 Apple token endpoint를 호출하지 않고 실패한다
- [x] `platform=ios`는 iOS client id로, `platform=android`는 Android client id로 client secret을 생성한다
- [x] Apple token 교환 성공 시 `id_token.sub`를 `SocialAccount.oauthId`로 저장한다
- [x] ~~클라이언트가 전달한 `profile.email`은 id_token email보다 우선한다~~ → LLD-0094: id_token email만 사용한다
- [x] 이름이 없으면 "익명의 사용자"로 회원명을 만든다
- [x] 기존 Apple 계정 로그인은 JWT pair와 profile을 반환한다
- [x] 같은 이메일의 다른 계정이 있으면 `socialTemporaryToken`을 반환한다
- [x] Apple 최초 가입 후 임시 토큰으로 전화번호를 보강할 수 있다
- [x] 회원 탈퇴 시 저장된 Apple refresh token으로 revoke를 시도한다

## 8. 영향 범위 / 마이그레이션

- 기존 구현 완료 상태, 스키마 변경 없음
- `SocialAccount.refresh_token`은 Apple revoke를 위해 유지해야 한다
- `platform` 기본값이 iOS이므로 Android 클라이언트는 반드시 `platform=android`를 전달해야 한다
- 현재 `id_token` 서명·claim 검증이 없으므로 보안상 후속 개선이 필요하다

## 9. 미결정 사항 (Open Questions)

없음. (백필 문서, 구현 완료 상태)

후속 보안 개선 후보: Apple JWKS 기반 `id_token` 서명 검증과 `iss/aud/exp` 검증을 별도 ADR/LLD로 도입한다. 도입 시 플랫폼별 audience 검증 기준과 기존 모바일 요청 호환 기간을 함께 결정한다.

## 10. 참고

- `GuardianAuthController.java`: Apple social sign-in, phone number patch endpoint
- `OAuthCallbackController.java`: Apple form callback endpoint
- `OAuthCallbackService.java`: 모바일 앱 intent URL redirect
- `AppleLoginStrategy.java`: authorization code 교환, id_token payload 파싱, revoke
- `AppleJwtUtils.java`: Apple client secret 생성
- `SocialLoginService.java`: 기존/신규/연동 분기
- `SocialAccount.java` (widyu-domain): Apple oauthId, refreshToken 저장
