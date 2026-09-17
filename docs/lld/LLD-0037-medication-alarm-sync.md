# LLD-0037: 복약 알람 스냅샷 동기화와 revision FCM

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #619 |
| 작성일 | 2026-09-16 |

## 1. 목적

앱은 FCM을 알람 원문이 아닌 설정 변경 신호로 받고 최신 복약 스냅샷을 조회해 OS 로컬 알람을 적용한다. FCM의 유실·중복·순서 역전은 API 스냅샷과 단조 증가 revision으로 흡수한다.

## 2. 범위

- 회원별 `medicationAlarmRevision`을 영속화한다.
- 본인 복약 알람 스냅샷 조회 API와 Swagger 문서를 추가한다.
- 복약 스케줄 생성·수정·삭제 및 복용 인증 성공 후 revision을 증가시킨다.
- 과거부터 유효한 스케줄을 당일 수정해 새 버전을 만들 때는 그날의 복용 인증을 새 버전으로 이관한다.
- 스케줄 설정 변경 시에만 `MEDICATION_SCHEDULE_CHANGED`와 revision을 FCM data payload로 enqueue한다.
- 기존 정시·재알림 FCM 스케줄러는 변경하지 않는다.

## 3. API

`GET /api/v1/goals/medicine-schedules/alarm-sync` (`MEDICINE_2010`)

```json
{"revision":42,"timeZone":"Asia/Seoul","schedules":[{"scheduleId":15,"alarmTime":"19:27","doseCount":2}],"completed":["15:2026-09-16"]}
```

`schedules`는 API timeZone 기준 오늘 유효한 ACTIVE 스케줄이며, `doseCount`는 해당 시각의 복용 수량이다. `completed`는 같은 timeZone 기준 오늘 인증된 `scheduleId:YYYY-MM-DD`다.

FCM data payload: `{ "type": "MEDICATION_SCHEDULE_CHANGED", "revision": "42" }`.

## 4. 데이터와 트랜잭션

`Member.medicationAlarmRevision BIGINT NOT NULL DEFAULT 0`을 추가한다. schedule CRUD와 proof 저장은 같은 트랜잭션에서 해당 회원 revision을 증가시킨다. revision을 바꾸는 명령은 회원 행을 `PESSIMISTIC_WRITE`로 잠가 동시 변경을 직렬화한다. 복용 인증은 업로드 전에 잠금 없는 읽기 전용 트랜잭션에서 소유권·시간·중복을 1차 검증하고, 이미지는 잠금 트랜잭션 밖에서 업로드한다. 업로드 후의 별도 트랜잭션에서 같은 조건을 다시 검증한 뒤 저장한다. 최종 검증이나 저장이 실패하면 업로드한 이미지를 삭제한다. 설정 변경 FCM outbox는 revision 증가와 같은 트랜잭션에서 enqueue한다.

## 5. 인수조건

- 스냅샷은 오늘 유효한 스케줄, 총 복용 수량, 오늘 완료 키와 revision을 반환한다.
- 스케줄 CRUD는 revision을 1 증가시키고 FCM에 type/revision을 넣는다.
- 당일 인증 후 스케줄을 새 버전으로 수정해도 completed와 기존 정시 FCM 스케줄러는 새 scheduleId를 완료 처리한다.
- 복용 인증은 revision을 1 증가시키지만 설정 변경 FCM은 보내지 않는다.
- `Asia/Seoul`을 반환하고 completed 날짜도 그 기준으로 계산한다.

## 6. 배포 마이그레이션

운영 환경은 Hibernate `validate`를 사용하므로 배포 전에 `scripts/mysql/add_medication_alarm_sync.sql`을 1회 실행한다.

```sql
ALTER TABLE member ADD COLUMN medication_alarm_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fcm_outbox ADD COLUMN data_type VARCHAR(100) NULL;
ALTER TABLE fcm_outbox ADD COLUMN data_revision BIGINT NULL;
```

## 7. 운영상 제약

- 사용자가 `MEDICINE_SCHEDULE` 알림 설정을 끄면 변경 FCM도 수신하지 않는다. 앱이 포그라운드가 될 때 스냅샷을 재조회해 이를 보완한다.

## 미결정 사항(Open Questions)

- 없음. 일본 실증의 회원별 timeZone은 별도 정책 확정 전까지 이 API의 `Asia/Seoul` 기본값을 바꾸지 않는다.
