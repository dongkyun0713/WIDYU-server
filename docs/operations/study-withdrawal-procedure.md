# 실증 참여 철회·삭제 수동 처리 절차

| 항목 | 값 |
| --- | --- |
| 근거 | 데이터정책서 v1.1 B 1.5.8, 작업지시서 L6, ADR-0034, LLD-0052 |
| 적용 | 국내 실증 기간. 상용 배포 전에는 앱·웹 삭제 요청 경로로 대체한다 |
| 담당 | 실증 운영 담당(접수·기록), 서버 운영 담당(삭제 실행), 연구 책임자(반출본·종이 동의서) |
| 작성 | 2026-09-21, #667 |

실증 기간에는 자동 삭제가 없다. 이 문서의 순서대로 사람이 지운다. 열쇠는 `participation_id` 하나다. 참여 기록(`study_participation`)이 접수 대장이고, 처리 완료 시각을 그 기록에 남기는 것으로 절차가 끝난다.

## 0. 먼저 정할 것 (확인 필요)

| 항목 | 상태 | 결정 전 처리 |
| --- | --- | --- |
| 재식별 연결키(실명 ↔ 가명 대응표)도 함께 지우는가 | **확인 필요** (대표 결정) | 지우지 않고 보관 담당에게 「철회됨」 표시만 전달한다 |
| 철회 **전**에 모은 자료를 가명 연구본으로 남기는가 | **확인 필요** (동의서·IRB 승인 범위) | 남기지 않는다. 전부 삭제 대상으로 본다 |
| 일부 항목만 철회(`SELECTED_CONSENTS`)했을 때 어느 스트림을 지우는가 | 동의 항목 ↔ 스트림 대응표 미정 | 위치 항목 철회 = `location_fix`, 심전도 = 해당 스트림. 나머지는 전부 철회와 같이 처리 |

## 1. 접수

1. 철회·삭제 요청을 받으면 관리자 API로 참여 기록을 철회 상태로 바꾼다. 이것이 접수 기록이다(시각·범위가 남고 감사 로그에 누가 했는지 남는다).

```http
POST /api/v1/admin/studies/participations/{participationId}/withdrawal
{"scope": "ALL"}            또는  {"scope": "SELECTED_CONSENTS", "consentKeys": ["guardian_location_access"]}
```

2. 응답의 `withdrawnAt`을 요청자에게 알린다. 삭제는 아직 하지 않았다는 것도 함께 말한다.

## 2. 사본 목록

한 사람의 자료가 있을 수 있는 곳 전부다. **여기 없는 곳에 사본이 있으면 이 문서를 먼저 고친다.**

| 위치 | 무엇 | 찾는 열쇠 |
| --- | --- | --- |
| MySQL `collection_run` | 회차 | `study_participation_id` = 참여 기록 PK |
| MySQL `sensor_batch` | IMU·심박 배치 인덱스 행 | `run_id` ∈ 위 회차 |
| S3 `sensor/{memberId}/{deviceId}/{stream}/{batchId}-{sha256}.json` | 배치 원문 | `sensor_batch.s3_key` |
| MySQL `heart_rate_event`, `heart_rate_emergency`, Redis `HeartRateResult` | 심박 샘플·위급·최신값 | `member_id`, `batch_id` |
| MySQL `location_fix` | 위치 원본 | `run_id`, `member_id` |
| Redis `location:stay:{memberId}`, `location:trail:{memberId}` | 실시간 위치 캐시(15분) | `member_id` |
| MySQL `device_heartbeat` | 기기 상태 | `run_id` |
| MySQL `clock_mapping`, `run_marker`, `run_device_assignment` | 시계 기준점·마커·배정 | `run_id` |
| MySQL `decision_record` | 판정 기록(심박 값·사유 포함) | `run_id`, `member_id` |
| MySQL `incident` | 인시던트(본인 응답·사후 판정) | `run_id`, `member_id` |
| MySQL `run_export`, S3 `exports/{runId}/{exportId}/run_{runId}.zip` | 내보내기 잡·zip | `run_id` |
| MySQL `fcm_outbox`, `fcm_notification` | 위급 알림 발송 행 | `related_member_id`, `decision_id` |
| 연구자 반출본 | 내려받은 zip, 노트북·외장 저장장치의 복사본 | 연구 책임자가 반출 대장으로 찾는다 |
| 종이 동의서 | 서면 연구 동의 원본 | 연구 책임자 |
| 가명 대응표 | 실명 ↔ `participation_id` | 서버 밖 보관 담당. 0절 결정에 따른다 |
| DB 백업 | RDS 자동 스냅샷 | 보존 주기(현재 설정값) 안에 자연 만료. 만료 전 복구 시 이 절차를 다시 적용해야 함을 대장에 적는다 |

