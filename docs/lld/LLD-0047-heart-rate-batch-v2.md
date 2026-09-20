# LLD-0047: 심박 배치 v2 수신 — 원본 보존과 저장 우선 (B5)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #646 (작업지시서 B5) |
| 관련 ADR | [ADR-0031](../adr/ADR-0031-heart-rate-batch-store-first.md), ADR-0030 v2 |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #634 → #642 → #644 → #645(8c4f254). 이 브랜치는 feature/633 위에 쌓인다 |

## 1. 목적 / 배경

ADR-0031 맥락과 같다. 심박을 샘플 배열 + 정확도로 받아 원문을 보존하고, AI 실패가 저장을 막지 않게 하며, 잰 시각·서버 수신 시각을 따로 남겨 1.1.8 지연 분포를 잴 수 있게 한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `SensorBatchService.ingest`의 `stream` 분기: `imu_watch`·`imu_phone`(기존) / `hr`(신규)
- `hr` 검증·인덱스 행·샘플 행 저장·AI 판정 루프
- `heart_rate_event`에 `accuracy`·`batch_id` 컬럼
- `sensor_batch`에 `sample_count` 컬럼, `collection_mode`·`gyro_mode` NULL 허용

### Out of scope
- 새 endpoint·WebSocket 목적지 (기존 `POST /api/v1/sensor/batches`·`/app/sensor/batches/send`가 `stream`으로 분기. WebSocket 컨트롤러는 PR #635에 있어 그 머지 뒤 자동 적용)
- 단건 경로 제거, 지연 분포 집계(B9), `context` 개인화(#477)
- 위급 알림 정책 변경(기존 `HeartRateEmergencyEvent`·FCM 그대로)

## 3. 인터페이스 / API

같은 endpoint. 요청은 부록 B `hr` + `resend{}`(선택) + `phone_received_at_ms`(선택):

```json
{ "v": 2, "stream": "hr", "batch_id": "01j8zk3v9x2q4m7n8p1r5s6t7v",
  "device_id": "gw-3f2a", "session_id": "s-20260920-01", "seq": 1041,
  "study_id": null, "participation_id": null, "run_id": null,
  "clock": { "boot_id": "b7c1", "clock_mapping_id": "cm-01", "anchor_elapsed_ns": "993847100000001", "anchor_epoch_ms": 1760000000000, "uncertainty_ms": 2.0 },
  "samples": [
    { "bpm": 71, "ts_ms": 1760000000123, "accuracy": "HIGH" },
    { "bpm": 72, "ts_ms": 1760000001120, "accuracy": "HIGH" },
    { "bpm": 0,  "ts_ms": 1760000002118, "accuracy": "UNRELIABLE" }
  ],
  "on_body": true, "watch_battery_pct": 63, "resend": null }
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `v` | int | O | `2` |
| `stream` | string | O | `hr` |
| `batch_id`, `device_id`, `session_id`, `seq`, `study_id`, `participation_id`, `run_id`, `clock.*`, `resend`, `phone_received_at_ms` | | | LLD-0041 v2 3.1과 같다 |
| `samples` | object[] | O | **1..60개**(검사기 E1) |
| `samples[].bpm` | int | O | `0..300` |
| `samples[].ts_ms` | long | O | epoch ms. 배치 안에서 **엄격 증가**(역행·중복 금지, E2) |
| `samples[].accuracy` | string | O | `HIGH` \| `MEDIUM` \| `LOW` \| `UNRELIABLE` \| `UNKNOWN`(D5) |
| `bpm == 0` | | | `accuracy == UNRELIABLE`일 때만(E7) |
| `on_body` | bool | O | |
| `watch_battery_pct` | int | X | `0..100` |
| `location`, `context` | | **금지** | 있으면 `SENSOR_BATCH_INVALID`(E6, 계약에서 삭제된 필드) |
| `source`, `acc`, `gyro`, `trigger`, `gyro_backfill`, `backfill_for`, `collection_mode`, `gyro_mode`, `wear_state`, `missing_reason` | | X | 오면 원문에만 남고 인덱스에는 쓰지 않는다 |

응답 `{batchId, seq, result}`는 LLD-0041과 같다. 샘플 단위 AI 실패는 응답에 영향이 없다.

## 4. 데이터 모델

### `sensor_batch` 변경
| 컬럼 | 변경 |
| --- | --- |
| `sample_count` | INT NULL **추가**. `hr` 샘플 수. IMU는 null(`acc_n`·`gyro_n` 사용) |
| `collection_mode`, `gyro_mode` | NOT NULL → **NULL 허용**. `hr`은 null. IMU 필수는 서비스 검증이 보장 |
| `stream` | 값 `hr` 허용 |
| `measured_at_start_ms`, `measured_at_end_ms` | `hr`은 `samples[].ts_ms`의 min·max(시계 환산 없음) |
| `acc_*`, `gyro_*`, `trigger_*`, `on_body`, `watch_battery_pct` | `hr`은 축·충격 null, `on_body`·`watch_battery_pct`는 저장 |
| `gyro_backfill`, `is_resend`, `config_mismatch` | `hr`은 `false`(설정 대조는 IMU만) |
| `source` | NOT NULL 유지. `hr`은 `watch` 고정(심박은 워치에서만 온다) |

### `heart_rate_event` 변경
| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `accuracy` | VARCHAR(12) | NULL | 단건 경로는 null |
| `batch_id` | VARCHAR(26) | NULL | 배치 원문 참조. 단건 경로는 null |

`measured_at` = `LocalDateTime.ofInstant(Instant.ofEpochMilli(ts_ms), ZoneId.of("Asia/Seoul"))`. 기존 그래프·위급 사이클·30일 정리와 같은 축이다. 원 시각 `ts_ms`는 S3 원문과 인덱스 행 범위에 남는다. UK `(member_id, measured_at)` 유지.

### DTO
`sensor/dto/request/HeartRateBatchRequest`(record, snake_case, `SensorBatchRequest.Clock`·`Resend` 재사용, `samples[]`는 record `Sample(int bpm, long tsMs, String accuracy)`, `location`·`context`는 `JsonNode`로 받아 null이 아니면 거부).

## 5. 처리 흐름

`SensorBatchService.ingest(memberId, payload)`:

1. 공통: 수신 시각, 회원, 크기, **`readTree`로 `stream`만 먼저 읽어** DTO를 고른다(`imu_*` → `SensorBatchRequest`, `hr` → `HeartRateBatchRequest`, 그 밖 → `SENSOR_BATCH_INVALID`). 파싱 실패 → `SENSOR_PAYLOAD_INVALID`.
2. `hr` 검증: 3절 표. `samples`의 `ts_ms` 엄격 증가, `bpm==0 ⇒ UNRELIABLE`, `accuracy` 값 집합, 금지 필드. 실패 → `SENSOR_SAMPLE_INVALID`(샘플) / `SENSOR_BATCH_INVALID`(그 밖). `accepted_at`.
3. 공통: `clock` 등록 — `ClockMappingService.register(clock, deviceId, anchorElapsedNs, anchorElapsedNs)`. `hr`은 경과 나노초 축이 없어 관측 범위를 앵커 한 점으로 준다(범위를 넓히지 않는다). 호출 전 `anchor_elapsed_ns` 형식·범위 검증(LLD-0044 계약).
4. 공통: 회차 귀속(LLD-0045), `existsByBatchId` → `DUPLICATE`.
5. 공통: sha256 → S3 PUT `sensor/{memberId}/{deviceId}/hr/{batch_id}.json`.
6. **`hr` 전용 — `HeartRateBatchService.storeAndAssess(member, batchId, samples, serverReceivedAtMs)`** (S3 뒤·인덱스 INSERT 앞):
   - `aiAvailable = true`로 시작. 샘플을 `ts_ms` 순으로:
     - `measured_at` 환산. `existsByMemberIdAndMeasuredAt` → 있으면 스킵(재전송·단건 중복).
     - AI 대상 = `1 ≤ bpm ≤ 299 && accuracy != UNRELIABLE && aiAvailable`. 대상이면 `HeartRateAnomalyDetector.detect(memberId, HeartRateMeasurement(bpm, measuredAt), "UNKNOWN")`. `RestClientException`·`BusinessException`이면 `status=UNKNOWN`, `emergency=false`, **`aiAvailable=false`**(배치 안 단락). 대상이 아니면 `UNKNOWN`.
     - 저장: `HeartRateEvent`(bpm, measuredAt, status, accuracy, batchId). `emergency`면 `HeartRateEmergency(member, bpm, measuredAt, location=null)` + `HeartRateEmergencyEvent(memberId)` 발행. 기존 `HeartRatePersistenceService`에 배치용 메서드를 추가해 재사용(단건 메서드는 그대로).
   - 마지막으로 저장한 샘플로 `HeartRateResult`(Redis 최신값) 갱신.
   - 트랜잭션: 샘플 저장은 `HeartRatePersistenceService`의 샘플 단위 `@Transactional`(기존과 같은 경계). AI 호출은 트랜잭션 밖(ADR-0008 유지).
7. 공통: 인덱스 행 INSERT(`sample_count`, `measured_at_*`, 시각 4단계, 계보, 귀속). UK 경합 처리 기존과 같다.
8. `STORED`. 로그는 memberId·stream·seq·sampleCount·aiSkipped(건수)·result만. **bpm 값·상태·응답 본문은 어떤 레벨에도 남기지 않는다**(#639).

순서 근거는 ADR-0031 결정 4. 6단계 중간 실패(DB 장애)는 예외로 올라가 인덱스 행이 생기지 않으므로 재전송이 다시 시도되고, 이미 저장된 샘플은 UK로 스킵된다.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `SENSOR_4001` `SENSOR_SAMPLE_INVALID` | 400 | 샘플 수 0·61+, `bpm` 범위, `ts_ms` 역행·중복, `accuracy` 값 밖, `bpm==0`인데 `UNRELIABLE` 아님 |
| `SENSOR_4003` `SENSOR_BATCH_INVALID` | 400 | `location`·`context` 존재, 필수 누락, 모르는 `stream` |
| 그 밖 | | LLD-0041·0044·0045와 같다 |

AI 실패는 오류가 아니다. `UNKNOWN` 저장 + 단락.

## 7. 인수조건 (Acceptance Criteria)

- [x] 3절 예시 배치를 보내면 `STORED`, S3 객체 1개, `sensor_batch(stream=hr, sample_count=3)` 행 1개, `heart_rate_event` 3행(각 `accuracy`·`batch_id` 채워짐, `measured_at`은 Asia/Seoul 환산).
- [x] AI `detect`가 `RestClientException`을 던지면 첫 샘플부터 `UNKNOWN`으로 저장되고 이후 샘플은 AI를 호출하지 않으며 배치는 `STORED`다.
- [x] `bpm=0`·`UNRELIABLE` 샘플은 저장되고 AI가 호출되지 않는다.
- [x] `bpm=0`인데 `HIGH` → `SENSOR_SAMPLE_INVALID`. `location` 존재 → `SENSOR_BATCH_INVALID`. 샘플 61개 → `SENSOR_SAMPLE_INVALID`. `ts_ms` 역행 → `SENSOR_SAMPLE_INVALID`. 이 경우 S3·DB에 아무것도 남지 않는다.
- [x] 위급 판정 샘플은 `HeartRateEmergency` 행과 `HeartRateEmergencyEvent`를 만든다.
- [x] 같은 `batch_id` 재전송은 `DUPLICATE`이고 샘플이 늘지 않는다. 다른 `batch_id`인데 같은 `measured_at` 샘플은 스킵된다.
- [x] 인덱스 행의 `server_received_at_ms − measured_at_end_ms`가 지연이다(값 확인).
- [x] 마지막 샘플로 `HeartRateResult`가 갱신된다.
- [x] 기존 단건 경로·`HeartRateService`·그래프 테스트가 그대로 통과한다. IMU 배치 테스트 29건도 그대로.
- [x] `./gradlew compileJava`(Q클래스 재생성), `bash scripts/harness/run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

- `scripts/mysql/create_sensor_batch.sql`: `sample_count INT NULL` 추가, `collection_mode`·`gyro_mode` NULL 허용으로 수정(미배포 테이블).
- `scripts/mysql/alter_sensor_batch_for_hr.sql`: 이미 v2 스키마가 있는 dev용 ALTER(컬럼 추가 + MODIFY NULL).
- `scripts/mysql/add_heart_rate_event_accuracy.sql`: `ALTER TABLE heart_rate_event ADD COLUMN accuracy VARCHAR(12) NULL, ADD COLUMN batch_id VARCHAR(26) NULL;` — **운영 배포 전 필수**(기존 테이블).
- `HeartRateEvent.of` 시그니처에 `accuracy`·`batchId` 추가(단건 경로는 null 전달). ERD·`backend/CLAUDE.md` heart·sensor 절 갱신.
- `SensorBatchService` 분기로 `SensorBatchRequest` 파싱 경로가 바뀐다. 기존 테스트 29건 유지.

## 9. 미결정 사항 (Open Questions)

- 없음. 단건 경로 제거·주소 조회·지연 집계는 ADR-0031 후속 절.

## 10. 참고

- 작업지시서 v2 B5·부록 B, 정책서 v1.1 1.1.8·1.2.1·1.3.1·1.3.2, 형식서 §3.5, 검사기 D4·D5·E1·E2·E4·E6·E7
- LLD-0041 v2, LLD-0044, LLD-0045, LLD-0046, ADR-0008, ADR-0017
