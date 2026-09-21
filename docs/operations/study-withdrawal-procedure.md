# 실증 참여 철회·삭제 수동 처리 절차

| 항목 | 값 |
| --- | --- |
| 근거 | 데이터정책서 v1.1 B 1.5.8, 작업지시서 L6, ADR-0034, LLD-0052 |
| 적용 | 국내 실증 기간. 상용 배포 전에는 앱·웹 삭제 요청 경로로 대체한다 |
| 담당 | 실증 운영 담당(접수·기록), 서버 운영 담당(삭제 실행), 연구 책임자(반출본·종이 동의서) |
| 작성 | 2026-09-21, #667 |

실증 기간에는 자동 삭제가 없다. 철회가 접수되면 `participation_id`로 대상 회차와 사본을 확정한 뒤 이 문서 순서대로 사람이 지운다. 삭제 대상을 찾는 인덱스 행을 먼저 지우면 남은 원문을 찾을 수 없으므로, **삭제 전 대상 식별자를 같은 MySQL 세션의 임시 테이블에 보존하고 외부 사본 삭제를 확인한 뒤 RDB를 지운다.** 처리 완료 시각은 모든 대상의 사후 검증이 끝난 뒤에만 기록한다.

## 0. 실행 전 조건과 철회 범위

### 0.1 배포 전제

다음 스키마가 운영에 반영됐는지 먼저 확인한다.

- #659: `study_participation`과 `collection_run.study_participation_id`
- #669: `decision_record`, `fcm_outbox.decision_id`, `fcm_notification.decision_id`
- #674: `incident`. 아직 배포 전이면 아래 SQL에서 `incident` 캡처·삭제·검증만 건너뛴다

열이나 테이블이 없는데 SQL을 그대로 실행하지 않는다. `SHOW TABLES`와 `SHOW COLUMNS`로 실제 운영 스키마를 확인하고 결과를 대장에 남긴다.

### 0.2 전체 철회와 일부 철회

`ALL`은 해당 참여 기록에 연결된 연구 자료 전체를 지운다. 회원의 일반 서비스 자료와 회원 계정은 지우지 않는다.

`SELECTED_CONSENTS`는 아래 확정된 대응표만 사용한다.

| 연구 선택동의 키 | 삭제할 연구 자료 | 함께 무효화할 사본 |
| --- | --- | --- |
| `guardian_location_access` | 대상 회차의 `location_fix` | 해당 회차의 기존 내보내기 zip과 `run_export` 행 |
| `ecg` | 대상 회차에서 `sensor_batch.stream = 'ecg'`인 배치와 S3 원문 | 해당 회차의 기존 내보내기 zip과 `run_export` 행 |

표에 없는 동의 키는 삭제 범위를 추정하지 않는다. 운영 담당은 **삭제 단계로 진행하지 않고** 연구 책임자에게 스트림 대응을 확인한 뒤 이 표를 먼저 개정한다. 일부 철회에서는 `collection_run`, 참여 기록, 표에 없는 센서·심박·위치·판정·인시던트를 지우지 않는다.

다음 두 사항은 연구 책임자 결정이 필요하다.

| 항목 | 결정 전 처리 |
| --- | --- |
| 재식별 연결키(실명 ↔ 가명 대응표)도 함께 지우는가 | 지우지 않고 서버 밖 보관 담당에게 「철회됨」 표시만 요청한다 |
| 철회 전에 만든 가명 연구본을 보존하는가 | 별도 승인 근거가 없으면 삭제 대상으로 본다 |

## 1. 접수

1. 관리자 API로 참여 기록을 철회 상태로 바꾼다. 시각·범위·선택 항목과 처리 관리자가 변경 이력과 감사 로그에 남는다.

```http
POST /api/v1/admin/studies/participations/{participationId}/withdrawal
{"scope":"ALL"}
```

```http
POST /api/v1/admin/studies/participations/{participationId}/withdrawal
{"scope":"SELECTED_CONSENTS","consentKeys":["guardian_location_access"]}
```

