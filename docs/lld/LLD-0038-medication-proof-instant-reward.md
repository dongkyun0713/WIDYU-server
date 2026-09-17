# LLD-0038: 복약 인증 즉시 포인트 적립

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> LLD 하나 = PR 하나가 원칙. "하나의 PR에 넣기엔 diff가 너무 많다(파일 15개 이상)"면 LLD를 분리한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #622 |
| 관련 ADR | - |
| 작성자 | Claude Code |
| 작성일 | 2026-09-17 |

## 1. 목적 / 배경

복약 인증 포인트는 자정 배치 `MedicineScheduleRewardScheduler`가 전날분을 한꺼번에 정산했다.
시니어는 인증 직후 응답에서 "적립 예정 10p"를 보지만 잔액은 다음 날에야 바뀌어, 보유 포인트 화면과 인증 화면이 하루 동안 어긋난다.
인증 성공과 같은 트랜잭션에서 즉시 적립해 이 간극을 없앤다. 금액 규칙(1회 10p, 그날 유효 일정 완주 시 보너스 20p)은 그대로 둔다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (엔티티·테이블 변경 없음)
- `MedicationProofTransactionService.verifyMedication`에서 인증 저장 직후 포인트 적립
- 자정 배치 `MedicineScheduleRewardScheduler`와 전용 조회 메서드 제거
- Swagger 인증 응답 설명을 즉시 적립 기준으로 갱신

### Out of scope
- 포인트 금액·보너스 조건 변경 (`MedicationPointPolicy` 상수 유지)
- 배포 전환 시점의 1회 수동 정산 (§8 운영 절차로 분리)
- 인증 취소·철회에 따른 포인트 환수 (현재 인증 취소 기능 없음)

## 3. 인터페이스 / API

경로·요청 형식은 변경하지 않는다. 응답 필드의 의미만 바뀐다.

```http
POST /api/v1/medicine-schedules/{scheduleId}/proof
```

```json
{
  "isSuccess": true,
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "result": {
    "currentPoints": 130,
    "earnedPoints": 10
  }
}
```

| 필드 | 타입 | 변경 전 | 변경 후 |
| --- | --- | --- | --- |
| `currentPoints` | Long (non-null) | 이번 인증분이 빠진 잔액 | 이번 인증분이 반영된 잔액 |
| `earnedPoints` | Long (non-null) | 자정에 적립될 예정 포인트 | 이번 인증으로 적립된 포인트 |

시니어 프로필이 없는 회원은 인증만 성공하고 `currentPoints=0`, `earnedPoints`는 계산값 그대로다.

## 4. 데이터 모델

신규·변경 테이블 없다.

- `MedicationProof`(widyu-domain): 그대로. `saveAndFlush`로 확보한 `id`를 포인트 멱등 키에 쓴다.
- `PointHistory`(widyu-domain): 그대로. 적립 1건당 `type=EARN`, `description="약 복용 인증"`, `operationKey="MEDICATION_PROOF:{proofId}"` 로 남는다.
- `MedicationProofRepository.findDistinctMembersByVerifiedAtBetween` 삭제 (사용처가 배치뿐이었다).

## 5. 처리 흐름

`MedicationProofService`(트랜잭션 밖, S3 업로드) → `MedicationProofTransactionService`(단일 `@Transactional`) 2단 구조는 유지한다.
포인트 적립은 두 번째 단계 안에서 일어난다.

1. `MedicationProofService.verifyMedication` — 사전 검증(`validateBeforeUpload`) 후 S3에 인증 이미지를 올린다. 여기까지 트랜잭션 밖이다.
2. `MedicationProofTransactionService.verifyMedication` 진입 — `memberRepository.findByIdForUpdate`로 회원 행을 비관적 잠금한다.
3. 소유자·알람 시간(±30분)·당일 중복을 다시 검증한다.
4. `MedicationProof`를 `saveAndFlush`로 저장한다. 유니크 제약 위반이면 `BAD_REQUEST`로 바꿔 던진다.
5. `member.incrementMedicationAlarmRevision()` — 앱 로컬 알람 동기화용 revision을 올린다 (LLD-0037).
6. `calculateEarnedPoints` — 저장 후 당일 인증 수와 그날 유효 스케줄 수로 이번 건의 증분을 구한다.
7. 증분이 0보다 크고 `member.getSeniorProfile() != null`이면 `seniorProfileService.addPointsToMember(memberId, earnedPoints, "약 복용 인증", "MEDICATION_PROOF:{proofId}")`를 호출한다.
8. 같은 영속성 컨텍스트라 `member.getSeniorProfile().getPoints()`가 적립된 값이다. 이 값으로 `MedicationProofResponse.of(...)`를 만들어 반환한다.
9. 트랜잭션 커밋. 실패하면 `MedicationProofService`가 업로드한 S3 객체를 보상 삭제한다.

**트랜잭션 경계**: 2~9가 하나의 트랜잭션이다. `addPointsToMember`는 `@Transactional`(REQUIRED)이라 이 트랜잭션에 참여하므로, 인증 저장과 포인트 적립은 함께 커밋되거나 함께 롤백된다.

**재시도 정책**: 이 경로에는 `@RetryOnPointConflict`를 붙이지 않는다. 재시도는 자신이 최외곽 트랜잭션인 진입점에서만 유효한데, 여기서는 이미 트랜잭션 밖에서 S3 업로드가 끝나 서버가 재실행할 수 없다. `AlbumUnlockService.unlockAlbum`과 같은 정책이다 (LLD-0003 §8).

