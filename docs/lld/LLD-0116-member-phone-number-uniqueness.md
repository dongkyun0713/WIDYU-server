# LLD-0116: 회원 전화번호 유일성과 중복 데이터의 단건 조회

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #850 |
| 관련 ADR | ADR-0003 (DB 설계) |
| 작성자 | Claude |
| 작성일 | 2026-10-31 |

## 1. 목적 / 배경

`member.phone_number`에는 유일 제약이 없다. 그런데 인증과 회원 매칭 코드는 전화번호(또는 전화번호와 이름)로 회원이 하나만 나온다고 가정하고 `Optional` 단건 조회를 쓴다. 같은 번호의 회원이 둘 이상이면 단건 조회가 `IncorrectResultSizeDataAccessException`으로 500이 되고, 회원을 고르는 경로는 엉뚱한 회원을 매칭할 수 있다.

시니어 일괄 등록, 보호자의 시니어 번호 수정, 시니어 본인 번호 수정, Apple 회원 번호 보강은 중복 확인 없이 번호를 저장해 이런 데이터를 만들 수 있었다.

정책(2026-10-31 사용자 결정): **전화번호는 회원 전체에서 하나다.** 시니어와 보호자 사이에도 같은 번호를 허용하지 않는다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api, widyu-domain(`ErrorCode`만)
- 번호를 저장하는 경로에서 다른 회원이 이미 쓰는 번호를 거부한다. 본인 번호를 그대로 다시 저장하는 것은 허용한다.
- 전화번호로 회원을 고르는 단건 조회가 기존 중복 데이터 때문에 500으로 끝나지 않게 한다. 중복이면 임의 회원을 고르지 않고 명확한 오류로 끝낸다.
- 운영 DB 중복 조사용 SELECT 쿼리를 남긴다(§8).

