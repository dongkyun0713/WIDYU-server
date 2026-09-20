# LLD-0050: 측정회차 내보내기 — `run_<run_id>.zip` (B9)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> **파일 형식의 정본은 `04_계약검사기/EXPORT_FORMAT.md`(`format_version "EXPORT_FORMAT v0.2"`)다.** 이 문서는 서버가 그 형식을 어떻게 만드는가만 적는다. 두 문서가 다르게 읽히면 형식서가 맞다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #654 (작업지시서 B9) |
| 관련 ADR | [ADR-0032](../adr/ADR-0032-run-export.md), ADR-0030 v2, ADR-0031 |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | #642(B3)·#644(B8)·#645(B12)·#647(B5)·#649(B6)·#653(B7). 이 브랜치는 feature/633 위에 646·648·652를 합친 통합 베이스다 |

## 1. 목적 / 배경

ADR-0032 맥락과 같다. 완료 기준: ① 내보낸 파일을 다시 읽었을 때 샘플 수·첫/마지막 시각·해시가 서버 저장값과 일치 ② `python check_export.py run_<run_id>.zip` **FAIL 0건**, NOT_MEASURABLE은 항목마다 사유 제출.

## 2. 범위

### In scope
- widyu-api / widyu-domain
- 관리자 API 2개(요청·상태 조회), `run_export` 테이블, `@Scheduled` 워커
- zip 조립: `manifest.json`·`run.json`·`clock_mappings.json`·`quality.json`·`streams/<stream>_<device_id>.jsonl`(imu_watch·imu_phone·hr·location·heartbeat)
- `_server{}` 봉투, 재계산 집계, `streams_absent[]`, `missing_intervals[]`
- S3 업로드, presigned GET

### Out of scope
- `incidents.jsonl`·`measurements`·`decisions.jsonl` 생성(각 기능 미구현). `streams_absent`/파일 부재로 신고
- 수용 영수증·ECG 오프셋(`run.json` 자리 null)
- 파일 분할(X5), 내보내기 삭제·보존
- 검사기 실행 자동화(CI). 실행 방법은 8절

## 3. 인터페이스 / API

관리자 계정(`ROLE_ADMIN`).

```http
POST /api/v1/admin/collection-runs/{runId}/exports
```
→ `202` `data: { "exportId": "exp-…", "runId": "run-…", "status": "QUEUED" }`. 회차가 `CLOSED`가 아니면 `RUN_NOT_CLOSED`(400). 같은 회차에 `QUEUED`/`RUNNING` 잡이 있으면 새로 만들지 않고 그것을 200으로 돌려준다.

```http
GET /api/v1/admin/collection-runs/{runId}/exports/{exportId}
```
→ `data: { "exportId", "runId", "status": "QUEUED|RUNNING|DONE|FAILED", "requestedAtMs", "startedAtMs", "finishedAtMs", "fileName": "run_<runId>.zip", "bytes", "sha256", "downloadUrl", "downloadExpiresAtMs", "errorType" }`. `downloadUrl`은 `DONE`일 때만(호출 시점에 presigned 15분 발급). `errorType`은 예외 클래스명만(메시지·값 없음).

## 4. 데이터 모델

`run_export` (엔티티 `com.widyu.run.RunExport`, widyu-domain, `BaseTimeEntity`):

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `export_id` | VARCHAR(40) | NOT NULL UK (`exp-` + UUID hex) |
| `run_id` | VARCHAR(40) | NOT NULL (문자열 run_id, FK 없음) |
| `status` | VARCHAR(10) | NOT NULL `QUEUED`/`RUNNING`/`DONE`/`FAILED` |
| `requested_at_ms`, `started_at_ms`, `finished_at_ms` | BIGINT | NOT NULL / NULL / NULL |
| `s3_key` | VARCHAR(255) | NULL (`exports/{runId}/{exportId}/run_{runId}.zip`) |
| `bytes` | BIGINT | NULL |
| `sha256` | CHAR(64) | NULL |
| `error_type` | VARCHAR(100) | NULL |
| `server_build` | VARCHAR(64) | NOT NULL (설정값 `sensor.export.server-build`, 기본 `unknown`) |

인덱스 `(run_id, status)`, `(status, requested_at_ms)`.

