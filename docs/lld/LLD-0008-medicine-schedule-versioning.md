# LLD-0008: 약 복용 스케줄 버전링(수정 시 과거 보존)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #380 |
| 관련 ADR | - |
| 관련 LLD | LLD-0007(약 복용 일자별 조회) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-09 |

## 1. 목적 / 배경

약 복용 스케줄을 수정하면 **오늘 이전(과거) 날짜의 일자별 조회 표시까지 함께 바뀌는** 문제가 있었다.
`MedicineSchedule`은 날짜별 인스턴스가 아니라 반복 템플릿 1개(alarmTime + categories)이고, 일자별 조회가 이 템플릿 하나로 과거·미래 모든 날짜를 렌더링했다. 수정은 템플릿을 그대로 변경하므로 과거 표시가 소급 변경됐다.
수정은 **오늘부터 적용**되고 과거 날짜는 **수정 전 상태로 보존**되도록 스케줄에 적용 기간(effective range)을 도입한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-domain(엔티티), widyu-api(medicineschedule, home, fcm 리스너, 스케줄러)
- `MedicineSchedule`에 `effectiveFrom`(적용 시작일) / `effectiveTo`(종료일, null=현재 유효) 추가
- 조회: 날짜 D 기준 `effectiveFrom <= D <= (effectiveTo ?? ∞)`인 버전만 반환
- 수정: 과거부터 유효한 버전은 어제까지 마감(`effectiveTo = 어제`) + 오늘부터 유효한 새 버전 생성. 당일 생성/수정분은 in-place 수정
- 삭제: `effectiveTo = 어제`로 마감(오늘부터 중단, 과거 보존)
- 월별 달성률: 분모(하루 총 스케줄 수)를 **그날 유효했던 스케줄 수**로 계산(버전링 도입에 따른 정합성 반영). LLD-0007의 "월별 통계 out of scope"는 #357 한정이며, 본 변경(#380)에서 버전링 정합성 목적으로 포함한다.
- 복약 알림(FCM)·포인트 정산도 "그날 유효한 스케줄"만 대상으로 정합화

### Out of scope
- 복용 인증(`MedicationProof`) 제출 로직·시간창 규칙
- 일자별 조회 응답 필드 구조(LLD-0007 유지)
- 클라이언트 UI

## 3. 인터페이스 / API

기존 엔드포인트 계약(경로·요청·응답 필드) 변경 없음. 동작만 아래로 바뀐다.

- `GET /daily?date=` — 그 날짜에 유효했던 버전만 반환
- `PATCH /{scheduleId}` — 과거 유효분이면 새 버전 생성(응답은 기존과 동일한 `Void`; 클라이언트는 재조회로 최신 id를 얻는다)
- `DELETE /{scheduleId}` — 오늘부터 중단, 과거 보존
- `GET /home`, `GET /monthly`, 목표 홈(`goal/home`), 시니어/보호자 홈도 위 규칙을 따른다

## 4. 데이터 모델

`MedicineSchedule`(widyu-domain)에 컬럼 2개 추가:

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `effective_from` | DATE | 이 버전이 유효한 시작일. 생성 시 오늘. 기존 데이터는 시작 시 `created_at` 기준으로 보정 |
| `effective_to` | DATE, NULL | 유효 종료일. null이면 현재 유효한 최신 버전 |

- 도메인 메서드: `isEffectiveOn(date)`, `closeAsOf(date)`, `startedOn(date)`, `clearCategories()`
- 기존 `status`는 유지(모든 버전 ACTIVE). 삭제/수정 마감은 `effectiveTo`로 표현한다.
- `MedicationProof`는 기존대로 스케줄 id를 참조하며, 과거 인증은 마감된 옛 버전 row에 그대로 연결되어 보존된다.

## 5. 처리 흐름

**수정** `MedicineScheduleService.updateSchedule`
```
1. 스케줄 조회 + 소유자 검증
2. today = LocalDate.now()
3. schedule.startedOn(today)면 → in-place (alarmTime·categories 갱신) 후 종료
4. 아니면 → schedule.closeAsOf(today.minusDays(1))
5. 새 MedicineSchedule.create(member, alarmTime) (effectiveFrom=today) + categories 구성 후 save
```

**삭제** — 소유자 검증 후 `schedule.closeAsOf(today.minusDays(1))` (당일 생성분은 유효 구간이 비어 어디에도 노출되지 않음)

**월별 달성률** — 달과 겹치는 모든 버전을 조회해, 각 날짜별로 `isEffectiveOn(date)` 개수를 분모로 계산

## 6. 예외 / 에러 처리

기존과 동일(`BAD_REQUEST`: 없는 스케줄, `FORBIDDEN`: 소유자 아님). 신규 에러 코드 없음.

## 7. 인수조건 (Acceptance Criteria)

- [x] 과거부터 유효한 스케줄을 수정하면 기존 버전은 어제까지 마감되고 오늘부터 유효한 새 버전이 생성된다.
- [x] 당일 생성/수정된 스케줄을 수정하면 새 버전 없이 in-place로 수정된다.
- [x] 일자별 조회는 그 날짜에 유효했던 버전만 반환한다(과거 표시 보존).
- [x] 삭제는 어제까지 마감되어 오늘부터 중단되고 과거 날짜에는 보존된다.
- [x] 월별 달성률의 분모가 그날 유효했던 스케줄 수로 계산된다(스케줄 시작 전 날짜는 0).
- [x] 복약 알림·포인트 정산이 그날 유효한 스케줄만 대상으로 한다.
- [x] 엔티티 유효기간 경계 + 서비스 수정/삭제/월별 단위 테스트가 존재한다.

## 8. 영향 범위 / 마이그레이션

**DB 마이그레이션** — 기존 행은 애플리케이션 시작 시 `created_at` 기준으로 `effective_from`을 보정한다. 수동 보정이 필요한 환경에서는 아래 SQL을 사용할 수 있다.
```sql
ALTER TABLE medicine_schedule ADD COLUMN effective_from DATE NULL, ADD COLUMN effective_to DATE NULL;
UPDATE medicine_schedule SET effective_from = DATE(created_at) WHERE effective_from IS NULL;
```
- ERD(`ERD-0001-initial-domain.md`) 동기화 완료.
- API 계약(경로·필드) 변경 없음. 수정 시 새 버전 id가 생기므로 클라이언트는 수정 후 재조회한다.

## 9. 미결정 사항 (Open Questions)

- 같은 스케줄을 하루에 여러 번 수정하는 경우 in-place로 처리(현재 정책). 추후 이력 보관 필요 시 재검토.

## 10. 참고

- `MedicineSchedule`(widyu-domain): 유효기간 필드·메서드
- `MedicineScheduleService`: 수정/삭제/일자별/월별
- `MedicineScheduleRepository`: 유효기간 기반 쿼리
- `MedicineScheduleNotificationListener`: 그날 유효 스케줄 정합화
- `MedicationProofTransactionService`: 인증 즉시 적립 시 그날 유효 스케줄 수로 보너스 판정 (LLD-0038)
- Issue #380
