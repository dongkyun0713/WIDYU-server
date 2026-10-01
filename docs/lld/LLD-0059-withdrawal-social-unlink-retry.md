# LLD-0059: 탈퇴 시 소셜 연동 해제 재시도와 Redis 위치 삭제

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #685 |
| 관련 ADR | ADR-0025 |
| 작성자 | 김동균 (Claude Code) |
| 작성일 | 2026-09-28 |

## 1. 목적 / 배경

탈퇴 시 카카오·애플·네이버 연동 해제는 탈퇴 트랜잭션 안에서 한 번만 호출하고, 실패하면 로그만 남긴다. 탈퇴 마스킹이 oauthId를 가리고 소셜 refresh token을 지우므로(#680), 실패한 연동 해제는 다시 시도할 수 없다. 애플은 계정 삭제 시 토큰 폐기(revoke)를 요구한다. 호출이 트랜잭션 안에 있어 연동 해제가 성공한 뒤 트랜잭션이 롤백되면, 회원은 남고 연동만 끊긴다.

탈퇴한 시니어의 Redis 위치는 지우지 않는다. `refreshLocationTtl`이 4분마다 모든 위치를 다시 저장하므로 5분 TTL이 지나도 만료되지 않는다.

## 2. 범위

### In scope

- 변경 모듈: widyu-domain, widyu-api
- 탈퇴 트랜잭션에서 소셜 계정마다 연동 해제 작업(`social_unlink_task`)을 저장한다. 외부 API는 커밋 뒤에 호출한다.
- 실패한 작업은 스케줄러가 10분 간격으로 최대 5회 다시 시도한다.
- 완료·실패로 끝난 작업에서는 oauthId와 refresh token을 지운다.
- 탈퇴가 커밋되면 그 회원의 Redis 위치 키 3종(`senior_location`, `location:trail:`, `location:stay:`)을 지운다.
- `refreshLocationTtl`은 활성 회원(`Status.ACTIVE`)의 위치만 갱신하고, 나머지는 같은 3종을 지운다. 이 갱신이 탈퇴 삭제 직전에 위치를 읽었다가 삭제 뒤에 다시 저장해도, 다음 주기(최대 4분)에 지워진다.

### Out of scope

- 위치 TTL 갱신 정책 자체(마지막 위치를 얼마나 보여 줄지). 기획 결정 뒤 별도로 정한다.
- DB에 남는 위치 이력(`location_fix`, `parent_location`)의 삭제. 탈퇴 시 보존 범위 결정 뒤 다룬다.
- 최종 실패한 작업의 수동 재처리 화면. 실패는 ERROR 로그와 작업 행으로 확인한다.
- 여러 서버에서의 중복 처리 방지(선점·임대). 현재 단일 인스턴스 배포다(ADR-0023).

## 3. 인터페이스 / API

공개 API 계약은 바꾸지 않는다. `DELETE /api/v1/auth/guardians/withdraw`의 요청과 응답은 기존과 같다.

커밋 뒤 리스너는 요청 스레드에서 동기로 돈다. 그래서 첫 연동 해제 시도는 여전히 응답 전에 일어나며, 응답 지연은 기존과 같다. 달라지는 점은 두 가지다.
- 호출이 DB 트랜잭션 밖에서 일어난다.
- 호출 결과가 탈퇴 성공 여부에 영향을 주지 않는다.

실패한 호출은 응답 뒤 스케줄러가 다시 시도한다. 응답 지연이 문제가 되면 리스너를 비동기 실행기로 옮긴다.

## 4. 데이터 모델

`social_unlink_task`(widyu-domain `com.widyu.auth.SocialUnlinkTask`)를 추가한다.

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `member_id` | BIGINT NOT NULL | 탈퇴 회원 |
| `provider` | VARCHAR(20) NOT NULL | `kakao` / `apple` / `naver` |
| `oauth_id` | VARCHAR(255) NULL | 마스킹 전 원래 값. 카카오 어드민 연동 해제 대상. 종료 시 NULL |
| `refresh_token` | VARCHAR(2048) NULL | 애플·네이버 폐기용. `AesGcmStringConverter`로 암호화. 종료 시 NULL |
| `status` | VARCHAR(20) NOT NULL | `PENDING` / `COMPLETED` / `FAILED` |
| `attempt_count` | INT NOT NULL | 실패 횟수 |
| `next_retry_at` | DATETIME NOT NULL | 스케줄러가 집을 시각 |
| `last_error_type` | VARCHAR(100) NULL | 예외 클래스명만 |
| `completed_at`, `failed_at` | DATETIME NULL | |
| `created_at`, `updated_at` | DATETIME NOT NULL | |

인덱스 `idx_social_unlink_due (status, next_retry_at)`. 생성 SQL은 `scripts/mysql/create_social_unlink_task.sql`이다.

## 5. 처리 흐름

1. `MemberWithdrawService.withdrawMember` 트랜잭션 안에서 마스킹 전에 `SocialUnlinkService.schedule(member)`를 호출한다. 소셜 계정마다 작업을 `PENDING`으로 저장하며, `next_retry_at = now + 10분`이다.
   - 애플·네이버인데 refresh token이 없으면 폐기할 토큰이 없으므로 작업을 만들지 않는다. 기존 동작처럼 경고만 남긴다.
2. 트랜잭션 끝에서 `MemberWithdrawnEvent(memberId)`를 발행한다.
3. 커밋 뒤(`@TransactionalEventListener(AFTER_COMMIT)`) 두 리스너가 따로 동작한다.
   - `SocialUnlinkListener`: 그 회원의 `PENDING` 작업을 하나씩 처리한다.
   - `WithdrawnMemberLocationListener`: Redis 위치 키 3종을 지운다.
4. 작업 처리는 `SocialUnlinkService.process(id)`이며, 작업마다 새 트랜잭션(`REQUIRES_NEW`)에서 한다.
   - 작업이 `PENDING`이 아니면 건너뛴다.
   - 제공자 전략의 `withdrawSocialAccount(refreshToken, oauthId)`를 호출한다.
   - 성공하면 `COMPLETED`로 바꾸고 oauthId·토큰을 지운다.
   - 실패하면 `attempt_count`를 올리고 예외 클래스명을 기록한다. 5회째 실패면 `FAILED`로 끝내고 oauthId·토큰을 지운 뒤 ERROR 로그를 남긴다. 그 전이면 `next_retry_at = now + 10분`이다.
5. `SocialUnlinkRetryScheduler`가 10분마다 `status = PENDING AND next_retry_at <= now`인 작업을 최대 100건 처리한다.

작업 생성 시 `next_retry_at`을 10분 뒤로 잡으므로, 커밋 직후 리스너가 처리하는 동안 스케줄러가 같은 작업을 집지 않는다.

## 6. 예외 / 에러 처리

- 연동 해제 실패는 탈퇴 API 실패로 전파하지 않는다. 호출이 커밋 뒤에 일어나므로 구조상으로도 전파되지 않는다.
- 리스너와 스케줄러는 작업별로 예외를 잡아 다음 작업을 계속 처리한다.
- 로그와 `last_error_type`에는 oauthId·토큰·응답 본문을 남기지 않는다. 작업 ID·회원 ID·제공자·예외 클래스명만 남긴다.
- Redis 삭제가 실패하면 WARN만 남긴다. 탈퇴는 이미 커밋된 뒤다.
- 새 에러 코드는 없다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 탈퇴 트랜잭션 안에서 외부 연동 해제 API를 호출하지 않고 소셜 계정마다 `PENDING` 작업을 저장한다.
- [x] 토큰이 없는 애플·네이버 계정은 작업을 만들지 않는다.
- [x] 작업 처리에 성공하면 `COMPLETED`가 되고 oauthId·refresh token이 NULL이 된다.
- [x] 실패하면 `attempt_count`가 오르고 다음 시도 시각이 10분 뒤로 잡힌다. 5회째 실패하면 `FAILED`가 되고 oauthId·refresh token이 NULL이 된다.
- [x] `PENDING`이 아닌 작업은 다시 처리하지 않는다.
- [x] 탈퇴가 커밋되면 `MemberWithdrawnEvent`로 연동 해제 처리와 Redis 위치 키 3종 삭제가 일어난다.
- [x] 위치 TTL 갱신은 활성 회원의 위치만 다시 저장하고, 활성이 아닌 회원의 위치는 지운다.
- [x] refresh token은 DB에 암호화되어 저장된다(`AesGcmStringConverter`).
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **신규 테이블:** `social_unlink_task`. 운영 DB는 `ddl-auto: validate`이므로 배포 전에 `scripts/mysql/create_social_unlink_task.sql`을 실행한다. 운영 서버는 아직 열지 않았다(2026-09-28).
- **바뀌는 동작:** `MemberWithdrawService`는 더 이상 `SocialLoginStrategyFactory`를 직접 쓰지 않는다. 연동 해제는 커밋 뒤 같은 요청 스레드에서 한 번 시도하고, 실패분은 스케줄러가 응답 뒤에 다시 시도한다.
- `RealtimeLocationService`가 `MemberRepository`를 새로 주입받는다. `refreshLocationTtl`은 4분마다 위치가 있는 회원 수만큼 ID 조회를 한 번(IN 절) 더 한다.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고

- LLD-0030(S3 삭제 작업 재시도): 같은 "트랜잭션 안 작업 저장 → 커밋 뒤 처리 → 스케줄러 재시도" 구조
- Apple: [Revoke tokens](https://developer.apple.com/documentation/sign_in_with_apple/revoke_tokens)