**이벤트·Facade**: 사용하지 않는다.

## 6. 예외 / 에러 처리

| 상황 | 예외 | 응답 |
| --- | --- | --- |
| 알람 시간 ±30분 밖 인증 | `BusinessException(BAD_REQUEST)` | 400 |
| 당일 같은 스케줄 재인증 (선검증·유니크 제약 양쪽) | `BusinessException(BAD_REQUEST)` | 400 |
| 그날 유효하지 않은 스케줄 | `BusinessException(BAD_REQUEST)` | 400 |
| 타인 스케줄 인증 | `BusinessException(FORBIDDEN)` | 403 |
| 포인트 잔액 낙관적 락 충돌 | `ObjectOptimisticLockingFailureException` | 409 `POINT_CONCURRENT_UPDATE` |
| S3 업로드 실패 | `BusinessException(FILE_UPLOAD_FAILED)` | 500 |

409는 이 엔드포인트에 새로 생긴 실패 모드다. 인증 저장이 함께 롤백되므로 클라이언트가 그대로 재시도하면 된다. 새 에러 코드는 만들지 않는다.

시니어 프로필이 없는 회원은 예외가 아니다. 경고 로그만 남기고 적립을 건너뛴다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 남은 일정이 있는 인증 시 `addPointsToMember(memberId, 10, "약 복용 인증", "MEDICATION_PROOF:{proofId}")`가 호출되고 응답 `earnedPoints=10`이다.
      → `남은_일정이_있는_인증을_저장하면_10포인트를_즉시_적립한다`
- [x] 그날 마지막 일정 인증 시 30p(10+보너스 20)가 한 번에 적립된다.
      → `마지막_남은_일정을_인증하면_보너스를_더한_30포인트를_한_번에_적립한다`
- [x] `SeniorProfile` 없는 회원은 인증은 성공하고 적립 호출 없이 `currentPoints=0`을 반환한다.
      → `시니어_프로필이_없으면_적립_없이_현재_포인트를_0으로_반환한다`
- [x] 같은 스케줄을 같은 날 두 번 인증하면 두 번째는 BAD_REQUEST이고 적립은 1회다.
      → `오늘_이미_인증한_스케줄을_다시_인증하면_적립하지_않는다`, `중복_인증_저장에_실패하면_revision을_증가시키지_않는다`
- [x] `MedicineScheduleRewardScheduler` 클래스가 존재하지 않는다.
- [x] Swagger에 성공/주요 예외 응답이 반영된다. (인증 응답 설명이 즉시 적립을 반영하고 409를 명시한다)
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **DB 변경 없음.** 테이블·컬럼·ENUM 모두 그대로다.
- **삭제**: `MedicineScheduleRewardScheduler`와 그 단위 테스트, `MedicationProofRepository.findDistinctMembersByVerifiedAtBetween`.
- **유지**: `MedicationPointPolicy.calculateDailyPoints`는 `calculateEarnedPoints`가 증분 계산에 쓰므로 남긴다. 클래스 주석의 "자정 정산" 문구만 즉시 적립 기준으로 고친다.
- **문서**: LLD-0008 §10의 `MedicineScheduleRewardScheduler` 참조를 `MedicationProofTransactionService`로 바꾼다.
- **API 계약**: 경로·필드는 그대로고 `currentPoints`의 의미만 바뀐다. 클라이언트가 "응답 잔액 + earnedPoints"로 표시 잔액을 직접 계산하고 있었다면 이중 가산이 되므로, 앱은 `currentPoints`를 그대로 표시하도록 맞춘다.

### 전환 리스크 (배포 시점)

배포 당일 자정 이전에 들어온 인증분은 배치가 사라져 적립되지 않는다. 즉시 적립은 배포 이후 인증부터 적용되기 때문이다.

- **권장**: 자정 직후에 배포한다. 미적립 구간이 사실상 없어진다.
- **차선**: 낮에 배포했다면 배포 시각 이전 당일 인증분을 1회 수동 정산한다. `PointHistory`는 `operationKey` 없이도 INSERT할 수 있으므로 `SeniorProfileService.addPointsToMember(memberId, points, "약 복용 인증 수동 정산")`로 보정한다.

## 9. 미결정 사항 (Open Questions)

- 같은 회원의 마지막 두 인증이 동시에 커밋되면 보너스 20p가 누락될 수 있다는 우려가 있었으나, `verifyMedication`이 첫 문장에서 `memberRepository.findByIdForUpdate`(PESSIMISTIC_WRITE)로 회원 행을 잠그므로 같은 회원의 인증은 직렬화되어 현재 구조에서는 발생하지 않는다. 회원 락을 거치지 않는 인증 경로가 생기면 `operationKey`를 일자 단위 보너스 키(`MEDICATION_BONUS:{memberId}:{date}`)로 분리하는 방식을 재검토한다.

## 10. 참고

- [LLD-0003](LLD-0003-payment-points.md) §8 — 포인트 낙관적 락과 재시도 경계 정책
- [LLD-0007](LLD-0007-medicine-daily-status.md) — 약 복용 일자별 조회와 복용 상태
- [LLD-0008](LLD-0008-medicine-schedule-versioning.md) — 그날 유효 스케줄 판정
- [LLD-0037](LLD-0037-medication-alarm-sync.md) — 인증 시 revision 증가
- Issue #622
