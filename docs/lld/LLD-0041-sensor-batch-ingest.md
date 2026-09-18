# LLD-0041: 원시 센서 배치 수신·저장 (v2 형식)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> v2(2026-09-19): 전달 묶음 `위듀_BE_김동균_20260919`(정책서 v1.1, 작업지시서 부록 A, 내보내기 형식서)에 맞춰 다시 썼다. v1의 `streamType`·`samples[{t,x,y,z}]`·재직렬화 JSON 설계는 폐기한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #631 (REST·저장, B1·B2·B4), #632 (WebSocket, 3.2절·5.2절) |
| 관련 ADR | [ADR-0030](../adr/ADR-0030-raw-sensor-batch-storage.md) (v2 개정) |
| 작성자 | Claude |
| 작성일 | 2026-09-18, v2 2026-09-19 |

## 1. 목적 / 배경

워치·폰의 IMU 배치를 **받은 그대로** 저장하고 원본 그대로 꺼낼 수 있어야 한다(작업지시서 1차 목표). 정책서 v1.1은 배치마다 시계 환산 다섯 값을 원본 그대로 보존하고 환산은 서버가 하며(1.1.7), 가속도·자이로는 같은 배치에 오되 시간축이 독립이고(1.1.7), 자이로 부재를 수집 모드로 가릴 수 있게 배치마다 수집 모드를 저장하고(1.2.6·1.6.4), 재전송은 원 순번·원 시각을 유지한 채 계보를 남기도록(1.3.3·1.3.4, 지시서 B4) 확정했다. 완료 기준은 저장한 것을 다시 꺼냈을 때 앱이 보낸 본문과 **바이트 단위로 같은 값**이 복원되는 것이다(지시서 B2, 완료기준 C9-1).

