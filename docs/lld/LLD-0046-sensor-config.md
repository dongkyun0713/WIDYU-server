# LLD-0046: 수집 설정 하달과 수집 모드 전환 (B12)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> 이슈 #633을 B12 범위로 넓힌 것이다. v1 설계(배포 설정값 `gyro_mode` 하나, 기동 시 1회 조회)는 폐기한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #633 |
| 관련 ADR | [ADR-0030 v2](../adr/ADR-0030-raw-sensor-batch-storage.md) |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #644(B8, 5b434f8) — `CollectionRunService.hasOpenRun`. 이 브랜치는 feature/643 위에 쌓인다 |

## 1. 목적 / 배경

정책서 v1.1 1.6.4는 서버가 워치에 `gyro_mode`와 **수집 모드**(product/research)를 정해 내려주도록 확정했다. 수집 모드는 참가자·회차별 값이 아니라 실증 프로토콜 단위 운영값이며, 연구 세션 동안 `research`, 그 밖에는 `product`다. 전환 수단은 합의 대기 K5였고 질의 ②에 「열린 회차 유무로 서버가 정하고 워치가 60초마다 조회」로 회신하기로 했다. 작업지시서 B12는 표본율·배치 길이·충격 문턱·보강 구간·주기까지 설정 전체를 내려보내고, 앱이 실제 적용한 값이 배치에 실려 돌아오면 내려보낸 값과 대조하라고 한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain(`sensor_batch.config_mismatch` 컬럼)
- `GET /api/v1/sensor/config` (인증 사용자). 설정 전체 + 호출자 기준 `collection_mode`
- 설정 기본값 yml(`application-sensor.yml`) + `SensorProperties` 확장
- 배치 수신 시 `fs_hz_requested`·`collection_mode`·`gyro_mode` 대조 → 불일치 표시

### Out of scope
- 관리자 변경 API·push 경로(없음. 회차 열기·닫기가 전환 수단)
- 참가자별 개별 설정
- 워치가 언제 다시 읽는지(앱 정책. 서버는 60초 이내 재조회를 권고만 한다)

## 3. 인터페이스 / API

```http
GET /api/v1/sensor/config
```

응답 `data` (`SensorConfigResponse`):

```json
{
  "collectionMode": "research",
  "gyroMode": "continuous",
  "accFsHz": 50,
  "batchSec": 1,
  "impactThresholdG": 1.8,
  "backfillBeforeSec": 2,
  "backfillAfterSec": 10,
  "hrUploadSec": 1,
  "locationMoveSec": 5,
  "locationKeepaliveSec": 60,
  "heartbeatSec": 60,
  "refreshSec": 60
}
```

`collectionMode`만 호출자에 따라 다르다(열린 회차 있음 → `research`, 없음 → `product`). 나머지는 서버 설정값이다. `refreshSec`은 워치가 다시 읽을 권고 주기다.

## 4. 데이터 모델

DB 변경 없음. `application-sensor.yml`:

```yaml
sensor:
  max-payload-bytes: ${SENSOR_MAX_PAYLOAD_BYTES:32768}
  config:
    gyro-mode: ${SENSOR_GYRO_MODE:continuous}
    acc-fs-hz: ${SENSOR_ACC_FS_HZ:50}
    batch-sec: 1
    impact-threshold-g: 1.8
    backfill-before-sec: 2
    backfill-after-sec: 10
    hr-upload-sec: 1
    location-move-sec: 5
    location-keepalive-sec: 60
    heartbeat-sec: 60
    refresh-sec: 60
```

`SensorProperties(int maxPayloadBytes, Config config)`로 확장. `sensor_batch`에 `config_mismatch BOOLEAN NOT NULL DEFAULT false` 컬럼 추가(DDL ALTER).

## 5. 처리 흐름

- 조회: `SecurityUtil.getCurrentMemberId()` → `collectionRunService.hasOpenRun(memberId)` → `SensorConfigResponse.of(properties.config(), mode)`. 서비스 하나(`SensorConfigService`), 트랜잭션 `readOnly`.
- 배치 수신(`SensorBatchService.ingest`, 회차 귀속 뒤·S3 PUT 앞): 서버 지시값과 배치 값을 대조한다.
  - 기준 `collection_mode`: 회차 귀속(B8) 결과에 회차가 없으면 `product`. 회차가 정해졌는데 서버가 그 값을 아직 모르면(앱이 `run_id`를 실어 보냈거나 재전송이라 조회를 건너뛴 경우) `CollectionRunService.findCollectionMode(runId)`로 배치당 한 번 조회한다. 모르는 `run_id`면 기준이 없으므로 `collection_mode` 대조만 건너뛰고 나머지 필드는 그대로 대조한다.
  - 기준 `gyro_mode`·`acc_fs_hz`: `SensorProperties.config`.
  - 배치의 `collection_mode`·`gyro_mode`·`acc.fs_hz_requested`(acc가 있을 때) 중 하나라도 다르면 `config_mismatch=true`로 저장하고 WARN 로그(memberId·batchId·다른 필드 이름만, 값 금지). **거부하지 않는다** — 설정이 적용되지 않은 채 도는 상황을 조용히 넘기지 않는 것이 목적이다(지시서 B12 「확인」).
  - `fs_hz_requested`는 double이라 `Double.compare == 0`으로 본다.
- `SensorConfigService.currentConfig(memberId)`가 조회 API와 대조 양쪽에 같은 값을 준다.
- 회차를 연 뒤 워치가 설정을 다시 읽기 전(최대 `refreshSec`)에 도착한 배치는 불일치로 표시된다. 의도된 동작이며 전환 지연의 기록이다.
- 보강 배치처럼 `acc`가 없는 배치는 표본율(`fs_hz_requested`) 대조를 하지 않는다.

## 6. 예외 / 에러 처리

신규 에러코드 없음. 인증 없는 조회는 401.

## 7. 인수조건 (Acceptance Criteria)

- [x] 열린 회차가 없는 회원의 조회는 `collectionMode=product`, 회차를 열면 `research`, 닫으면 다시 `product`.
- [x] 나머지 값은 yml 설정값 그대로이고 환경변수로 덮인다.
- [x] 배치의 `fs_hz_requested`가 설정과 다르면 `config_mismatch=true`로 저장되고 배치는 `STORED`다. `collection_mode`가 회차의 값과 다를 때, `gyro_mode`가 설정과 다를 때도 같다. 전부 같으면 `false`.
- [x] 로그에 값이 아니라 필드 이름만 남는다.
- [x] Swagger 반영, `run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

`sensor_batch`에 컬럼 1개 추가. `create_sensor_batch.sql`은 미배포 테이블이라 그 스크립트에 컬럼을 넣고, 이미 v2 스키마를 만든 dev를 위해 `scripts/mysql/add_sensor_batch_config_mismatch.sql`(`ALTER TABLE sensor_batch ADD COLUMN config_mismatch BOOLEAN NOT NULL DEFAULT FALSE`)도 둔다. `SensorProperties`가 `Config`를 갖도록 바뀌며 기존 `maxPayloadBytes`는 유지. Swagger `SensorConfigDocs`.

## 9. 미결정 사항 (Open Questions)

- 연구 세션 모드에서 자이로 회수율 통과선(검사기 G8 기본값 95%)은 설정값이며 서버 소관 아님.

## 10. 참고

- 작업지시서 v2 B12, 정책서 v1.1 1.6.4·1.2.6, 개정요약 개정 2, 합의 대기 K5
- LLD-0041 v2, LLD-0045
