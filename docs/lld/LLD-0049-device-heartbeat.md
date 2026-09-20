# LLD-0049: 기기 상태 하트비트 수신 (B7)

> 정책서에 없는 제안 항목(센서 계약 스트림 4, 작업지시서 B7). 내보내기 `heartbeat` 스트림의 근거다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #652 (작업지시서 B7) |
| 관련 ADR | [ADR-0030 v2](../adr/ADR-0030-raw-sensor-batch-storage.md) |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #645(B12, 8c4f254) — B8 회차 귀속 포함. 이 브랜치는 feature/633 위에 쌓인다(#647·#649와 형제) |

## 1. 목적 / 배경

자료가 비었을 때 왜 비었는지(배터리·미착용·앱 종료·네트워크)를 아는 유일한 근거다. 폰이 60초마다 보내는 기기 상태(부록 B `heartbeat`)를 저장한다. 내보내기 `heartbeat` 스트림(형식서 §3.6, 검사기 F4~F7)은 `phone{battery_pct, socket_connected, queue_depth}`·`watch{connected, battery_pct, hr_session, last_imu_ts_ms}`·`ts_ms`와 `_server` 봉투를 요구하고, `watch.last_imu_ts_ms`가 워치·폰 시계 오프셋을 재는 유일한 공통 이벤트다(B6 검사).

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `POST /api/v1/device/heartbeats` (REST, 60초 주기라 소켓 불필요)
- `device_heartbeat` 테이블: 원문 + 질의용 필드 + 봉투 시각. 회차 귀속

### Out of scope
- 하트비트 끊김 알림(지시서: 알림 기준은 범위 아님)
- 기기 모델·OS 기록 자리(합의 대기 S1) — `phone.os`·`app_ver`는 원문·필드에 남는다

## 3. 인터페이스 / API

```http
POST /api/v1/device/heartbeats
```
본문은 부록 B `heartbeat` 그대로(`v:1`, `ts_ms`, `device_id`, `session_id`, `phone{}`, `watch{}`) + `study_id`·`participation_id`·`run_id`(선택, null 허용). `seq`가 없으므로 멱등 키는 `(device_id, session_id, ts_ms)`.

```json
{ "v": 1, "ts_ms": 1760000000123, "device_id": "ph-9c1", "session_id": "s-20260920-01",
  "phone": { "battery_pct": 71, "charging": false, "os": "android/14", "app_ver": "1.4.2", "socket_connected": true, "location_permission": "always", "background_restricted": false, "queue_depth": 0 },
  "watch": { "connected": true, "device_id": "gw-3f2a", "battery_pct": 63, "app_ver": "1.4.2", "hr_session": "RUNNING", "on_body": true, "last_hr_ts_ms": 1760000000000, "last_imu_ts_ms": 1760000000100, "queue_depth": 0 } }
```

| 필드 | 필수 | 제약 |
| --- | --- | --- |
| `v` | O | `1` |
| `ts_ms` | O | epoch ms |
| `device_id`, `session_id` | O | `^[a-z0-9._-]{1,64}$` |
| `phone.battery_pct` | O | `0..100` |
| `phone.socket_connected`, `phone.queue_depth` | O | bool / `>= 0` |
| `phone.charging`, `phone.background_restricted` | X | bool |
| `phone.os` | X | 40자 이하 |
| `phone.app_ver`, `phone.location_permission` | X | 20자 이하 (컬럼 폭과 맞춘다) |
| `watch` | O | 객체 자체는 필수다 — `watch_connected`가 NOT NULL 컬럼이다 |
| `watch.connected` | O | bool |
| `watch.*` 나머지 | X | null 허용(`watch.connected=false`면 전부 null이다. 0으로 채우지 않는다). `hr_session`·`app_ver` 20자 이하 |
| 모르는 필드 | | 거절하지 않음(원문 보존) |

컨트롤러는 `@RequestBody byte[]`로 원문을 받는다(LLD-0041 패턴). 응답(`ApiResponseTemplate`) `data`: `{ "tsMs": 1760000000123, "result": "STORED" }` — `result ∈ STORED | DUPLICATE`.

## 4. 데이터 모델