설정(`application-sensor.yml` `sensor.export.*`): `poll-delay-ms`(5000), `presign-minutes`(15), `expected-period-ms`(`imu_watch: 20`, `imu_phone: 20`, `hr: 1000`, `location: 60000`, `heartbeat: 60000`, 샘플 1개당 기대 간격. 정수라 1/60 같은 반올림이 없다), `gap-threshold-ms`(`imu_*`·`hr`: 5000, `location`·`heartbeat`: 120000), `clock.source-domain`(`DEVICE_MONOTONIC`), `clock.target-domain`(`UTC_EPOCH_MS`), `clock.transform`(`ANCHOR_PAIR`), `clock.evidence-method`(`APP_REPORTED_ANCHOR`), `absent-reason-role-missing`(`NO_DEVICE_ASSIGNED`), `absent-reason-no-data`(`NO_DATA_IN_THIS_RUN`), `absent-reason-not-implemented`(`NOT_IMPLEMENTED_IN_THIS_RUN`).

## 5. 처리 흐름

### 5.1 요청 (`RunExportService.request(runId)`, `@Transactional`)
회차 조회(`CLOSED` 검사) → 진행 중 잡 있으면 반환 → `run_export` `QUEUED` INSERT → 응답.

### 5.2 워커 (`RunExportWorker`, `@Scheduled(fixedDelayString = "${sensor.export.poll-delay-ms}")`)
1. `claim()` (`REQUIRES_NEW`, **별도 빈 `RunExportTransactions`** — 같은 클래스 안 자기호출은 프록시를 타지 않아 새 트랜잭션이 열리지 않는다. `FcmOutboxTransactions`와 같은 분리): `QUEUED` 중 가장 오래된 1건을 `UPDATE … SET status=RUNNING, started_at_ms=now WHERE id=? AND status=QUEUED`로 선점. 0건이면 종료.
2. `RunExportAssembler.build(run)`가 임시 디렉터리(`Files.createTempDirectory`)에 파일을 쓴다(5.3~5.7). 각 단계의 DB 읽기는 `readOnly` 트랜잭션, 워커 자체는 트랜잭션 없음.
3. zip(`java.util.zip`, 경로는 `manifest.json`·`run.json`·`clock_mappings.json`·`quality.json`·`streams/…`) → sha256·bytes → S3 `uploadLocalFile(key, file, "application/zip")` → `finish()` (`RunExportTransactions`, `REQUIRES_NEW`): `DONE`·`s3_key`·`bytes`·`sha256`·`finished_at_ms`.
4. 예외 → `fail()` (`RunExportTransactions`, `REQUIRES_NEW`): `FAILED`·`error_type`·`finished_at_ms`. 로그는 exportId·runId·예외 클래스명. 임시 디렉터리는 `finally`에서 삭제.

### 5.3 스트림 파일 `streams/<stream>_<device_id>.jsonl`
회차의 자료 출처:

| 스트림 | 출처 | 원문 | 정렬 |
| --- | --- | --- | --- |
| `imu_watch`, `imu_phone`, `hr` | `sensor_batch` where `run_id = ?` and `stream = ?` | S3 `s3_key` GET | `measured_at_start_ms`, `seq` |
| `location` | `location_fix` where `run_id = ?` | `payload` 컬럼 | `ts_ms`, `seq` |
| `heartbeat` | `device_heartbeat` where `run_id = ?` | `payload` 컬럼 | `ts_ms` |

기기별로 파일을 나눈다(`device_id`). 레코드 조립(ADR-0032 결정 1): 원문 → `ObjectNode` → 다음 키를 **없을 때만** 넣는다 — `member_id`(행의 값), `stream`(위치·하트비트), `batch_id`(위치: `"loc-"+id`, 하트비트: `"hb-"+id`), `seq`(하트비트만: 앱이 싣지 않으므로 서버가 그 `(device_id, session_id)` 안에서 `ts_ms` 순 0부터 부여한 **서버 서수**), `boot_id`·`clock_mapping_id`(IMU·hr만, 행의 값. 위치·하트비트는 clock이 없으므로 **키를 넣지 않는다**, null도 넣지 않는다) → `_server` 객체 삽입 → 한 줄로 직렬화(`writeValueAsString`, 줄 끝 `\n`).