### Out of scope
- DB 유일 제약(`UNIQUE (phone_number)`). 운영 DB의 기존 중복을 조사해 정리한 뒤 별도 이슈로 건다. 그때까지 확인과 저장 사이의 동시 요청은 막지 못한다.
- 로컬 가입 `LocalLoginService.signupGuardianWithLocal`. 기존 회원이면 거부하는 변경을 #853(LLD-0115)이 따로 한다.
- Apple 번호 보강의 대상 회원 결정 방식(#838, LLD-0094 Open Questions). 이번에는 중복 거부만 넣는다.
- 이메일 단건 조회(`findBySocialAccounts_Email`)의 중복.
- 관리자 상태 변경으로 되살리는 회원의 번호 재검사(§5-4).

## 3. 인터페이스 / API

요청과 응답 형식은 바뀌지 않는다. 새로 생기거나 바뀌는 오류 응답만 있다.

| API | 추가되는 응답 |
| --- | --- |
| `POST /api/v1/auth/seniors/sign-up` (시니어 일괄 등록) | 400 `REQ_4000`(요청 안 번호 중복), 400 `AUTH_4021`(이미 가입된 번호) |
| `POST /api/v1/auth/seniors/sign-in` | 409 `MEMBER_4090` |
| 보호자 마이페이지 부모님 추가, 번호 변경 SMS 발송 | 400 `REQ_4000` → 400 `AUTH_4021`(HTTP 상태는 같고 `code`만 바뀜) |
| 보호자 번호 변경 확정, 시니어 번호 수정(방장) | 400 `AUTH_4021` |
| 시니어 마이페이지 번호 수정 | 400 `AUTH_4021` |
| `PATCH /api/v1/auth/guardians/apple/phone-number` | 400 `AUTH_4021` |
| 소셜 로그인(신규 소셜 계정), 이메일 찾기, 비밀번호 변경, 임시 토큰 프로필 조회 | 409 `MEMBER_4090` |

```json
{
  "code": "MEMBER_4090",
  "message": "같은 전화번호로 가입된 회원이 여러 명이라 본인을 확인할 수 없습니다. 고객센터로 문의해 주세요.",
  "data": null
}
```

## 4. 데이터 모델

스키마 변경 없음.

- `ErrorCode.ALREADY_REGISTERED_PHONE_NUMBER(400, AUTH_4021)`: #853과 같은 정의를 쓴다. 두 PR이 같은 줄을 추가하므로 병합 충돌이 나지 않는다.
- `ErrorCode.PHONE_NUMBER_SHARED_BY_MEMBERS(409, MEMBER_4090)`: 새 코드.
- `MemberRepository`: `existsByPhoneNumber`, `existsByPhoneNumberAndIdNot`, `findTop2ByPhoneNumber`, `findTop2ByPhoneNumberAndName`.
- `SeniorProfileRepository.findByInviteCodeAndMemberPhoneNumber`(Optional)를 `findAllByInviteCodeAndMemberPhoneNumber`(List)로 바꾼다.

## 5. 처리 흐름

### 5-1. 저장 경로의 중복 거부

| 경로 | 검사 |
| --- | --- |
| `SeniorAuthService.seniorSignUpBulk` | 가족을 만들기 전에 요청 안 번호가 겹치면 `BAD_REQUEST`, 하나라도 `existsByPhoneNumber`면 `ALREADY_REGISTERED_PHONE_NUMBER`. 가족과 회원은 저장하지 않는다 |
| `GuardianMyPageService.addSenior`, `sendPhoneChangeSms` | `findByPhoneNumber(...).isPresent()`를 `existsByPhoneNumber`로 바꾼다. 중복 데이터가 있어도 500이 아니다 |
| `GuardianMyPageService.updatePhone` | SMS 발송 때 확인했어도 인증하는 동안 다른 회원이 번호를 가져갈 수 있어 저장 직전에 `existsByPhoneNumberAndIdNot(번호, 본인 id)`로 다시 본다 |
| `GuardianMyPageService.updateSeniorPhone` | `existsByPhoneNumberAndIdNot(번호, 시니어 id)` |
| `SeniorMyPageService.updatePhoneNumber` | `existsByPhoneNumberAndIdNot(번호, 본인 id)` |
| `SocialLoginService.updatePhoneNumberIfAppleSignUp` | 대상 회원을 정한 뒤 `fillPhoneNumberIfEmpty` 전에 `existsByPhoneNumberAndIdNot(번호, 대상 id)` |

`IdNot` 검사는 본인 행을 빼고 보므로 본인 번호를 그대로 다시 저장하는 요청은 통과한다.

### 5-2. 단건 조회의 중복 처리

단건 `Optional` 조회 대신 두 건까지 읽는 List 조회를 쓰고 개수로 판단한다. 0건이면 기존 동작(없음), 1건이면 그 회원, 2건이면 `PHONE_NUMBER_SHARED_BY_MEMBERS`(409)로 끝낸다.

| 호출부 | 의미 | 중복일 때 |
| --- | --- | --- |
| `SocialLoginService.findExistingMemberByUserInfo` | 신규 소셜 계정을 붙일 기존 회원 찾기 | 409. 매칭 실패로 넘기면 이메일 매칭이나 새 회원 생성으로 이어져 같은 번호의 회원이 하나 더 생긴다 |
| `LocalLoginService.findMemberByPhoneNumberAndName` | 이메일(아이디) 찾기 | 409. 다른 사람의 이메일을 보여 줄 수 있다 |
| `LocalLoginService.changePassword` | 비밀번호 재설정 | 409. 다른 사람의 비밀번호를 바꿀 수 있다 |
| `LocalLoginService.getUserProfileByTemporaryToken` | 임시 토큰 프로필 | 409 |
| `SeniorAuthService.seniorSignIn` | 초대코드와 번호로 시니어 로그인 | 409. 초대코드도 유일하지 않아 같은 코드와 번호의 시니어가 여럿일 수 있다 |

매칭 실패(404)가 아니라 409를 고른 이유: 요청자는 SMS로 번호를 증명했고 계정도 실제로 있다. 404는 "가입하지 않았다"로 읽혀 재가입을 유도하고, 재가입은 중복을 늘린다. 409는 데이터 정리가 필요하다는 뜻을 그대로 전한다.

예외를 잡아 처리하지 않고 List로 받는 이유: 리포지토리 프록시 안에서 던진 예외는 바깥 트랜잭션을 rollback-only로 표시하므로, 잡아서 계속 진행하면 커밋 시점에 `UnexpectedRollbackException`이 된다.

### 5-3. 트랜잭션

모든 검사는 각 서비스의 기존 `@Transactional` 안에서 저장보다 먼저 일어난다. 예외가 나면 그 요청의 저장은 모두 롤백된다.

### 5-4. 탈퇴 회원의 번호

- 본인 탈퇴(`MemberWithdrawService.withdrawMember`)는 `Member.maskPersonalInfo()`로 `phone_number`를 `NULL`로 비운다. 번호가 남지 않으므로 다른 사람이 그 번호로 가입하거나 번호를 바꿀 수 있다. 별도 처리는 하지 않는다.
- 관리자 상태 변경(`AdminMemberService.changeStatus` → `INACTIVE`)은 `withdraw()`만 호출해 번호를 남기고, 다시 `ACTIVE`로 되돌릴 수 있다. 되돌렸을 때 다른 회원과 번호가 겹치지 않게 이 번호는 계속 사용 중으로 본다. 그래서 검사는 회원 상태를 보지 않는다.
- 따라서 "탈퇴 회원 번호 재사용"은 마스킹으로 이미 성립하고, 상태와 무관하게 번호가 남아 있으면 사용 중으로 본다.

## 6. 예외 / 에러 처리

| 상황 | 에러 |
| --- | --- |
| 일괄 등록 요청 안에 같은 번호가 두 번 이상 | BAD_REQUEST (400, `REQ_4000`) |
| 다른 회원이 이미 쓰는 번호로 저장 | ALREADY_REGISTERED_PHONE_NUMBER (400, `AUTH_4021`) |
| 전화번호(와 이름, 또는 초대코드)로 고른 회원이 둘 이상 | PHONE_NUMBER_SHARED_BY_MEMBERS (409, `MEMBER_4090`) |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 시니어 일괄 등록 요청 안에 같은 번호가 있으면 거부하고 가족과 회원을 저장하지 않는다.
- [x] AC2: 시니어 일괄 등록에 다른 회원이 쓰는 번호가 있으면 `ALREADY_REGISTERED_PHONE_NUMBER`로 거부하고 가족과 회원을 저장하지 않는다.
- [x] AC3: 방장의 시니어 번호 수정, 시니어 본인 번호 수정, 보호자 번호 변경 확정, Apple 번호 보강은 다른 회원이 쓰는 번호면 `ALREADY_REGISTERED_PHONE_NUMBER`이고 번호가 바뀌지 않는다.
- [x] AC4: 본인 번호를 그대로 다시 저장하면 허용한다.
- [x] AC5: 같은 번호의 회원이 둘 이상 저장돼 있어도 두 건 조회는 예외 없이 두 건을 돌려준다(H2 리포지토리 테스트).
- [x] AC6: 중복 데이터가 있으면 소셜 신규 계정 매칭, 이메일 찾기, 비밀번호 변경, 시니어 로그인이 500이 아니라 `PHONE_NUMBER_SHARED_BY_MEMBERS`로 끝나고 임의 회원에 연결하거나 비밀번호를 바꾸지 않는다.
- [x] AC7: 탈퇴로 마스킹된 회원의 번호는 사용 중으로 보지 않는다.
- [x] AC8: Swagger에 새 오류 응답이 반영된다.
- [x] AC9: `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **클라이언트 영향:** 부모님 추가와 번호 변경 SMS 발송의 중복 응답 `code`가 `REQ_4000`에서 `AUTH_4021`로 바뀐다. HTTP 상태(400)는 같다. 409 `MEMBER_4090`은 기존 중복 데이터가 있을 때만 나온다.
- **#853 병합:** 두 PR이 `existsByPhoneNumber`와 `ALREADY_REGISTERED_PHONE_NUMBER`를 같은 위치에 같은 내용으로 추가한다. 두 PR이 모두 머지되면 `MemberRepository.findByPhoneNumber`, `findByPhoneNumberAndName`은 호출부가 없어지므로 후속으로 지운다. 이번 PR에서 지우면 #853과 같은 줄을 서로 다르게 바꿔 충돌한다.
- **운영 DB 중복 조사(미실행, 운영 접속 권한자가 실행):** 결과에는 실제 번호가 나오므로 문서나 PR에 붙이지 않는다.

```sql
-- 1. 같은 번호를 쓰는 회원 묶음 (상태와 유형 포함)
SELECT phone_number,
       COUNT(*) AS member_count,
       SUM(status = 'ACTIVE') AS active_count,
       GROUP_CONCAT(CONCAT(id, ':', type, ':', status) ORDER BY id) AS members
FROM member
WHERE phone_number IS NOT NULL AND TRIM(phone_number) <> ''
GROUP BY phone_number
HAVING COUNT(*) > 1
ORDER BY member_count DESC;

-- 2. 유일 제약에서 서로 충돌할 빈 문자열 번호
SELECT COUNT(*) AS blank_phone_count
FROM member
WHERE phone_number IS NOT NULL AND TRIM(phone_number) = '';

-- 3. 같은 초대코드와 번호의 시니어 묶음 (시니어 로그인 409 대상)
SELECT sp.invite_code, m.phone_number, COUNT(*) AS senior_count,
       GROUP_CONCAT(m.id ORDER BY m.id) AS member_ids
FROM senior_profile sp
JOIN member m ON m.id = sp.member_id
GROUP BY sp.invite_code, m.phone_number
HAVING COUNT(*) > 1;

-- 4. 번호가 남아 있는 비활성 회원 (마스킹 이전 탈퇴 또는 관리자 정지)
SELECT COUNT(*) AS inactive_with_phone
FROM member
WHERE status = 'INACTIVE' AND phone_number IS NOT NULL;
```

- **후속 DB 제약(이번에 실행하지 않음):** 1과 2가 0건이 된 뒤 `ALTER TABLE member ADD CONSTRAINT uk_member_phone_number UNIQUE (phone_number);`를 걸고 `Member.phoneNumber`에 `@Column(unique = true)`를 맞춘다. MySQL 유일 인덱스는 `NULL`을 여러 개 허용하므로 마스킹된 탈퇴 회원은 막히지 않는다. 제약을 건 뒤에는 저장 경로에서 `DataIntegrityViolationException`을 `ALREADY_REGISTERED_PHONE_NUMBER`로 바꿔 동시 요청까지 닫는다.

## 9. 미결정 사항 (Open Questions)

- [ ] 운영 DB에 중복이 있으면 어느 회원에 번호를 남길지(정리 기준). 조사 결과를 보고 정한다.

## 10. 참고

- LLD-0094 (Apple 번호 보강 조건부 UPDATE), LLD-0115 (#853 로컬 가입 기존 회원 거부), LLD-0059 (탈퇴)
- `MemberRepository.java`, `SeniorProfileRepository.java`, `SeniorAuthService.java`, `GuardianMyPageService.java`, `SeniorMyPageService.java`, `SocialLoginService.java`, `LocalLoginService.java`