2. 응답의 `withdrawnAt`을 요청자에게 알리고, 물리 삭제 완료 전이라는 점을 함께 안내한다.
3. 같은 참여 기록에 대해 새 연구 회차가 열리지 않는지 확인한다.

## 2. 사본 목록

| 위치 | 무엇 | 찾는 기준 |
| --- | --- | --- |
| MySQL `collection_run` | 연구 회차 | `study_participation_id` |
| MySQL `sensor_batch` | IMU·심박·ECG 배치 인덱스 | `run_id` |
| S3 `sensor/...` | 배치 원문 | 삭제 전 캡처한 `sensor_batch.s3_key` |
| MySQL `heart_rate_event`, `heart_rate_emergency` | 배치 심박·위급 | 삭제 전 캡처한 `batch_id`, 회원·측정시각 |
| MySQL `location_fix`, `device_heartbeat` | 위치·기기 상태 | `run_id` |
| MySQL `clock_mapping`, `run_marker`, `run_device_assignment` | 시계 기준점·마커·배정 | 삭제 전 캡처한 ID. 다른 회차가 공유하는 시계 기준점은 보존 |
| MySQL `decision_record`, `incident` | 판정·본인확인·사후 판정 | `run_id`, `decision_id` |
| MySQL `run_export`, S3 `exports/...` | 내보내기 잡·zip | `run_id`, 삭제 전 캡처한 `s3_key` |
| MySQL `fcm_outbox`, `fcm_notification` | 연구 판정에서 나온 알림 행 | 삭제 전 캡처한 `decision_id` |
| 연구자 반출본 | 내려받은 zip과 복사본 | 반출 대장 |
| 종이 동의서·가명 대응표 | 서버 밖 원본 | 연구 책임자와 대응표 보관 담당 |
| DB 백업 | RDS 자동 스냅샷 | 현재 보존 주기. 만료 전 복구하면 이 절차를 다시 실행 |

Redis의 `location:stay:{memberId}`, `location:trail:{memberId}`, `HeartRateResult`는 연구 회차 전용이 아니라 같은 회원의 일반 서비스 자료가 섞인 공용 캐시다. **연구 철회에서는 삭제하지 않는다.** 회원 전체 탈퇴 절차에서만 별도로 지운다. 현재 TTL은 `location:trail` 15분, `location:stay`와 `HeartRateResult` 24시간이다.

## 3. 삭제 대상 확정

아래 작업은 한 MySQL 세션에서 수행한다. 실제 열 이름은 운영 `SHOW COLUMNS` 결과와 대조한다. 임시 테이블의 건수와 내용을 삭제 대장에 저장하되 건강값·좌표·알림 본문은 대장에 복사하지 않는다.