## 3. 찾기 (읽기 전용 SQL)

```sql
-- 참여 기록과 회차
SELECT p.study_participation_id, p.participation_id, p.member_id, p.status, p.withdrawn_at
FROM study_participation p WHERE p.participation_id = :pid;
SELECT run_id FROM collection_run WHERE study_participation_id = :ppk;          -- :ppk = 위 PK

-- 회차에 매인 자료 건수 (지우기 전·후 비교용)
SELECT 'sensor_batch', COUNT(*) FROM sensor_batch WHERE run_id IN (:runs)
UNION ALL SELECT 'location_fix', COUNT(*) FROM location_fix WHERE run_id IN (:runs)
UNION ALL SELECT 'device_heartbeat', COUNT(*) FROM device_heartbeat WHERE run_id IN (:runs)
UNION ALL SELECT 'decision_record', COUNT(*) FROM decision_record WHERE run_id IN (:runs)
UNION ALL SELECT 'incident', COUNT(*) FROM incident WHERE run_id IN (:runs)
UNION ALL SELECT 'run_export', COUNT(*) FROM run_export WHERE run_id IN (:runs);

-- S3 키 목록 (삭제 명령 입력)
SELECT s3_key FROM sensor_batch WHERE run_id IN (:runs);
SELECT s3_key FROM run_export WHERE run_id IN (:runs) AND s3_key IS NOT NULL;

-- 회원 단위 (회차 밖 심박·위치는 제품 자료이므로 실증 철회 범위가 아니다. 탈퇴 요청이면 별도)
SELECT COUNT(*) FROM heart_rate_event WHERE batch_id IN (SELECT batch_id FROM sensor_batch WHERE run_id IN (:runs));
```

건수를 대장에 적는다.

## 4. 지우기 (순서대로, 한 단계씩 확인)

1. **S3 원문**: 3절의 `s3_key` 목록으로 `aws s3api delete-objects`. 지운 뒤 `aws s3api head-object`가 404인지 표본 3개 확인.
2. **내보내기 zip**: `run_export.s3_key` 삭제, `run_export` 행 삭제.
3. **RDB 회차 자료**: `incident` → `decision_record` → `heart_rate_event`(위 batch_id 조건) → `heart_rate_emergency`(같은 회원·회차 기간) → `sensor_batch` → `location_fix` → `device_heartbeat` → `run_marker` → `run_device_assignment` → `clock_mapping`(회차 기기의 것) → `collection_run` 순. FK가 있으면 그 순서가 강제된다.
4. **Redis**: `location:stay:{memberId}`, `location:trail:{memberId}`, `HeartRateResult` 키 DEL(15분 TTL이라 대부분 이미 없다).
5. **알림 발송 행**: `fcm_outbox`·`fcm_notification`에서 `decision_id`가 지운 판정인 행. 발송 사실 자체는 남겨도 되지만 본문에 건강값이 없는지 확인한 뒤 결정한다(현재 본문에는 없다).
6. **연구자 반출본·종이 동의서·가명 대응표**: 연구 책임자에게 목록과 함께 요청하고 회신을 대장에 붙인다. 대응표는 0절 결정을 따른다.
7. **참여 기록 자체는 지우지 않는다.** 철회했다는 사실·범위·처리 완료 시각이 법적 증빙이다. `study_participation`·`study_participation_history`·관리자 감사 로그는 남긴다.

## 5. 확인과 완료 기록

1. 3절 건수 SQL을 다시 돌려 전부 0인지 확인한다. S3 표본 404 확인.
2. 처리 완료를 기록한다.

```http
POST /api/v1/admin/studies/participations/{participationId}/deletion-processed
```

3. 요청자에게 완료를 알린다. 대장에는 접수 일시, 처리자, 삭제 전·후 건수, 반출본 회신, 0절 결정 적용 여부를 남긴다.

## 6. 시험 절차 (실증 투입 전 1회)

시험 참여자 1명으로 등록 → 연구 회차 1개·배치 몇 건·내보내기 1회 → 이 문서 1~5절 실행 → 3절 SQL 전부 0, S3 404, `deletionProcessedAt` 채워짐. 결과를 이 문서 아래에 날짜와 함께 적는다.

| 날짜 | 실행자 | 결과 |
| --- | --- | --- |
| — | — | 미실행 |