검사기 정상 표본(`samples/normal`)은 위치·하트비트에도 `batch_id`·`seq`·`stream`·`member_id`를 싣는다. `boot_id`·`clock_mapping_id`는 앱 계약(부록 B)에 없어 싣지 못하며, 그 스트림에서 C1·B2가 NOT_MEASURABLE이 되면 사유 목록에 적는다.

`_server{}` (형식서 §2.2):

| 필드 | IMU·hr (`sensor_batch`) | location | heartbeat |
| --- | --- | --- | --- |
| `measured_at_start_ms`, `measured_at_end_ms` | 행 값 | `ts_ms`, `ts_ms` | `ts_ms`, `ts_ms` |
| `phone_received_at_ms` | 행 값(null 허용) | null | null |
| `server_received_at_ms`, `accepted_at_ms`, `persisted_at_ms` | 행 값 | 행 값 | 행 값 |
| `model_available_at_device_ms`, `model_available_at_phone_ms` | null | null | null |
| `model_available_at_server_ms` | 행 값 | `persisted_at_ms` | `persisted_at_ms` |
| `payload_sha256` | 행 값 | 행 값 | 행 값 |
| `quality_status` | 행 값(`OK`) | `OK` | `OK` |
| `resend` | `is_resend`면 6필드 객체, 아니면 null | null | null |
| `is_backfill`, `backfill_for` | `gyro_backfill`, 보강이면 배열(쉼표 결합 컬럼을 split), 아니면 `null` | false, `null` | false, `null` |
| `collection_mode` | 행 값(`hr`은 null) | null | null |

검사기 D2는 보강 레코드를 원 배치에 병합하지 않는다. 충격 뒤 10초 구간의 acc 배치는 자기 안에 gyro를 실어야 결측으로 잡히지 않는다(앱 계약 확인 항목).

### 5.4 `clock_mappings.json`
회차 배치(`sensor_batch` where run)의 `clock_mapping_id` distinct → `clock_mapping` 행 → 배열 원소: `clock_mapping_id`, `device_id`, `boot_id`, `source_clock_domain`·`target_clock_domain`·`transform`·`evidence_method`(설정값), `anchors: [{elapsed_ns(문자열), epoch_ms, captured_at_ms: first_seen_at_ms, uncertainty_ms}]`, `valid_from_elapsed_ns: observed_min(문자열)`, `valid_to_elapsed_ns: observed_max(문자열)`, `offset_ms: 0`, `drift_ppm: null`, `residual_ms: null`, `estimated_at_ms: first_seen_at_ms`, `superseded_by: null`. 매핑이 없으면 빈 배열 `[]`.

### 5.5 `quality.json`
- `scheduled_duration_s` = `(ended_at_ms − started_at_ms)/1000`.
- `per_stream[]`: 실제 파일이 있는 (스트림, 기기)마다. `expected_sample_count` = `배정 구간 ms ÷ expected-period-ms[stream]`(정수 나눗셈)(배정 구간 = 그 기기의 `assigned_at_ms`~`unassigned_at_ms`(null이면 `ended_at_ms`) 합, ADR-0032 결정 4). `actual_sample_count` = 5.7과 같은 규칙으로 센 값. `coverage` = actual/expected(소수 4자리, expected 0이면 null). `gap_count` = 그 스트림·기기의 `missing_intervals` 수. `duplicate_count` = 0. `out_of_order_count` = 정렬 전 원 순서에서 `measured_at_start`가 직전 `measured_at_end`보다 앞선 건수. `resend_count` = `is_resend` 건수.
- `missing_intervals[]`: 스트림·기기별로 정렬된 레코드의 `measured_at_end_ms[i] → measured_at_start_ms[i+1]` 간격이 `gap-threshold-ms[stream]`을 넘으면 `{stream, device_id, run_id, start_ms: end[i], end_ms: start[i+1], clock_domain: "UTC_EPOCH_MS", missing_reason, disposition: "UNRECOVERED"}`. 배정 시작~첫 레코드, 마지막 레코드~배정 끝 공백도 같은 규칙. `missing_reason`: 그 구간에 그 기기(워치는 `watch.device_id`, 폰은 `device_id`)의 하트비트가 있고 `watch.on_body=false`면 `NOT_WORN`, `watch.connected=false` 또는 `phone.socket_connected=false`면 `DEVICE_OFF`, 아니면 `UNKNOWN`. `wear_state`는 싣지 않는다.
- `unknown_duration_s` = `missing_reason=UNKNOWN` 구간 길이 합(초).