```sql
-- 참여 기록과 회차. :pid에는 접수한 participation_id를 넣는다.
CREATE TEMPORARY TABLE wd_runs AS
SELECT r.id AS collection_run_id, r.run_id
FROM collection_run r
JOIN study_participation p
  ON p.study_participation_id = r.study_participation_id
WHERE p.participation_id = :pid;

-- 전체 철회용 배치. 일부 ECG 철회면 마지막 조건을 AND b.stream = 'ecg'로 좁힌다.
CREATE TEMPORARY TABLE wd_batches AS
SELECT b.batch_id, b.s3_key, b.clock_mapping_id
FROM sensor_batch b JOIN wd_runs r ON r.run_id = b.run_id;

CREATE TEMPORARY TABLE wd_heart_events AS
SELECT h.heart_rate_event_id, h.member_id, h.measured_at
FROM heart_rate_event h JOIN wd_batches b ON b.batch_id = h.batch_id;

-- heart_rate_emergency에는 run_id와 batch_id가 없으므로 회원·측정시각으로 대상 ID를 먼저 고정한다.
CREATE TEMPORARY TABLE wd_heart_emergencies AS
SELECT e.heart_rate_emergency_id
FROM heart_rate_emergency e
JOIN wd_heart_events h
  ON h.member_id = e.member_id AND h.measured_at = e.measured_at;

CREATE TEMPORARY TABLE wd_decisions AS
SELECT d.decision_id
FROM decision_record d JOIN wd_runs r ON r.run_id = d.run_id;

-- #674가 배포된 경우에만 실행한다.
CREATE TEMPORARY TABLE wd_incidents AS
SELECT i.incident_id
FROM incident i JOIN wd_decisions d ON d.decision_id = i.decision_id;

CREATE TEMPORARY TABLE wd_exports AS
SELECT e.id, e.s3_key
FROM run_export e JOIN wd_runs r ON r.run_id = e.run_id;

CREATE TEMPORARY TABLE wd_location_fixes AS
SELECT l.location_fix_id
FROM location_fix l JOIN wd_runs r ON r.run_id = l.run_id;

CREATE TEMPORARY TABLE wd_heartbeats AS
SELECT h.device_heartbeat_id
FROM device_heartbeat h JOIN wd_runs r ON r.run_id = h.run_id;

CREATE TEMPORARY TABLE wd_markers AS
SELECT m.id, m.clock_mapping_id
FROM run_marker m JOIN wd_runs r ON r.collection_run_id = m.run_id;

CREATE TEMPORARY TABLE wd_assignments AS
SELECT a.id
FROM run_device_assignment a JOIN wd_runs r ON r.collection_run_id = a.run_id;

CREATE TEMPORARY TABLE wd_clock_candidates AS
SELECT DISTINCT clock_mapping_id FROM wd_batches
UNION
SELECT DISTINCT clock_mapping_id FROM wd_markers;

CREATE TEMPORARY TABLE wd_fcm_outbox AS
SELECT f.id FROM fcm_outbox f JOIN wd_decisions d ON d.decision_id = f.decision_id;
CREATE TEMPORARY TABLE wd_fcm_notifications AS
SELECT f.id FROM fcm_notification f JOIN wd_decisions d ON d.decision_id = f.decision_id;
```

`clock_mapping`은 다른 배치나 마커가 같은 값을 참조하면 공유 자료이므로 삭제 대상에서 제외한다. 대상 배치·마커를 지운 뒤 `wd_clock_candidates` 중 남은 참조가 0인 값만 삭제한다.

일부 위치 철회는 `location_fix` PK와 `run_export`만 캡처한다. 일부 ECG 철회는 `stream='ecg'`인 배치·S3 키와 `run_export`만 캡처한다. 위 전체 철회용 심박·판정·인시던트 캡처 SQL을 실행하지 않는다.

## 4. 외부 사본 삭제

1. `wd_batches.s3_key` 전체를 입력으로 S3 원문을 삭제한다.
2. `delete-objects` 응답의 `Errors` 배열이 비어 있는지 확인한다. 오류가 하나라도 있으면 RDB 삭제를 시작하지 않는다.
3. 캡처한 **모든 키**에 `head-object`를 실행해 전부 404인지 확인한다. 표본 확인으로 대체하지 않는다.
4. `wd_exports.s3_key`가 있는 모든 zip도 같은 방식으로 삭제하고 `Errors` 없음과 전건 404를 확인한다.
5. 연구 책임자가 반출 대장의 모든 사본 삭제를 확인한다. 회신이 없으면 완료 처리하지 않는다.

S3 삭제가 일부 실패하면 `sensor_batch`와 `run_export` 인덱스 행을 보존한 채 실패 키만 재시도한다. 이 인덱스가 남은 객체를 찾는 유일한 기준이다.

## 5. RDB 삭제

외부 사본 전건 삭제가 확인된 뒤 트랜잭션을 시작한다. 각 DELETE는 임시 테이블의 PK나 식별자만 사용한다.

