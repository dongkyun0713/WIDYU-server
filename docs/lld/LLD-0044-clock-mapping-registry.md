# LLD-0044: 시계 환산 기준점 묶음(clock mapping) 등록

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #641 (작업지시서 B3) |
| 관련 ADR | [ADR-0030 v2](../adr/ADR-0030-raw-sensor-batch-storage.md) |
| 작성자 | Claude |
| 작성일 | 2026-09-19 |
| 선행 | PR #634 v2 (LLD-0041 v2) — 이 브랜치는 feature/631 위에 쌓인다 |

## 1. 목적 / 배경

정책서 v1.1 B 1.1.7은 배치마다 오는 시계 환산 다섯 값을 원본 그대로 보존하고 환산은 서버가 하도록 확정했다. LLD-0041 v2는 다섯 값을 배치 행에 그대로 저장하고 그 값으로만 환산한다. 작업지시서 B3은 여기서 한 걸음 더 나가 `clock` 묶음을 **별도 테이블에 보관하고 배치는 `clock_mapping_id`로 참조**하라고 한다. 앱이 환산해 온 값을 그대로 믿지 않고, 같은 묶음 식별자로 다른 값이 오거나 다른 기기가 같은 묶음을 쓰는 것을 잡아내기 위해서다. 시계 동기·재부팅으로 환산이 달라지면 앱은 **새 기준점 식별자를 발급**하고 이미 보낸 배치는 고치지 않는다(개정 1 ⓒ). 그래서 한 식별자의 다섯 값은 불변이어야 한다.

내보내기 형식서 §4의 `clock_mappings.json`은 기기마다 매핑이 따로여야 하고(공유하면 검사기 B2 FAIL), 매핑의 유효 구간이 그 매핑을 쓴 레코드를 덮어야 한다(B10). 이 테이블이 그 근거다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `clock_mapping` 테이블·엔티티
- 배치 수신 시 등록·대조·관측 범위 갱신
- 불일치 거부 에러코드

### Out of scope
- `clock_mappings.json` 내보내기 자체와 시계 도메인·변환·근거 방법 값(형식서 X8). B9에서 상수로 채운다
- 매핑 간 점프 검증(검사기 B9·B12), 앵커 2개 이상의 기울기 — 앱이 앵커를 하나만 보낸다
- 심박·위치·마커 스트림의 매핑 참조 (B5·B6·B8이 같은 서비스 메서드를 재사용한다)
- `sensor_batch.clock_mapping_id`에 DB FK 제약을 거는 것 — 매핑 행이 배치보다 먼저 만들어지므로 서비스 순서로 보장하고 FK는 두지 않는다

## 3. 인터페이스 / API

외부 API 변경 없음. `POST /api/v1/sensor/batches`·`/app/sensor/batches/send`의 동작이 5절대로 바뀐다. 거부 응답이 하나 늘어난다(6절).

## 4. 데이터 모델

신규 테이블 `clock_mapping` (엔티티 `com.widyu.sensor.ClockMapping`, widyu-domain, `BaseTimeEntity`):

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `clock_mapping_id_pk` | BIGINT PK | IDENTITY | 내부 키. 컬럼명은 `id` |
| `clock_mapping_id` | VARCHAR(64) | NOT NULL, **UK `uk_clock_mapping_id`** | 앱이 발급한 묶음 식별자(1.1.7 ②) |
| `device_id` | VARCHAR(64) | NOT NULL | 매핑은 기기 단위. 다른 기기가 같은 id를 쓰면 거부 |
| `boot_id` | VARCHAR(64) | NOT NULL | ① |
| `anchor_elapsed_ns` | BIGINT | NOT NULL | ③ |
| `anchor_epoch_ms` | BIGINT | NOT NULL | ④ |
| `uncertainty_ms` | DOUBLE | NOT NULL | ⑤ |
| `observed_min_elapsed_ns` | BIGINT | NOT NULL | 이 매핑을 쓴 배치의 축 시각 최소. 내보내기 `valid_from_elapsed_ns` 근거 |
| `observed_max_elapsed_ns` | BIGINT | NOT NULL | 최대(마지막 샘플). `valid_to_elapsed_ns` 근거 |
| `first_seen_at_ms` | BIGINT | NOT NULL | 서버가 처음 본 시각 |
| `last_seen_at_ms` | BIGINT | NOT NULL | 마지막으로 참조된 시각 |
| `created_at`, `updated_at` | | | `BaseTimeEntity` |