배치 1건 = S3 객체 1개 + MySQL `sensor_batch` 인덱스 행 1개 구조는 유지한다(ADR-0030).

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- v2 IMU 배치 형식(지시서 부록 A) 수신·검증
- 받은 원문 바이트 그대로 S3 저장, 원문 sha256
- 시계 다섯 값 원본 보존과 서버 환산(`measured_at_start/end_ms`)
- 시각 단계 컬럼(수신·수락·저장·서버 가용)
- 재전송 계보 6필드, 보강 표시, 수집 모드, 착용 상태·결측 사유 자리
- `batch_id` 멱등
- REST `POST /api/v1/sensor/batches` (#631), WebSocket `/app/sensor/batches/send` (#632)

### Out of scope
- `clock_mapping` 테이블·기기 단위 매핑 검증·유효구간 (B3, 후속 PR). 이 PR은 배치의 `clock` 블록으로만 환산한다
- 측정회차·기기 배정에 의한 서버 귀속 (B8). `run_id`·`study_id`·`participation_id`는 받은 값을 그대로 저장한다(null 허용)
- 수집 모드의 서버 결정(B12). 이 PR은 앱이 실어 보낸 `collection_mode`를 저장한다
- 심박·위치·하트비트 스트림 (B5·B6·B7, 별도 destination)
- 내보내기(B9), AI 입력(B10), 30일 정리(B11 #637)
- `quality_status` 판정. 이 PR은 `OK`로 고정한다

## 3. 인터페이스 / API

### 3.1 REST (#631) — 오프라인 재전송·자이로 보강

```http
POST /api/v1/sensor/batches
Content-Type: application/json
```

요청 본문은 **지시서 부록 A와 글자 그대로 같다.** 서버는 원문 바이트를 그대로 보관하므로 모르는 필드를 거절하지 않는다(스트림 단위로 늘어나는 구조, 개정 7).

```json
{
  "v": 2,
  "stream": "imu_watch",
  "source": "watch",
  "batch_id": "01j8zk3v9x2q4m7n8p1r5s6t7u",
  "device_id": "gw-3f2a",
  "session_id": "s-20260919-01",
  "seq": 88213,
  "study_id": null,
  "participation_id": null,
  "run_id": null,
  "clock": {
    "boot_id": "b7c1",
    "clock_mapping_id": "cm-01",
    "anchor_elapsed_ns": "993847100000001",
    "anchor_epoch_ms": 1760000000000,
    "uncertainty_ms": 2.0
  },
  "acc": {
    "fs_hz_requested": 50,
    "n": 50,
    "t0_elapsed_ns": "993847112340001",
    "dt_ns": [19998417, 20003005],
    "mg": [[20, -980, 110], [22, -979, 108]]
  },
  "gyro": null,
  "trigger": null,
  "gyro_backfill": false,
  "backfill_for": null,
  "resend": null,
  "collection_mode": "product",
  "gyro_mode": "continuous",
  "on_body": true,
  "wear_state": null,
  "missing_reason": null,
  "watch_battery_pct": 63
}
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `v` | int | O | `2` |
| `stream` | string | O | `imu_watch` \| `imu_phone` |
| `source` | string | O | `watch` \| `phone`. `imu_watch`↔`watch`, `imu_phone`↔`phone` 일치 |
| `batch_id` | string | O | `^[a-z0-9]{26}$`(ULID 소문자). **불변 멱등 키** |
| `device_id`, `session_id` | string | O | `^[a-z0-9._-]{1,64}$` |
| `seq` | long | O | `>= 0` |
| `study_id`, `participation_id`, `run_id` | string | X | null 허용, 각 64자 이하 |
| `clock.boot_id` | string | O | 64자 이하 |
| `clock.clock_mapping_id` | string | O | `^[a-z0-9._-]{1,64}$` |
| `clock.anchor_elapsed_ns` | **string** | O | 10진 정수 문자열, `0 ≤ v ≤ Long.MAX`. JSON number면 거부 |
| `clock.anchor_epoch_ms` | long | O | epoch ms |
| `clock.uncertainty_ms` | number | O | `>= 0` |
| `acc` | object \| null | O(키) | 보강 배치(`gyro_backfill=true`)만 null 허용 |
| `acc.fs_hz_requested` | number | O | `> 0` |
| `acc.n` | int | O | `1 ≤ n ≤ 1000` |
| `acc.t0_elapsed_ns` | **string** | O | 10진 정수 문자열 |
| `acc.dt_ns` | int[] | O | 길이 **n−1**, 각 `0 < dt ≤ 4294967295` |
| `acc.mg` | int[][] | O | 정확히 **n행 × 3열** |
| `gyro` | object \| null | O(키) | 같은 모양, 값 배열 이름은 `mrads`. **null은 null로 저장** |
| `trigger` | object \| null | O(키) | `{kind, smv_g, event_elapsed_ns(string), ts_ms}` |
| `gyro_backfill` | bool | X | 기본 false. true면 `backfill_for` 필수, `acc`는 null, `gyro`는 필수 |
| `backfill_for` | string \| string[] \| null | X | 원 배치 `batch_id` |
| `resend` | object \| null | X | `{is_resend, original_batch_id, original_seq, original_run_id, original_session_id, resent_at_ms}`. `is_resend=true`면 **6필드 전부 필수**(`original_run_id`는 null 허용) |
| `collection_mode` | string | O | `product` \| `research` |
| `gyro_mode` | string | O | `continuous` \| `trigger` |
| `on_body` | bool | O | |
| `wear_state` | string \| null | X | `WORN_VALID` \| `WORN_INVALID` \| `PLANNED_NOT_WORN` \| `ACTUAL_NOT_WORN` \| `UNKNOWN`. 자리는 합의 대기 S1의 제안 |
| `missing_reason` | string \| null | X | 64자 이하. 값 목록은 합의 대기 S2, 검증하지 않음 |
| `watch_battery_pct` | int | X | `0..100` |

응답 (`ApiResponseTemplate`, 세 결과 모두 200):

```json
{ "code": "200", "message": "센서 배치를 저장했습니다.", "data": { "batchId": "01j8zk3v9x2q4m7n8p1r5s6t7u", "seq": 88213, "result": "STORED" } }
```

`result`: `STORED` · `DUPLICATE`(같은 `batch_id`가 이미 있음, S3 PUT 없음). 검증 실패는 4xx 에러 응답.

### 3.2 WebSocket (#632) — 평시 1초 배치

| 명령 | 목적지 | 조건 |
| --- | --- | --- |
| SEND | `/app/sensor/batches/send` | 인증. 페이로드는 3.1과 같은 JSON |
| SUBSCRIBE | `/user/queue/sensor/result` | 인증된 사용자. 발신 세션에만 ACK |

컨트롤러는 **`Message<byte[]>`로 받아 원문 바이트를 그대로 서비스에 넘긴다.** ACK `{batchId, seq, result}`, `result`에 `REJECTED`(검증 실패, 재전송 불필요) 추가. 검증 실패 시 `batch_id`·`seq`를 파싱할 수 있으면 `REJECTED` ACK, 파싱 자체가 실패하면 `/user/queue/errors`. S3·DB 실패는 예외 → `/user/queue/errors`(ACK 없음 → 재전송).

## 4. 데이터 모델

### 4.1 S3

키 `sensor/{memberId}/{deviceId}/{stream}/{batch_id}.json`, `Content-Type: application/json`. 내용은 **받은 원문 바이트 그대로**(재직렬화·정렬·압축 없음). `batch_id`가 불변이라 같은 배치의 재전송은 같은 키다. 재전송으로 내용이 달라지는 경우(`resend` 블록이 붙음)는 새 `batch_id`가 온다(지시서 B4 `original_batch_id`).

### 4.2 `sensor_batch` (엔티티 `com.widyu.sensor.SensorBatch`, widyu-domain, `BaseTimeEntity`)

| 그룹 | 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- | --- |
| 식별 | `sensor_batch_id` | BIGINT PK | | |
| | `batch_id` | VARCHAR(26) | NOT NULL, **UK `uk_sensor_batch_batch_id`** | 멱등 키 |
| | `member_id` | BIGINT FK | NOT NULL | 토큰에서 확정(1.3.1) |
| | `stream` | VARCHAR(20) | NOT NULL | `imu_watch`/`imu_phone` |
| | `source` | VARCHAR(10) | NOT NULL | |
| | `device_id`, `session_id` | VARCHAR(64) | NOT NULL | |
| | `seq` | BIGINT | NOT NULL | |
| | `study_id`, `participation_id`, `run_id` | VARCHAR(64) | NULL | 받은 값 그대로 |
| 시계 원본 | `boot_id` | VARCHAR(64) | NOT NULL | 1.1.7 ① |
| | `clock_mapping_id` | VARCHAR(64) | NOT NULL | ② (FK는 B3에서) |
| | `anchor_elapsed_ns` | BIGINT | NOT NULL | ③ 문자열→long 무손실 |
| | `anchor_epoch_ms` | BIGINT | NOT NULL | ④ |
| | `uncertainty_ms` | DOUBLE | NOT NULL | ⑤ |
| 축 | `acc_n`, `gyro_n` | INT | NULL | 없으면 null |
| | `acc_t0_elapsed_ns`, `gyro_t0_elapsed_ns` | BIGINT | NULL | |
| | `acc_fs_hz_requested`, `gyro_fs_hz_requested` | DOUBLE | NULL | |
| 서버 환산 | `measured_at_start_ms`, `measured_at_end_ms` | BIGINT | NOT NULL | 4.3 환산. acc·gyro 둘 다 있으면 min·max |
| 시각 단계 | `phone_received_at_ms` | BIGINT | NULL | 앱이 실어 보내면 저장(부록 A에 없음, 오면 최상위 `phone_received_at_ms`) |
| | `server_received_at_ms` | BIGINT | NOT NULL | 컨트롤러 진입 시각 |
| | `accepted_at_ms` | BIGINT | NOT NULL | 검증 통과 시각 |
| | `persisted_at_ms` | BIGINT | NOT NULL | INSERT 직전 시각 |
| | `model_available_at_server_ms` | BIGINT | NOT NULL | = `persisted_at_ms`. 소급 금지(1.1.5) |
| 모드·상태 | `collection_mode` | VARCHAR(10) | NOT NULL | 앱 에코. B12에서 서버 지시값과 대조 |
| | `gyro_mode` | VARCHAR(20) | NOT NULL | |
| | `on_body` | BOOLEAN | NOT NULL | |
| | `wear_state` | VARCHAR(20) | NULL | |
| | `missing_reason` | VARCHAR(64) | NULL | |
| | `watch_battery_pct` | INT | NULL | |
| | `quality_status` | VARCHAR(12) | NOT NULL | `OK` 고정(이번 PR) |
| 충격·보강 | `trigger_kind` | VARCHAR(20) | NULL | |
| | `trigger_smv_g` | DOUBLE | NULL | |
| | `trigger_event_elapsed_ns` | BIGINT | NULL | |
| | `trigger_ts_ms` | BIGINT | NULL | |
| | `gyro_backfill` | BOOLEAN | NOT NULL | |
| | `backfill_for` | VARCHAR(255) | NULL | 배열이면 쉼표 결합 |
| 계보 | `is_resend` | BOOLEAN | NOT NULL | |
| | `original_batch_id` | VARCHAR(26) | NULL | |
| | `original_seq` | BIGINT | NULL | |
| | `original_run_id`, `original_session_id` | VARCHAR(64) | NULL | |
| | `resent_at_ms` | BIGINT | NULL | |
| 저장 | `s3_key` | VARCHAR(255) | NOT NULL | |
| | `byte_size` | INT | NOT NULL | 원문 바이트 수 |
| | `payload_sha256` | CHAR(64) | NOT NULL | 원문 해시, 소문자 hex |

인덱스: UK `batch_id`; `idx_sensor_batch_member_stream_time (member_id, stream, measured_at_start_ms)`; `idx_sensor_batch_seq (device_id, session_id, seq)`; `idx_sensor_batch_run (run_id)`.

v1 컬럼 `stream_type`·`batch_kind`·`received_at_ms`·`measured_from_ms`·`measured_to_ms`·`sample_count`·`sha256`은 삭제한다. 테이블은 미배포라 DDL을 통째로 다시 쓴다.

### 4.3 시각 환산

`epoch_ms(elapsed_ns) = anchor_epoch_ms + floor((elapsed_ns − anchor_elapsed_ns) / 1_000_000)`. 마지막 샘플 `elapsed_ns = t0_elapsed_ns + Σ dt_ns`. `measured_at_start_ms = epoch_ms(t0)`, `measured_at_end_ms = epoch_ms(last)` (acc·gyro 중 min·max). `long` 산술로 충분하다(ns 차이는 부팅 이후 시간이라 2^63 안). 환산값은 조회·내보내기 봉투용이고 원값(`anchor_*`, `t0_*`)은 별도 컬럼에 남으므로 기준점이 바뀌면 다시 환산할 수 있다.

### 4.4 DTO

widyu-api `sensor/dto/request/SensorBatchRequest`(record, Jackson `@JsonProperty` snake_case, 중첩 record `Clock`·`Axis`·`Trigger`·`Resend`). `anchor_elapsed_ns`·`t0_elapsed_ns`·`event_elapsed_ns`는 `String`으로 받는다. `backfill_for`는 `JsonNode`로 받아 문자열/배열 모두 수용. 응답 `SensorBatchResultResponse.of(batchId, seq, result)`.

## 5. 처리 흐름

### 5.1 `SensorBatchService.ingest(memberId, byte[] payload)` — 공통

1. `server_received_at_ms = now`. 회원 존재 확인 → `MEMBER_NOT_FOUND`.
2. 원문 바이트 길이 > `sensor.max-payload-bytes`(기본 32,768) → `SENSOR_BATCH_TOO_LARGE`.
3. `ObjectMapper.readValue(payload, SensorBatchRequest)`. JSON 문법 오류·타입 불일치(예: `anchor_elapsed_ns`가 number) → `SENSOR_PAYLOAD_INVALID`. `FAIL_ON_UNKNOWN_PROPERTIES`는 끈다(원문이 보관되므로 손실 없음).
4. Bean Validation(`Validator.validate`) + 구조 검증: `stream`↔`source` 일치, `acc`/`gyro` 각각 `dt_ns.length == n−1`·`mg|mrads` n×3·`dt_ns > 0`·`≤ 4294967295`, `*_elapsed_ns` 10진 정수 문자열이며 long 범위, `gyro_backfill`이면 `acc == null && gyro != null && backfill_for != null`, `resend.is_resend`면 6필드, `acc`·`gyro` 둘 다 null이면 거부. 실패 → `SENSOR_SAMPLE_INVALID`(축·샘플) 또는 `SENSOR_BATCH_INVALID`(그 밖). `accepted_at_ms = now`.
5. `existsByBatchId` → 있으면 `DUPLICATE` 반환(S3 PUT 없음).
6. sha256(원문) → 키 생성 → `s3Service.uploadBytes(key, payload, "application/json")` 동기, `apiCallTimeout` 5초. 실패 → 예외.
7. 4.3 환산, `persisted_at_ms = model_available_at_server_ms = now`. `repository.save()`. `DataIntegrityViolationException` → `existsByBatchId` 재조회 → 있으면 `DUPLICATE`, 없으면 재throw. 서비스에 `@Transactional` 없음(S3는 트랜잭션 밖).
8. `STORED`. 로그는 memberId·stream·seq·acc_n·gyro_n·result만. **샘플 값·페이로드·바이트는 어떤 로그 레벨에도 남기지 않는다**(1.6.7, 완료기준 C9-6).

### 5.2 수신 경로

- REST 컨트롤러: `@RequestBody byte[] payload`(`ByteArrayHttpMessageConverter`) → `ingest` → `ApiResponseTemplate`. 검증 예외는 `GlobalExceptionHandler`.
- WebSocket 컨트롤러(#632): `@MessageMapping("/sensor/batches/send")`, 파라미터 `Message<byte[]>`, 회원 ID는 principal 우선·세션 속성 폴백. `@SendToUser(destinations="/queue/sensor/result", broadcast=false)`. `BusinessException` 중 `FILE_UPLOAD_FAILED`는 재throw, 나머지는 `REJECTED`(`batch_id`·`seq`는 서비스가 예외에 실어 주거나 컨트롤러가 얕게 파싱). 파싱 불가면 재throw.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `SENSOR_4000` `SENSOR_BATCH_TOO_LARGE` | 400 | 원문 바이트 > 설정 상한 |
| `SENSOR_4001` `SENSOR_SAMPLE_INVALID` | 400 | 축 구조 불일치(`n`·`dt_ns`·`mg` 길이, `dt_ns` 범위, `*_elapsed_ns` 형식·범위) |
| `SENSOR_4002` `SENSOR_PAYLOAD_INVALID` | 400 | JSON 파싱 실패, 타입 불일치, 나노초 필드가 문자열이 아님 |
| `SENSOR_4003` `SENSOR_BATCH_INVALID` | 400 | 필수 필드 누락·값 집합 위반·`stream`↔`source` 불일치·보강/재전송 조건 위반 |
| `MEMBER_4041` | 404 | 회원 없음 |
| `FILE_5000` | 500 | S3 PUT 실패·타임아웃 |

WebSocket: 4xx → `REJECTED` ACK(식별자 파싱 가능 시), 500·파싱 불가 → `/user/queue/errors`.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 부록 A 예시 배치를 REST로 보내면 `STORED`, S3 객체의 바이트가 보낸 원문과 **바이트 단위로 같고** `payload_sha256`·`byte_size`가 그 원문 기준이다(C9-1).
- [ ] 같은 `batch_id`를 다시 보내면 `DUPLICATE`이고 S3 업로드가 호출되지 않는다. UK 경합 시 재조회 뒤 있을 때만 `DUPLICATE`.
- [ ] `anchor_elapsed_ns`·`t0_elapsed_ns`가 JSON number로 오면 `SENSOR_PAYLOAD_INVALID`, `dt_ns` 길이가 `n−1`이 아니거나 `mg`가 n×3이 아니면 `SENSOR_SAMPLE_INVALID`, `dt_ns`에 0 또는 uint32 초과가 있으면 `SENSOR_SAMPLE_INVALID`(검사기 A1·A3·A9·A13).
- [ ] `gyro: null`이 그대로 저장되고(`gyro_n` null) 0 배열로 바뀌지 않는다(D1).
- [ ] `gyro_backfill=true`인데 `backfill_for`가 없거나 `acc`가 있으면 `SENSOR_BATCH_INVALID`.
- [ ] `resend.is_resend=true`인데 6필드 중 하나가 빠지면 `SENSOR_BATCH_INVALID`; 갖추면 6필드가 컬럼에 그대로 저장된다(C12).
- [ ] 다섯 시계 값이 원본 그대로 컬럼에 남고, `measured_at_start/end_ms`가 4.3 식으로 환산된 값이다(1.1.7).
- [ ] `server_received_at_ms ≤ accepted_at_ms ≤ persisted_at_ms = model_available_at_server_ms`이고 `measured_at_end_ms ≤ server_received_at_ms`인 정상 배치에서 그 순서가 유지된다(C5·C6).
- [ ] `collection_mode`·`gyro_mode`·`on_body`가 없으면 `SENSOR_BATCH_INVALID`(C10·L4).
- [ ] 로그에 샘플 값이 없다.
- [x] (#632) WebSocket으로 같은 배치를 보내면 REST와 같은 행·객체가 생기고 ACK를 발신 세션만 받는다. 검증 실패는 `REJECTED`.
- [ ] Swagger 반영, `./gradlew compileJava`로 `QSensorBatch` 재생성, `bash scripts/harness/run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

`sensor_batch`는 미배포 테이블이라 `scripts/mysql/create_sensor_batch.sql`을 4.2 정의로 **통째로 교체**한다. 로컬·dev에서 v1 스키마가 이미 만들어졌으면 `DROP TABLE sensor_batch` 뒤 재기동(`ddl-auto: update`는 컬럼을 지우지 않는다). 운영은 미적용 상태라 새 스크립트만 실행한다.

설정 `sensor.max-payload-bytes`(기본 32768)를 `application-sensor.yml`에 둔다(#633 B12와 같은 파일).

## 9. 미결정 사항 (Open Questions)

- 없음. `clock_mapping` 테이블(B3), 회차 귀속(B8), 서버 결정 수집 모드(B12), `quality_status` 판정은 후속 PR.

## 10. 참고

- `Downloads/위듀_BE_김동균_20260919/00_작업지시서_서버AI입력.md` B1·B2·B3·B4, 부록 A
- `02_데이터정책서_v1.1_B_서버AI입력.md` 1.1.7·1.2.5·1.2.6·1.3.3·1.6.4
- `04_계약검사기/EXPORT_FORMAT.md` §2(봉투)·§2.3(계보), `CHECK_ITEMS.md` A·C·D
- ADR-0030 v2, LLD-0032(allowlist)
