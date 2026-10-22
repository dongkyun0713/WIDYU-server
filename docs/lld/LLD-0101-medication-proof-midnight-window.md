# LLD-0101: 자정을 넘는 복약 인증 창과 복용 기준일

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (리뷰에서 확정된 결함 수정, 구현 지시 2026-10-23) |
| Issue | #870 |
| 관련 ADR | - |
| 작성자 | Claude |
| 작성일 | 2026-10-23 |

## 1. 목적 / 배경

복약 인증은 알람 시각 전후 30분 안에만 받는다(backend 복약 불변식, LLD-0007·0038). 기존 `MedicationProofTransactionService.validateScheduleTime`은 기준 알람을 항상 `now.toLocalDate().atTime(alarmTime)`, 즉 **오늘 날짜의 알람**으로 잡았다. 창이 자정을 넘으면 절반이 거부됐다.

- 알람 23:45의 창은 23:15~00:15이지만 00:00~00:15 인증은 오늘 23:45와 비교되어 400이었다.
- 알람 00:10의 창은 전날 23:40~00:40이지만 23:40~23:59 인증은 오늘 00:10과 비교되어 400이었다.

복약 알림 스케줄러는 알람 날짜 기준으로 +10·+20분 미인증 푸시를 보낸다. 23:45 알람이면 00:05에 인증을 요청하고 서버는 그 인증을 거절했다. 중복 검사와 `verified_date` 저장도 "지금 날짜"라, 창만 고치면 어제 알람의 인증이 오늘 분으로 저장된다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (goal/medicineschedule), widyu-domain (`MedicationProof`)
- 복용 기준일 규칙: 인증 시각이 알람 ±30분 안에 드는 알람의 날짜
- 유효 스케줄 검사, 중복 인증 검사, `verified_date` 저장, 즉시 적립의 하루 완료 판정을 복용 기준일로 통일
- `MedicationProof.create`가 인증 시각과 복용 기준일을 받는다(`@PrePersist` 파생 제거)

### Out of scope
- 읽기 쪽의 `verified_date` 전환 — #872(PR #875)에서 먼저 한다. 이 LLD는 그 PR 위에서 동작한다
- 알람 동기화 `completed`에 다음 날 기준일 인증을 넣는 것 — 9절
- 건강 일정의 같은 자정 경계 문제 — #869
- 인증 창 길이(30분)·메시지 문구 변경

## 3. 인터페이스 / API

HTTP API는 바뀌지 않는다. `POST /api/v1/goals/medicine-schedules/{scheduleId}/verify`가 받아들이는 시각 범위만 넓어진다(자정을 넘는 창의 나머지 절반). 오류 코드·메시지는 그대로다.

## 4. 데이터 모델

`medication_proof.verified_date`(DATE, NOT NULL, 기존 컬럼)의 의미를 "`verified_at`의 날짜"에서 **"복용 기준일(인증한 알람의 날짜)"**로 바꾼다. 스키마 변경은 없다. 자정을 넘는 창의 인증만 두 값이 하루 다르고, 그 밖에는 같다. 기존 행은 모두 `verified_date = DATE(verified_at)`이다.

`MedicationProof.create(schedule, member, imageUrls, verifiedAt, verifiedDate)`로 두 값을 서비스가 넘긴다. `@PrePersist` 파생은 없앤다(값이 둘로 갈라질 수 있어 파생할 수 없다).

ERD-0001의 `MedicationProof`에 `verifiedDate`와 의미를 적는다.

## 5. 처리 흐름

### 5-1. 복용 기준일 (`MedicationProofTransactionService.resolveDoseDate`)

```
for d in [오늘-1, 오늘, 오늘+1]:          // now.toLocalDate() 기준
    alarmAt = d.atTime(schedule.alarmTime)
    if alarmAt - 30분 <= now <= alarmAt + 30분:
        schedule.isEffectiveOn(d)가 아니면 400 "오늘 유효하지 않은 약 복용 스케줄입니다."
        return d
400 "약 복용 인증은 알람 시간 전후 30분 이내에만 가능합니다."
```

창 길이(60분)가 하루보다 훨씬 짧아 맞는 날짜는 많아야 하나다. 경계는 기존과 같이 양 끝을 포함한다.

| 알람 | 인증 시각 | 복용 기준일 |
| --- | --- | --- |
| 23:45 | 8/11 00:05 | 8/10 |
| 00:10 | 8/10 23:50 | 8/11 |
| 08:00 | 8/10 08:20 | 8/10 (기존과 같음) |
| 23:45 | 8/11 00:16 | 거부 |

### 5-2. 인증 (업로드 전 1차 검증·업로드 후 저장 모두)