인덱스: UK `clock_mapping_id`; `idx_clock_mapping_device (device_id)`.

`member_id`는 두지 않는다. 기기는 참가자 사이를 돌아다니므로 매핑은 기기에만 속한다. 내보내기는 회차의 배치가 참조하는 매핑을 `clock_mapping_id`로 모은다.

`sensor_batch`는 변경 없음(`clock_mapping_id` 컬럼은 이미 있다).

## 5. 처리 흐름

`SensorBatchService.ingest` 5.1의 4단계(검증 통과, `accepted_at`) 뒤·5단계(`existsByBatchId`) 앞에 한 단계가 들어간다.

### 5.1 `ClockMappingService.register(clock, deviceId, observedMinElapsedNs, observedMaxElapsedNs)` — `@Transactional`

1. `findByClockMappingId(clock.clockMappingId())` 조회.
2. **없으면** 별도 `REQUIRES_NEW` 트랜잭션에서 INSERT: 다섯 값·`device_id`·관측 범위·`first/last_seen_at_ms = now`. `saveAndFlush`의 UK 경합(`DataIntegrityViolationException`)은 해당 삽입 트랜잭션만 롤백한다. 호출 트랜잭션은 새 persistence context로 다시 조회해 3단계로 간다(동시에 처음 보는 같은 매핑 → 한 행만 남고 둘 다 통과).
3. **있으면** 대조: `device_id`·`boot_id`·`anchor_elapsed_ns`·`anchor_epoch_ms`·`uncertainty_ms`가 전부 같아야 한다. 하나라도 다르면 `SENSOR_CLOCK_MAPPING_CONFLICT`. 부동소수 `uncertainty_ms`는 `Double.compare == 0`.
4. 관측 범위 갱신: `observed_min = min(observed_min, 배치 min)`, `observed_max = max(observed_max, 배치 max)`, `last_seen_at_ms = now`. 동시 갱신에 안전하도록 **JPQL bulk update** 한 문장으로: `UPDATE ClockMapping m SET m.observedMinElapsedNs = CASE WHEN m.observedMinElapsedNs < :min THEN m.observedMinElapsedNs ELSE :min END, m.observedMaxElapsedNs = CASE WHEN m.observedMaxElapsedNs > :max THEN m.observedMaxElapsedNs ELSE :max END, m.lastSeenAtMs = :now WHERE m.clockMappingId = :id`.

배치 min·max는 `SensorBatchService`가 이미 계산하는 축 시각(`t0_elapsed_ns`, `t0 + Σdt_ns`, acc·gyro 합산)이다. 이 값을 매핑 등록에 넘기도록 `toEntity` 앞에서 한 번 계산해 재사용한다.

새로 INSERT한 경로에서는 4단계를 생략한다. 방금 만든 행의 관측 범위가 곧 그 배치의 범위라 넓힐 것이 없다.

**호출 계약**: `register`는 `clock.anchor_elapsed_ns`의 형식·범위(10진 정수 문자열, `0..Long.MAX`)를 검증하지 않고 `Long.parseLong`한다. 호출자가 먼저 검증해야 한다(LLD-0041 5.1 4단계). 심박·위치·마커(B5·B6·B8)가 이 메서드를 재사용할 때는 같은 검증을 호출 전에 둔다.

### 5.2 순서와 실패