### 5.6 `run.json`
`collection_run`·`run_device_assignment`·`run_marker`에서: `run_id`, `study_id`, `participation_id`, `started_at_ms`, `ended_at_ms`, `subject_ref`(= `"member-" + member_id`), `devices[]{assignment_id, device_id, role, wear_site, assigned_at_ms, unassigned_at_ms}`, `protocol_ref`, `markers[]{marker_id, kind, label, source_elapsed_ns(문자열), ts_ms, source, source_device_id, clock_mapping_id}`, `quality{notes, missing_reason}`, `consent_version`, `retention{data_policy, identified_until, pseudonymized_at, research_until}`(`data_policy` null이면 `"UNDECIDED"` + 날짜 null; 날짜는 ISO `yyyy-MM-dd` 문자열), `collection_mode`, `acceptance_receipt_id: null`, `ecg_clock_offsets: []`.

### 5.7 `manifest.json`
`format_version: "EXPORT_FORMAT v0.2"`, `run_id`, `study_id`, `participation_id`, `exported_at_ms`, `server_build`, `files[]`(**임시 디렉터리에 쓴 실제 파일을 다시 읽어** `bytes`·`sha256`·`record_count`(줄 수)·`sample_count`·`first/last_measured_at_ms`), `totals{record_count, sample_count}`, `streams_absent[]`.
- `sample_count` 규칙: `imu_*` = `acc.n` + `gyro.n`(null이면 0) 합, `hr` = `samples` 길이 합, 그 밖 = 줄 수. 파일을 다시 읽어 파싱해 센다.
- `first/last_measured_at_ms` = 줄들의 **레코드 시작 시각**(`_server.measured_at_start_ms`)의 최소 / **최대**. 끝 시각을 쓰지 않는다. 검사기 I7은 레코드마다 이벤트 시각 하나(위치·하트비트 `ts_ms`, IMU `acc.t0`(보강은 `gyro.t0`) 환산, 심박 첫 샘플 `ts_ms`)를 잡아 min·max를 내며, 그 값이 `measured_at_start_ms`와 같다.
- `streams_absent[]`: 기대 쌍 = 배정 기기 role별 {`watch`: `imu_watch`·`hr`, `phone`: `location`·`heartbeat`·`imu_phone`, `external_ecg`: `measurements`} × 그 `device_id`. 파일이 없는 쌍마다 `{stream, device_id, reason: absent-reason-no-data}`. 회차에 role이 없는 스트림은 `device_id` 없이 `{stream, reason: absent-reason-role-missing}`(예: ECG 없음 → `measurements`). `incidents`는 `{stream: "incidents", reason: absent-reason-not-implemented}`.
- `manifest.json`·`run.json`·`clock_mappings.json`·`quality.json` 자체는 `files[]`에 넣지 않는다(스트림 파일만).