1. 소유자 검증
2. `resolveDoseDate(schedule, now)` — 유효 스케줄 검사 포함
3. 중복 검사 `existsByMedicineScheduleAndVerifiedDate(schedule, doseDate)`
4. (저장 단계) `MedicationProof.create(..., now, doseDate)` → `saveAndFlush`. 유일 제약 `(medicine_schedule_id, verified_date)`이 같은 알람 날짜의 동시 중복을 막는다
5. (저장 단계) 즉시 적립: `countByMemberAndVerifiedDate(member, doseDate)`와 `countEffectiveByMemberAndDate(member, ACTIVE, doseDate)`로 그 알람 날짜의 증분을 계산한다(LLD-0038의 계산식 그대로)

트랜잭션 경계는 LLD-0037·0038과 같다. 테스트가 시각을 고정할 수 있도록 public 메서드는 `LocalDateTime.now()`를 넘기는 패키지 전용 메서드(`validateBeforeUploadAt`, `verifyMedicationAt`)를 부른다.

### 5-3. 읽기

#872 이후 읽는 쪽(일자별·월별 현황, 목표 홈, 시니어·보호자 홈, 복약 알림 스케줄러, 알람 동기화)은 `verified_date`로 날짜를 판정한다. 따라서 8/11 00:05에 한 8/10 23:45 알람 인증은 8/10의 완료로 보이고, 8/10 23:45 알람의 +30분(00:15) 보호자 미복용 알림이 나가지 않으며, 8/11 23:45 스케줄은 그날 저녁까지 미완료다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
|------|------|
| 어떤 날짜의 알람 ±30분에도 들지 않음 | 400 "약 복용 인증은 알람 시간 전후 30분 이내에만 가능합니다." (기존 문구) |
| 맞는 알람 날짜에 스케줄이 유효하지 않음 | 400 "오늘 유효하지 않은 약 복용 스케줄입니다." (기존 문구) |
| 같은 알람 날짜에 이미 인증 | 400 "오늘은 이미 해당 약 복용을 인증했습니다." (기존 문구, 선검증·유일 제약 양쪽) |

검사 순서가 "유효일 → 시간창"에서 "시간창 → 유효일"로 바뀐다. 유효일은 시간창이 정한 날짜로만 판단할 수 있기 때문이다. 창 밖이면서 유효하지 않은 스케줄은 이제 시간창 오류 문구를 받는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 23:45 알람을 다음 날 00:05에 인증하면 허용하고 `verified_date`는 전날, `verified_at`은 인증 시각이다 — `자정_전_알람을_자정_뒤에_인증하면_전날_분으로_저장한다`
- [x] 00:10 알람을 전날 23:50에 인증하면 허용하고 `verified_date`는 알람 날짜다 — `자정_뒤_알람을_자정_전에_인증하면_알람_날짜_분으로_저장한다`
- [x] 알람 ±30분 밖(23:45 알람의 00:16, 00:10 알람의 23:39)은 거부한다 — `자정을_넘는_알람이라도_전후_30분_밖이면_인증할_수_없다`
- [x] 중복 검사는 복용 기준일로 한다 — `자정_뒤에_전날_알람을_인증할_때_전날_분이_있으면_중복으로_거부한다`
- [x] 즉시 적립의 인증 수·유효 스케줄 수를 복용 기준일로 센다 — 위 첫 두 테스트(엄격한 스텁 인자)
- [x] 기존 인증·적립·중복 테스트(LLD-0038 7절)가 통과한다
- [x] Swagger 인증 설명에 자정을 넘는 창 처리를 적는다 — `MedicineScheduleDocs`
- [x] 읽기 쪽은 복용 기준일로 날짜를 판정한다 — #872 `날짜_조회는_인증_시각이_아니라_복용_기준일로_그날의_인증을_고른다`

## 8. 영향 범위 / 마이그레이션

- 스키마·데이터 변경 없음. 기존 행은 그대로 유효하다.
- **#872(PR #875)가 먼저 배포돼야 한다.** 이 변경만 배포하면 자정을 넘어 저장된 인증을 읽는 쪽이 `verified_at` 날짜로 읽어 다음 날 분으로 보인다. 이 PR은 #872 브랜치 위에 쌓았다.
- `MedicationProof.create` 시그니처 변경으로 테스트의 생성 호출 4곳을 맞췄다.

## 9. 미결정 사항 (Open Questions)

- [ ] 알람 동기화(`GET /alarm-sync`, LLD-0037)의 `completed`는 "오늘(Asia/Seoul) 인증된 `scheduleId:날짜`"다. 00:30 이전 알람을 전날 밤(23:30 이후)에 인증하면 복용 기준일이 다음 날이라 `completed`에 들어가지 않고, 앱이 그 사이 다시 동기화하지 않으면 기기 알람이 그대로 울릴 수 있다. 서버 푸시(+10·+20분, 보호자 +30분)는 `verified_date`로 걸러져 나가지 않는다. `completed`에 다음 날 기준일 키를 함께 넣을지는 앱 계약 변경이라 앱과 정한다.

## 10. 참고

- LLD-0007(일자별 복용 상태), LLD-0037(알람 동기화), LLD-0038(즉시 적립)
- #872 / PR #875: 읽기 쪽 `verified_date` 전환과 `(member_id, verified_date)` 인덱스