- 매핑 등록은 **S3 PUT 앞**이다. 충돌이면 S3·배치 행에 아무것도 남지 않는다.
- 매핑 INSERT/UPDATE는 자기 트랜잭션에서 커밋된다. 그 뒤 S3나 배치 INSERT가 실패해도 매핑 행은 남는다. 매핑은 앱이 발급한 사실의 기록이라 배치 실패와 무관하게 참이며, 재전송 시 3단계 대조를 통과한다. 되돌리지 않는다.
- `SensorBatchService`에는 여전히 `@Transactional`이 없다. `ClockMappingService.register`만 트랜잭션이다.
- 등록은 `existsByBatchId`(중복 판별) **앞**이라 같은 배치의 재전송도 `last_seen_at_ms`를 갱신하고 관측 범위를 넓힌다. 그 매핑이 실제로 참조된 사실이므로 내보내기 유효 구간 관점에서 맞다.
- `uncertainty_ms` 대조는 `Double.compare == 0`이다. 앱이 같은 묶음에 매번 재계산한 값을 실으면 409가 난다. 개정 1 ⓒ대로 묶음 값은 불변이어야 하며, FE 확인 요청 항목이다.

### 5.3 WebSocket

`SENSOR_CLOCK_MAPPING_CONFLICT`는 `BusinessException`이므로 기존 규칙대로 `REJECTED` ACK다(재전송해도 같은 결과). 컨트롤러 변경 없음.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `SENSOR_4090` `SENSOR_CLOCK_MAPPING_CONFLICT` | 409 | 같은 `clock_mapping_id`의 다섯 값 또는 `device_id`가 등록된 것과 다름 |

로그: `clockMappingId`·`deviceId`·충돌 필드 이름만. 값(앵커 시각·불확실성)은 남기지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 처음 보는 `clock_mapping_id`로 배치를 보내면 `clock_mapping` 행이 다섯 값·`device_id`·관측 범위와 함께 생기고 배치는 `STORED`다.
- [x] 같은 매핑으로 두 번째 배치를 보내면 행이 늘지 않고 `observed_min/max`가 두 배치를 덮게 넓어지며 `last_seen_at_ms`가 갱신된다.
- [x] 같은 `clock_mapping_id`로 `anchor_elapsed_ns`가 다른 배치는 `SENSOR_CLOCK_MAPPING_CONFLICT`이고 S3 업로드·배치 저장이 호출되지 않는다. `anchor_epoch_ms`·`boot_id`·`uncertainty_ms` 각각도 같다.
- [x] 같은 `clock_mapping_id`를 다른 `device_id`가 보내면 `SENSOR_CLOCK_MAPPING_CONFLICT`다.
- [x] 처음 보는 같은 매핑의 INSERT가 UK 경합으로 실패하면 재조회해 대조하고, 같으면 통과한다.
- [x] `sensor_batch.clock_mapping_id`가 그 매핑의 `clock_mapping_id`와 같다.
- [x] 로그에 앵커 시각·불확실성 값이 없다.
- [x] `./gradlew compileJava`로 `QClockMapping`이 생성되고 `bash scripts/harness/run-module-tests.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

신규 테이블. `SensorBatchService.ingest`에 호출 한 줄과 min·max 계산 위치 이동. 운영은 `scripts/mysql/create_clock_mapping.sql`:

```sql
CREATE TABLE clock_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    clock_mapping_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    boot_id VARCHAR(64) NOT NULL,
    anchor_elapsed_ns BIGINT NOT NULL,
    anchor_epoch_ms BIGINT NOT NULL,
    uncertainty_ms DOUBLE NOT NULL,
    observed_min_elapsed_ns BIGINT NOT NULL,
    observed_max_elapsed_ns BIGINT NOT NULL,
    first_seen_at_ms BIGINT NOT NULL,
    last_seen_at_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_clock_mapping_id UNIQUE (clock_mapping_id),
    INDEX idx_clock_mapping_device (device_id)
);
```

`sensor_batch`에 FK를 걸지 않는다(2절).

## 9. 미결정 사항 (Open Questions)

- 없음. 시계 도메인·변환·근거 방법 값(X8)은 B9에서 상수로 채운다.

## 10. 참고

- 작업지시서 v2 B3 「시계 환산 정보를 따로 저장한다」, 개정요약 개정 1 ⓒ
- 내보내기 형식서 §4, 검사기 B2·B10
- LLD-0041 v2 4.3 환산식
