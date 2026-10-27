# LLD-0095: 탈퇴 시 Apple·네이버 연동 해제 요청 바로잡기

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #827 |
| 관련 ADR | ADR-0025 |
| 작성자 | Claude |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

LLD-0059로 탈퇴 시 소셜 연동 해제를 커밋 뒤 작업(`social_unlink_task`)으로 옮겼지만, Apple과 네이버는 이 작업이 성공할 수 없다.

- **Apple:** `SocialUnlinkService`가 토큰이 필요한 제공자를 `Set.of("apple", "naver")`로 비교한다. `SocialAccount.provider`에는 `OAuthProvider.getValue()`가 저장되고 Apple은 `"APPLE"`이다(`OAuthProvider.APPLE("APPLE")`, 최초 구현부터 같은 값). 그래서 Apple 계정은 "토큰이 필요 없는 제공자"로 분류돼 refresh token 없이 저장되고, `AppleLoginStrategy.withdrawSocialAccount`가 토큰 누락으로 매번 실패해 5회 뒤 `FAILED`로 끝난다. Apple이 요구하는 계정 삭제 시 토큰 폐기가 일어나지 않는다. LLD-0059 §4의 `provider` 값 표기(`apple`)도 실제와 다르다.
- **네이버:** `NaverLoginStrategy.withdrawSocialAccount`는 client id·secret 없이 `grant_type=delete&refresh_token=…`를 HTTP DELETE로 보낸다. 네이버 명세에 없는 형식이다. 네이버는 연동 해제를 토큰 폐기 API(`POST /oauth2.0/revoke`)로 안내한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `SocialUnlinkService`의 토큰 필요 제공자를 `OAuthProvider.APPLE.getValue()`·`OAuthProvider.NAVER.getValue()`로 비교한다.
- `NaverLoginStrategy`가 `NaverProperties`(client id·secret)를 주입받아 토큰 폐기 API를 명세대로 호출한다. `NAVER_WITHDRAW_URL`을 `https://nid.naver.com/oauth2.0/revoke`로 바꾼다.
- 테스트의 소문자 `"apple"`을 실제 값 `"APPLE"`로 바로잡는다.

### Out of scope
- Apple revoke가 항상 iOS client id·secret을 쓰는 문제. Android(Services ID)로 가입한 계정의 refresh token은 발급 client id로 폐기해야 하지만, `SocialAccount`·`SocialUnlinkTask`에 가입 플랫폼이 없다. 스키마 변경이 필요해 별도로 다룬다.
- 이미 토큰 없이 저장된 Apple 작업의 복구. 탈퇴 마스킹(#680)이 `SocialAccount`의 토큰을 지워 다시 만들 수 없다.
- 카카오 연동 해제(어드민 키 방식, 변경 없음).

## 3. 인터페이스 / API

공개 API 변경 없음. 외부 호출만 바뀐다.

네이버 토큰 폐기 (출처: [네이버 로그인 API 명세 3.3·4.4](https://developers.naver.com/docs/login/api/api.md), [개발자 가이드 4.3 Token Revocation](https://developers.naver.com/docs/login/devguide/devguide.md), 2026-10-22 확인)

```http
POST https://nid.naver.com/oauth2.0/revoke
Content-Type: application/x-www-form-urlencoded

client_id={oauth.naver.client-id}&client_secret={oauth.naver.client-secret}&token={저장된 refresh token}&token_type_hint=refresh_token
```

- `token_type_hint=refresh_token`이면 연결된 access token까지 함께 폐기된다. 따라서 refresh token으로 access token을 먼저 발급받는 과정이 필요 없다.
- 성공은 본문 없는 200이다. 이미 폐기됐거나 없는 토큰도 200이다. 실패는 400(`invalid_request`)·401(`unauthorized_client`)·503(`temporarily_unavailable`) 등 HTTP 상태와 `error` 본문이다. 명세가 "응답 본문이 아닌 HTTP 상태 코드로 판단"하라고 하므로 2xx 여부만 본다.
- refresh token에는 `+`·`/`·`=`가 들어갈 수 있어 URL 인코딩이 필요하다. 폼 본문을 `MultiValueMap`으로 넘겨 `FormHttpMessageConverter`가 인코딩하게 한다.

## 4. 데이터 모델

변경 없음. LLD-0059 §4 `provider` 값은 실제로 `kakao` / `APPLE` / `naver`다.

## 5. 처리 흐름

1. 탈퇴 트랜잭션에서 `SocialUnlinkService.schedule`이 소셜 계정마다 작업을 만든다. `provider`가 `"APPLE"` 또는 `"naver"`면 refresh token을 함께 저장하고, 토큰이 없으면 작업을 만들지 않는다(LLD-0059와 같음).
2. 커밋 뒤 `process`가 전략의 `withdrawSocialAccount(refreshToken, oauthId)`를 부른다.
3. 네이버 전략은 3장의 폼 요청을 보내고, 2xx가 아니거나 호출이 실패하면 `NAVER_WITHDRAW_ERROR`를 던진다. 재시도·최종 실패 처리는 LLD-0059와 같다.

## 6. 예외 / 에러 처리

- 네이버 비 2xx·통신 실패: `NAVER_WITHDRAW_ERROR` → 작업 `attempt_count` 증가(LLD-0059). 로그에는 상태 코드와 예외 클래스명만 남기고 토큰·client secret·응답 본문은 남기지 않는다.
- 새 에러 코드 없음.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: `provider="APPLE"`이고 refresh token이 있는 계정은 토큰과 함께 작업이 저장된다.
- [x] AC2: `provider="APPLE"`인데 refresh token이 없으면 작업을 만들지 않는다.
- [x] AC3: 네이버 연동 해제는 `POST https://nid.naver.com/oauth2.0/revoke`에 `client_id`·`client_secret`·`token`·`token_type_hint=refresh_token`을 URL 인코딩된 폼으로 보낸다.
- [x] AC4: 네이버가 비 2xx를 응답하면 `NAVER_WITHDRAW_ERROR`로 실패한다.

## 8. 영향 범위 / 마이그레이션

- 설정: 기존 `oauth.naver.client-id`·`client-secret`(`OAUTH_NAVER_CLIENT_ID`·`OAUTH_NAVER_CLIENT_SECRET`)을 그대로 쓴다. 새 환경변수 없음.
- 배포 시 `PENDING`으로 남아 있는 네이버 작업은 다음 재시도부터 새 요청 형식을 쓴다. 토큰 없이 저장된 Apple 작업은 그대로 실패로 끝난다.
- 스키마 변경 없음.

## 9. 미결정 사항 (Open Questions)

- [ ] 서버의 `OAUTH_NAVER_CLIENT_ID`가 앱이 네이버 refresh token을 발급받은 애플리케이션과 같은지. 다르면 폐기 요청이 401이 된다. 운영 설정 확인 필요.
- [ ] Apple revoke의 플랫폼별 client id. 가입 플랫폼을 저장할지(스키마 변경) 결정이 필요하다.

## 10. 참고

- LLD-0059 (탈퇴 시 소셜 연동 해제 재시도)
- 네이버 로그인 API 명세: https://developers.naver.com/docs/login/api/api.md
- Apple: [Revoke tokens](https://developer.apple.com/documentation/sign_in_with_apple/revoke_tokens)