`device_heartbeat` (엔티티 `com.widyu.device.DeviceHeartbeat`, widyu-domain): `id`, `member_id`, `device_id`, `session_id`, `ts_ms`, `run_id`·`study_id`·`participation_id` NULL, `phone_battery_pct` INT, `phone_charging` BOOL, `phone_os` VARCHAR(40), `phone_app_ver` VARCHAR(20), `socket_connected` BOOL, `location_permission` VARCHAR(20), `background_restricted` BOOL, `phone_queue_depth` INT, `watch_connected` BOOL, `watch_device_id` VARCHAR(64) NULL, `watch_battery_pct` INT NULL, `watch_app_ver` VARCHAR(20) NULL, `hr_session` VARCHAR(20) NULL, `watch_on_body` BOOL NULL, `last_hr_ts_ms` BIGINT NULL, `last_imu_ts_ms` BIGINT NULL, `watch_queue_depth` INT NULL, `server_received_at_ms`·`accepted_at_ms`·`persisted_at_ms`, `payload` TEXT, `payload_sha256` CHAR(64), `created_at`.
UK `(device_id, session_id, ts_ms)`. 인덱스 `(member_id, ts_ms)`, `(run_id)`. 하루 1,440행/참가자.

## 5. 처리 흐름

`DeviceHeartbeatService.ingest(memberId, byte[] payload)`:
1. `server_received_at_ms = now`. 회원 존재 확인.
2. 파싱(`FAIL_ON_UNKNOWN_PROPERTIES` off) → 실패 `HEARTBEAT_INVALID`. Bean Validation + 3절 제약 → 실패 `HEARTBEAT_INVALID`. `accepted_at_ms`. `v != 1`은 Bean Validation이 아니라 서비스에서 거부한다(SensorBatchService의 형식 버전 대조와 같은 방식).
3. 회차 귀속: `request.runId`가 있으면 그대로, 없으면 `collectionRunService.resolveRun(memberId, deviceId, tsMs)`.
4. `existsByDeviceIdAndSessionIdAndTsMs` → `DUPLICATE`.
5. sha256(원문) → `persisted_at_ms` → INSERT. 서비스에 `@Transactional`을 선언하지 않는다 — INSERT는 리포지토리 자체 트랜잭션에서 돈다(SensorBatchService와 같은 이유: 같은 클래스 안의 호출은 프록시를 타지 않아 애노테이션이 무효다). UK 경합 `DataIntegrityViolationException` → 재조회 후 있으면 `DUPLICATE`, 없으면 재throw.
6. `STORED`. 로그는 memberId·deviceId·result만(배터리·큐 값 등은 남기지 않는다).

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `DEVICE_4000` `HEARTBEAT_INVALID` | 400 | JSON 파싱 실패, 필수 필드 누락, 값 범위·형식 위반 |
| `MEMBER_4041` | 404 | 회원 없음 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 부록 B 하트비트를 보내면 행 1개, 원문·필드·봉투 시각이 남는다. 같은 `(device, session, ts_ms)` 재전송은 `DUPLICATE`.
- [x] `watch.connected=false`·`on_body=false`가 값 그대로 남는다(자료 공백 설명 근거).
- [x] `phone.battery_pct` 누락은 400.
- [x] 열린 회차·배정 폰이면 `run_id`가 채워진다.
- [x] 원문 바이트가 `payload`에 그대로 있고 `payload_sha256`이 그 바이트의 해시다.
- [x] `./gradlew compileJava`(QDeviceHeartbeat), `bash scripts/harness/run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

신규 테이블 → `scripts/mysql/create_device_heartbeat.sql`(4절 컬럼, UK·인덱스, FK `member_id`). `SecurityConfig` 변경 없음(`/api/v1/**` 인증). Swagger `DeviceHeartbeatDocs`. ERD, `backend/CLAUDE.md` `device` 절 신설.

## 9. 미결정 사항 (Open Questions)

- 없음. 하트비트 끊김 알림은 범위 밖(지시서). 기기 모델·OS 자리(S1)는 `phone.os`·`app_ver`로 원문·필드에 남는다.

## 10. 참고

- 작업지시서 v2 B7·부록 B 하트비트, 형식서 §3.6, 검사기 B6·F4~F7
- LLD-0041 v2(원문 바이트 수신), LLD-0045(회차 귀속)
