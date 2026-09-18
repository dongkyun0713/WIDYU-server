# LLD-0041: 원시 센서 배치 수신·저장

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #631 (REST·저장), #632 (WebSocket 경로, 3.2절·5.2절) |
| 관련 ADR | [ADR-0030](../adr/ADR-0030-raw-sensor-batch-storage.md) |
| 작성자 | Claude |
| 작성일 | 2026-09-18 |

## 1. 목적 / 배경

데이터 정책서 v1 B장 1단은 워치·폰의 IMU와 위치 원본을 재표본화 없이 저장하고, 원 측정 시각·서버 가용 시각·`device_id`·`session_id`·`seq`를 남기도록 확정했다. 현재 서버에는 이 수신·저장 경로가 없다. 이 문서는 배치 1건을 S3 객체 1개와 MySQL 인덱스 행 1개로 저장하는 서비스와 REST·WebSocket 수신 경로를 정한다. API 스펙은 정책서가 아니라 이 문서가 정한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `SensorBatch` 엔티티, `SensorStreamType`·`SensorBatchKind`·`GyroMode` enum
- `SensorBatchService.ingest` (중복 판별 → 검증 → canonical JSON → S3 → 인덱스 행)
- `S3Service.uploadBytes`
- REST `POST /api/v1/sensor/batches` (#631)
- WebSocket `/app/sensor/batches/send` + allowlist (#632)
- 운영 DDL, ERD, `backend/CLAUDE.md`

### Out of scope
- `study_participation_id` FK와 연구 귀속: PR #618 머지 뒤 후속 연구 LLD에서 `heart_rate_event`와 함께 추가한다(ADR-0026).
- `sensor_batch` 정리·가명화·파기 스케줄러, S3 수명주기 정책 (후속 연구 LLD)
- `gyro_mode` 설정 내려주기 (#633, LLD-0042)
- 샘플 내부 스키마 검증 (AI 입력 형식 확정 뒤)
- 재전송 원본 참조 필드, 나노초 원 시각 (합의 대기 K1·K2)
- 심박·실시간 위치 경로 변경
- SENIOR 타입 검증 (보호자가 자기 회원 ID로 센서 행을 쓸 이유가 없다)

## 3. 인터페이스 / API

### 3.1 REST (#631) — 오프라인 재전송·자이로 보강

`/api/v1/**`는 `SecurityConfig`에서 인증 필수다. 회원은 토큰에서 확정한다(정책 1.3.1). Swagger는 `controller/docs/SensorBatchDocs`에 둔다.

```http
POST /api/v1/sensor/batches
```

요청 본문 (`SensorBatchRequest`. 모르는 최상위 필드는 `@JsonAnySetter`에서 예외를 던져 400으로 거절한다):

```json
{
  "deviceId": "watch-3f2a",
  "sessionId": "s-20260918-01",
  "seq": 1234,
  "streamType": "WATCH_ACCEL",
  "batchKind": "LIVE",
  "gyroMode": "CONTINUOUS",
  "onBody": true,
  "samples": [
    { "t": 1758150000123, "x": -12, "y": 980, "z": 45 },
    { "t": 1758150000143, "x": -10, "y": 982, "z": 44 }
  ]
}
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| `deviceId` | String | O | `^[a-z0-9._-]{1,64}$` (소문자만. DB collation과 S3 키 판정 통일) |
| `sessionId` | String | O | `^[a-z0-9._-]{1,64}$` (소문자만) |
| `seq` | Long | O | `>= 0` |
| `streamType` | enum | O | `WATCH_ACCEL`, `WATCH_GYRO`, `PHONE_ACCEL`, `PHONE_GYRO`, `PHONE_LOCATION` |
| `batchKind` | enum | O | `LIVE`, `RETRANSMIT`, `GYRO_ENRICH` |
| `gyroMode` | enum | O | `CONTINUOUS`, `TRIGGER`. 수집 당시 워치 설정(PT-02). 폰 스트림도 앱이 아는 현재 설정을 그대로 싣는다 |
| `onBody` | Boolean | X | 워치 착용 여부(1.2.2). 폰 스트림은 null |
| `samples` | JSON 배열 | O | 1~1000개. 각 원소에 정수 `t`(epoch ms UTC) 필수 |

샘플 내부 스키마는 스트림별로 다음과 같이 고정하되 서버는 `t`만 검증한다.

| 스트림 | 샘플 | 단위 |
| --- | --- | --- |
| `WATCH_ACCEL`, `PHONE_ACCEL` | `{t, x, y, z}` | 정수 mg, 중력 포함 기기 좌표계(1.6.2) |
| `WATCH_GYRO`, `PHONE_GYRO` | `{t, x, y, z}` | 정수 mrad/s |
| `PHONE_LOCATION` | `{t, lat, lon, accuracy}` | 도, 도, 미터. 저정확도도 저장(1.6.6) |

응답 (`ApiResponse` 래퍼, 세 결과 모두 200):

```json
{
  "code": "200",
  "message": "센서 배치를 저장했습니다.",
  "data": { "seq": 1234, "result": "STORED" }
}
```

`result`: `STORED`(저장), `DUPLICATE`(같은 배치가 이미 있어 저장하지 않음). 검증 실패는 4xx 에러 응답이다(6절).

### 3.2 WebSocket (#632) — 평시 1초 배치

| 명령 | 목적지 | 조건 |
| --- | --- | --- |
| SEND | `/app/sensor/batches/send` | 인증. 페이로드는 3.1과 같은 `SensorBatchRequest` |
| SUBSCRIBE | `/user/queue/sensor/result` | 인증된 사용자. 발신 세션에만 ACK |

ACK 페이로드는 `{seq, result}`이며 `result`에 `REJECTED`(검증 실패, 재전송 불필요)가 추가된다. S3·DB 실패는 예외로 던져 기존 `/user/queue/errors`로 가고, ACK가 없으므로 클라이언트는 재전송한다. `JwtChannelInterceptor` allowlist에 두 목적지를 정확 일치로 추가한다(LLD-0032).

## 4. 데이터 모델

신규 테이블 `sensor_batch` (엔티티 `com.widyu.sensor.SensorBatch`, widyu-domain, `BaseTimeEntity` 상속):

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `sensor_batch_id` | BIGINT PK | IDENTITY | |
| `member_id` | BIGINT FK → member | NOT NULL | 토큰에서 확정 |
| `stream_type` | VARCHAR(20) | NOT NULL | `@Enumerated(STRING)` |
| `batch_kind` | VARCHAR(20) | NOT NULL | `@Enumerated(STRING)` |
| `device_id` | VARCHAR(64) | NOT NULL | |
| `session_id` | VARCHAR(64) | NOT NULL | |
| `seq` | BIGINT | NOT NULL | 재전송은 원 seq 유지 |
| `gyro_mode` | VARCHAR(20) | NOT NULL | 수집 당시 모드. 생략과 결측 구분(1.2.6)에 쓰므로 필수 |
| `on_body` | BOOLEAN | NULL | |
| `measured_from_ms` | BIGINT | NOT NULL | 샘플 `t` 최소 |
| `measured_to_ms` | BIGINT | NOT NULL | 샘플 `t` 최대 |
| `sample_count` | INT | NOT NULL | |
| `received_at_ms` | BIGINT | NOT NULL | 서버 수신 = 가용 시각 |
| `s3_key` | VARCHAR(255) | NOT NULL | |
| `byte_size` | INT | NOT NULL | canonical JSON 바이트 수 |
| `sha256` | CHAR(64) | NOT NULL | canonical JSON 해시, 소문자 hex |
| `created_at`, `updated_at` | DATETIME(6) | | `BaseTimeEntity` |

- UK `uk_sensor_batch_seq (member_id, device_id, session_id, stream_type, seq)`
- INDEX `idx_sensor_batch_member_stream_time (member_id, stream_type, measured_from_ms)`

enum은 MySQL ENUM이 아니라 VARCHAR로 둔다. 값 추가 시 ALTER가 필요 없다.

S3 키: `sensor/{memberId}/{deviceId}/{sessionId}/{streamType}/{seq}-{sha256 앞 16자}.json`, `Content-Type: application/json`. 내용 해시가 키에 있어 객체는 내용별로 불변이다. 같은 seq에 다른 내용이 동시에 오면 INSERT 승자의 행과 객체만 남고 진 쪽 객체는 고아가 된다(참가자 파기 시 접두사 삭제).

DTO (widyu-api `sensor/dto`): `request/SensorBatchRequest`(record), `response/SensorBatchResultResponse.of(seq, result)`. `SensorBatchResult` enum(`STORED`, `DUPLICATE`, `REJECTED`)은 응답 DTO와 같은 패키지에 둔다.

## 5. 처리 흐름

### 5.1 `SensorBatchService.ingest(memberId, request)` — 공통

1. 회원 존재 확인 → 없으면 `MEMBER_NOT_FOUND`.
2. `existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq` → 있으면 `DUPLICATE` 반환. S3 PUT을 하지 않아 원본이 유지된다.
3. `samples` 순회 1회: 각 원소의 `t`가 정수가 아니거나 long 범위를 넘으면(`canConvertToLong()`) `SENSOR_SAMPLE_INVALID`. 같은 루프에서 min·max를 구한다.
4. 요청 DTO를 Boot `ObjectMapper` 빈으로 `writeValueAsBytes` → 길이가 32,768바이트를 넘으면 `SENSOR_BATCH_TOO_LARGE`. `MessageDigest("SHA-256")` + `HexFormat.of()`로 sha256을 구하고 그 앞 16자를 S3 키에 넣는다.
5. `received_at_ms = System.currentTimeMillis()`.
6. `s3Service.uploadBytes(key, bytes, "application/json")` 동기 호출. 실패 시 예외가 그대로 올라간다(인덱스 행 저장 없음).
7. `sensorBatchRepository.save(SensorBatch.of(...))`. 서비스 메서드에 `@Transactional`을 두지 않아 S3 호출이 트랜잭션 밖에 있다. `DataIntegrityViolationException`(UK 경합)은 `DUPLICATE`로 변환한다.
8. `STORED` 반환. 로그는 memberId·streamType·seq·sampleCount·result만 남긴다(1.6.7). 샘플 값·페이로드는 어떤 레벨에도 남기지 않는다.

`S3ServiceImpl.uploadBytes`는 `PutObjectRequest.overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)))`와 `RequestBody.fromBytes`를 쓴다. 실패는 `BusinessException(FILE_UPLOAD_FAILED)`로 감싼다.

### 5.2 수신 경로

- REST 컨트롤러: `@Valid @RequestBody` → `ingest` → `ApiResponse`. 검증 실패는 `GlobalExceptionHandler`가 처리한다.
- WebSocket 컨트롤러(#632): `@MessageMapping("/sensor/batches/send")`, `@Valid @Payload`, 회원 ID는 `HeartRateWebSocketController.resolveMemberId`와 같은 방식(principal 우선, 세션 속성 폴백). `@SendToUser(destinations = "/queue/sensor/result", broadcast = false)`로 발신 세션에만 ACK. 컨트롤러 안에서 `BusinessException`만 잡아 `REJECTED`로 응답하고, 나머지 예외는 던진다.

Facade·이벤트 없음.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `SENSOR_4000` `SENSOR_BATCH_TOO_LARGE` | 400 | canonical JSON > 32KB |
| `SENSOR_4001` `SENSOR_SAMPLE_INVALID` | 400 | 샘플에 정수 `t`가 없거나 long 범위를 넘음 |
| `MEMBER_4041` | 404 | 회원 없음 |
| `FILE_5000` `FILE_UPLOAD_FAILED` | 500 | S3 PUT 실패·타임아웃 |
| 기존 validation 400 | 400 | 필수 필드 누락, 패턴 위반, `samples` 크기 위반, 모르는 최상위 필드 |

WebSocket에서는 위 4xx 조건이 `REJECTED` ACK가 되고, 500 조건은 `/user/queue/errors`로 간다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 유효한 배치를 REST로 보내면 `STORED`를 받고, S3에 키 하나와 `sensor_batch` 행 하나가 생기며 행의 `sha256`·`byte_size`가 S3 객체 내용과 일치한다.
- [x] 같은 `(member, deviceId, sessionId, streamType, seq)` 배치를 다시 보내면 `DUPLICATE`를 받고 S3 업로드가 호출되지 않는다.
- [x] 같은 식별자에 내용이 다른 두 요청은 서로 다른 S3 키에 올라가고, 저장된 행의 `sha256`은 그 행이 가리키는 객체의 바이트와 일치한다.
- [x] UK 경합으로 INSERT가 실패하면 예외 대신 `DUPLICATE`를 반환한다.
- [x] canonical JSON이 32KB를 넘으면 `SENSOR_BATCH_TOO_LARGE`, 샘플에 정수 `t`가 없으면 `SENSOR_SAMPLE_INVALID`로 거부하고 S3·DB에 아무것도 남지 않는다.
- [x] 모르는 최상위 필드가 있으면 400으로 거부한다(역직렬화 단위 테스트로 검증).
- [x] S3 업로드가 실패하면 `sensor_batch` 행을 저장하지 않는다.
- [x] `measured_from_ms`·`measured_to_ms`가 샘플 `t`의 최소·최대이고 `received_at_ms`가 서버 시각이다.
- [x] 로그 메시지에 샘플 값이 포함되지 않는다.
- [ ] (#632) `/app/sensor/batches/send`로 보낸 배치가 같은 규칙으로 저장되고 `/user/queue/sensor/result`로 `{seq, result}`를 발신 세션만 받는다. 검증 실패는 `REJECTED`다.
- [ ] (#632) 변형 목적지·raw 큐·미인증 SEND는 거절되고 기존 위치·심박 회귀 테스트가 통과한다.
- [x] Swagger에 성공·주요 예외 응답이 반영된다.
- [x] `./gradlew compileJava`로 `QSensorBatch`가 생성된다.
- [x] `bash scripts/harness/run-module-tests.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