### 5.8 다운로드
`GET` 상태 조회에서 `DONE`이면 `S3DirectUploadService`(또는 `S3Service`)에 `presignGet(key, Duration)`을 추가해 URL 발급. `downloadExpiresAtMs` = now + 15분.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RUN_4003` `RUN_NOT_CLOSED` | 400 | 열린 회차의 내보내기 요청 |
| `RUN_4042` `RUN_EXPORT_NOT_FOUND` | 404 | `exportId` 없음 또는 다른 회차 |
| `RUN_4041` | 404 | 회차 없음 |

잡 실패는 `FAILED` 상태로 표현하고 예외를 API로 올리지 않는다. 로그에 자료 값 금지.

## 7. 인수조건 (Acceptance Criteria)

- [x] 닫힌 회차에 요청하면 `QUEUED`가 만들어지고 워커가 `RUNNING → DONE`으로 옮기며 S3에 zip이 있다. 열린 회차는 `RUN_NOT_CLOSED`.
- [x] 진행 중 잡이 있으면 같은 `exportId`를 돌려준다.
- [x] **통합 테스트(S3·리포지토리 mock + 현실 분량 합성 자료)**: 180초 닫힌 회차, 워치 1대(`imu_watch` 1초 배치 180건 × 50샘플, 그중 1건은 충격+자이로 보강, `hr` 3초 배치 60건 × 3샘플)·폰 1대(`location` 5초 간격 36건·`heartbeat` 60초 간격 3건)·마커 1개·매핑 1개(워치)를 내보내면 zip 안에 `manifest.json`·`run.json`·`clock_mappings.json`·`quality.json`·스트림 파일 4개가 있고, `manifest.files[]`의 `bytes`·`sha256`·`record_count`·`sample_count`가 파일을 다시 읽어 센 값과 같다.
- [x] 스트림 파일 각 줄에서 `_server`·`member_id`·`boot_id`·`clock_mapping_id`(·위치/하트비트의 `stream`·`batch_id`)를 제거하고 파싱한 JSON이 원문 파싱 JSON과 같다(값 동일성).
- [x] `imu_watch` 줄의 `_server.is_backfill`·`backfill_for`가 배열 정규화되고, 재전송 배치는 `resend` 6필드가 채워진다.
- [x] 위치 줄에 `batch_id`(`loc-…`)·`stream: "location"`·`member_id`가 있고 `is_mock`·`accuracy_m`·`reason`이 원문 그대로다.
- [x] `run.json`에 `retention{}`이 항상 있고, 회차에 보존 정보가 없으면 `data_policy: "UNDECIDED"`다. `markers[]`의 `source_elapsed_ns`가 문자열이다.
- [x] `clock_mappings.json`이 배열이고 원소마다 `anchors[]`(1개)·`source/target_clock_domain`·`transform`·`evidence_method`·`valid_from/to_elapsed_ns`(문자열)가 있다.
- [x] 배치 사이 5초 넘는 공백이 `missing_intervals`에 실제 경계값으로 적히고 `missing_reason`이 비어 있지 않다. `expected_sample_count`는 배정 구간 × 표본율이다.
- [x] 폰에 배정됐지만 `imu_phone` 자료가 없으면 `streams_absent`에 `{imu_phone, device_id, NO_DATA_IN_THIS_RUN}`이 있고, `measurements`·`incidents`가 사유와 함께 있다.
- [x] 워커 실패 시 `FAILED`·`error_type`이 남고 임시 디렉터리가 지워진다.
- [x] **수동**: 위 통합 테스트가 만든 zip을 꺼내(`build/…` 경로에 남기는 테스트 옵션) `python 04_계약검사기/check_export.py run_<runId>.zip`을 돌려 **FAIL 0건**을 확인하고, NOT_MEASURABLE 목록과 사유를 PR 본문에 적는다.
- [x] Swagger, `compileJava`(QRunExport), `run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

- 신규 테이블 `run_export` → `scripts/mysql/create_run_export.sql`.
- `S3Service`에 `uploadLocalFile(key, File, contentType)`·`presignGet(key, Duration)` 추가(`S3Presigner` 빈 사용).
- `application-sensor.yml`에 `sensor.export.*`.
- `SchedulerConfig`(`widyu.scheduling.enabled`)가 워커에도 적용된다. 테스트 프로파일에서 워커가 돌지 않게 `@ConditionalOnProperty`나 기존 방식대로.
- 검사기 실행: 전달 묶음 `04_계약검사기/`에서 `python selftest.py` 뒤 `python check_export.py <zip> --json result.json`. NOT_MEASURABLE 사유 양식은 `apiDocs/`에 둔다(로컬 문서).

## 9. 미결정 사항 (Open Questions)

- 없음. X1·X5·X8·X9는 설정값/상수로 두고 확정 시 값만 바꾼다. `decisions.jsonl`은 B10 뒤.

## 10. 참고

- `04_계약검사기/EXPORT_FORMAT.md` §1~§7·§10, `CHECK_ITEMS.md` I1~I10·D3·D12·D13·C10·B2·B3
- 작업지시서 v2 B9, 완료기준표 C9·C2-7·C2-8·C6-2·C6-3
- LLD-0041 v2·0044·0045·0046·0047·0048·0049, ADR-0032