1. `fcm_notification`, `fcm_outbox` — `wd_fcm_notifications`, `wd_fcm_outbox`의 ID
2. `incident` — `wd_incidents`의 ID(#674 배포 시)
3. `decision_record` — `wd_decisions.decision_id`
4. `heart_rate_emergency` — `wd_heart_emergencies.heart_rate_emergency_id`
5. `heart_rate_event` — `wd_heart_events.heart_rate_event_id`
6. `sensor_batch` — `wd_batches.batch_id`
7. `location_fix`, `device_heartbeat`, `run_marker`, `run_device_assignment` — 각각 `wd_location_fixes`, `wd_heartbeats`, `wd_markers`, `wd_assignments`의 PK
8. `clock_mapping` — 캡처한 후보 중 다른 `sensor_batch`·`run_marker`가 더 이상 참조하지 않는 행만
9. `run_export` — `wd_exports.id`
10. `collection_run` — `wd_runs.run_id` (`ALL`일 때만)

일부 철회에서는 0.2절 대응표의 자료와 기존 내보내기만 지운다. 참여 기록·회차와 다른 스트림은 유지한다.

`study_participation`, `study_participation_consent`, `study_participation_withdrawal_item`, `study_participation_history`, 관리자 감사·접속기록은 삭제하지 않는다. 철회 접수와 처리 사실을 증명하는 기록이다.

## 6. 독립 사후 검증

삭제 뒤 원본 테이블을 임시 테이블의 식별자와 직접 대조한다. 삭제된 `sensor_batch`를 다시 서브쿼리해 대상을 찾으면 남은 심박 행이 있어도 거짓으로 0이 나오므로 금지한다.

```sql
SELECT COUNT(*) FROM sensor_batch s JOIN wd_batches w ON w.batch_id = s.batch_id;
SELECT COUNT(*) FROM heart_rate_event h JOIN wd_heart_events w ON w.heart_rate_event_id = h.heart_rate_event_id;
SELECT COUNT(*) FROM heart_rate_emergency h JOIN wd_heart_emergencies w ON w.heart_rate_emergency_id = h.heart_rate_emergency_id;
SELECT COUNT(*) FROM decision_record d JOIN wd_decisions w ON w.decision_id = d.decision_id;
SELECT COUNT(*) FROM run_export e JOIN wd_exports w ON w.id = e.id;
SELECT COUNT(*) FROM fcm_outbox f JOIN wd_fcm_outbox w ON w.id = f.id;
SELECT COUNT(*) FROM fcm_notification f JOIN wd_fcm_notifications w ON w.id = f.id;
-- #674가 배포된 경우
SELECT COUNT(*) FROM incident i JOIN wd_incidents w ON w.incident_id = i.incident_id;
SELECT COUNT(*) FROM location_fix l JOIN wd_location_fixes w ON w.location_fix_id = l.location_fix_id;
SELECT COUNT(*) FROM device_heartbeat h JOIN wd_heartbeats w ON w.device_heartbeat_id = h.device_heartbeat_id;
SELECT COUNT(*) FROM run_marker m JOIN wd_markers w ON w.id = m.id;
SELECT COUNT(*) FROM run_device_assignment a JOIN wd_assignments w ON w.id = a.id;
SELECT COUNT(*) FROM collection_run r JOIN wd_runs w ON w.collection_run_id = r.id;
```

삭제하기로 한 `clock_mapping`만 0인지 확인하고, 공유 참조 때문에 보존한 행은 그 사유와 참조 건수를 대장에 남긴다. 모든 삭제 대상의 결과가 0이고 S3 전건 404일 때만 완료다.

## 7. 완료 기록

```http
POST /api/v1/admin/studies/participations/{participationId}/deletion-processed
```

접수 일시, 처리자, 철회 범위, 삭제 전·후 건수, S3 전체 키 확인 결과, 반출본 회신, 공유 시계 매핑 보존 사유를 대장에 남긴다. 건강값·좌표·알림 본문은 남기지 않는다. 그 뒤 요청자에게 완료를 알린다.

## 8. 실증 투입 전 시험

시험 참여자 한 명에게 연구 회차와 각 스트림 자료, 심박 위급·판정·인시던트, 내보내기를 만든 뒤 `ALL` 절차를 실행한다. 별도 시험 참여자에게 위치 일부 철회를 실행해 위치와 내보내기만 사라지고 심박·IMU·회차가 유지되는지도 확인한다.

| 날짜 | 실행자 | 범위 | 결과 |
| --- | --- | --- | --- |
| — | — | `ALL` | 미실행 |
| — | — | `SELECTED_CONSENTS: guardian_location_access` | 미실행 |