신규 테이블이라 기존 코드·데이터 영향 없음. `S3Service` 인터페이스에 메서드 하나가 추가되며 기존 구현체는 `S3ServiceImpl` 하나다. 로컬·dev는 `ddl-auto: update`가 생성하고, 운영은 `ddl-auto: validate`라 배포 전에 `scripts/mysql/create_sensor_batch.sql`을 실행한다:

```sql
CREATE TABLE sensor_batch (
    sensor_batch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    stream_type VARCHAR(20) NOT NULL,
    batch_kind VARCHAR(20) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    gyro_mode VARCHAR(20) NOT NULL,
    on_body BOOLEAN NULL,
    measured_from_ms BIGINT NOT NULL,
    measured_to_ms BIGINT NOT NULL,
    sample_count INT NOT NULL,
    received_at_ms BIGINT NOT NULL,
    s3_key VARCHAR(255) NOT NULL,
    byte_size INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sensor_batch_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_sensor_batch_seq UNIQUE (member_id, device_id, session_id, stream_type, seq),
    INDEX idx_sensor_batch_member_stream_time (member_id, stream_type, measured_from_ms)
);
```

운영 항목: S3 버킷의 `sensor/` 접두사에 SSE와 수명주기 정책을 적용할지는 후속 연구 LLD에서 정한다. WebSocket 메시지 크기 제한은 Spring 기본 64KB를 유지한다.

## 9. 미결정 사항 (Open Questions)

- 없음. 연구 귀속 FK, 정리 기간, 원본 참조 필드, 착용자 교대 뒤 재전송(1.3.4)은 ADR-0030 후속 절에 기록했다.

## 10. 참고

- `apiDocs/BE_김동균_위듀_데이터정책서_v1_B_서버AI입력.md` 1단, 합의 대기 A·B
- ADR-0030, ADR-0026, LLD-0032(allowlist), LLD-0023(심박 WebSocket 패턴)
