# LLD-0048: 위치 수신 보강 — 잰 시각·정확도·속도·사유와 원본 저장 (B6)


| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #648 (작업지시서 B6) |
| 관련 ADR | [ADR-0030 v2](../adr/ADR-0030-raw-sensor-batch-storage.md)(원문 보존 원칙), ADR-0007(위치 이벤트) |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #645(B12, 8c4f254) — B8 회차 귀속 포함. 이 브랜치는 feature/633 위에 쌓인다 |

## 1. 목적 / 배경

지금 위치는 `/app/location/update`로 `memberId·latitude·longitude·timestamp`만 받아 Redis(최신 1건·15분 trail)에 두고 RDB에 남기지 않는다. 지시서 B6은 잰 시각·정확도·속도·`reason`(move/keepalive/incident)을 받아 저장하고, 정확도가 낮은 위치도 원본 그대로 저장하라고 한다(정책 1.6.6 [확정], 위치 기준 기기는 폰 1.6.5). 내보내기 `location` 스트림(형식서 §3.4, 검사기 F1·F3·F8·F9·L8)은 레코드마다 `lat·lon·accuracy_m·speed_mps·provider·is_mock·reason·ts_ms` + `_server` 봉투를 요구한다.

## 2. 범위

### In scope
- widyu-api / widyu-domain
- `LocationUpdateRequest`에 v2 필드 추가(하위 호환: 기존 4필드만 보내는 앱도 동작)
- `location_fix` 테이블: 원문 JSON + 질의용 필드 + 봉투 시각. v2 필드가 온 요청만 저장
- 회차 귀속(`run_id`·`study_id`·`participation_id`, B8 재사용)

### Out of scope
- 실시간 브로드캐스트·Redis·안전구역 로직 변경(그대로)
- REST 재전송 경로(위치는 60초 keepalive가 있어 유실 허용. 필요해지면 `POST /api/v1/sensor/batches`의 `location` 스트림으로)
- 시계 매핑(위치는 `ts_ms`만 온다, 부록 B)
- 보호자 열람 기록·통보(L2, 별도)

## 3. 인터페이스 / API

`/app/location/update` 페이로드(부록 B `location` + 기존 필드):

```json
{ "v": 2, "memberId": 1023, "device_id": "ph-9c1", "session_id": "s-…", "seq": 5120,
  "study_id": null, "participation_id": null, "run_id": null,
  "lat": 37.5551, "lon": 126.9707, "accuracy_m": 8.5, "speed_mps": 0.9, "speed_accuracy_mps": 0.5,
  "heading_deg": 212.0, "altitude_m": 41.2, "provider": "fused", "is_mock": false,
  "ts_ms": 1760000000123, "reason": "move" }
```

- 기존 `latitude`·`longitude`·`timestamp`도 계속 받는다. `lat`/`lon`이 오면 그것을, 없으면 `latitude`/`longitude`를 쓴다(하위 호환). `v==2`이고 `device_id`·`ts_ms`가 있으면 **원본 저장 대상**이다.
- `reason ∈ move | keepalive | incident`(L8). `is_mock`은 **빼지 말고 `false`**로(F9). `accuracy_m > 100`도 저장(F3).
- 컨트롤러가 `Message<byte[]>`로 받아 원문을 서비스에 넘긴다(LLD-0041 3.2와 같은 방식). 기존 `@Valid @Payload LocationUpdateRequest` 파라미터는 원문 파싱으로 대체.

## 4. 데이터 모델

`location_fix` (엔티티 `com.widyu.location.raw.LocationFix`, widyu-domain):

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `member_id` | BIGINT FK | NOT NULL |
| `device_id`, `session_id` | VARCHAR(64) | NOT NULL |
| `seq` | BIGINT | NOT NULL |
| `study_id`, `participation_id`, `run_id` | VARCHAR(64) | NULL |
| `ts_ms` | BIGINT | NOT NULL (잰 시각) |
| `lat`, `lon` | DOUBLE | NOT NULL |
| `accuracy_m`, `speed_mps`, `speed_accuracy_mps`, `heading_deg`, `altitude_m` | DOUBLE | NULL |
| `provider` | VARCHAR(20) | NULL |
| `is_mock` | BOOLEAN | NOT NULL |
| `reason` | VARCHAR(12) | NOT NULL |
| `server_received_at_ms`, `accepted_at_ms`, `persisted_at_ms` | BIGINT | NOT NULL |
| `payload` | TEXT (utf8mb4) | NOT NULL — 원문 바이트 그대로 |
| `payload_sha256` | CHAR(64) | NOT NULL |
| `created_at` | | |

`member_id`는 FK 컬럼(`Long`)이며 JPA 연관(`@ManyToOne`)을 두지 않는다 — 쓰기 전용 원본 행이라 Member로 항해하지 않는다.
서비스·리포지토리 패키지는 `location/raw/application`·`location/raw/repository`다(CLAUDE.md DDD 레이아웃).

UK `uk_location_fix_seq (device_id, session_id, seq)`(멱등). 인덱스 `(member_id, ts_ms)`, `(run_id)`. 하루 최대 1.7만 행/참가자(5초 이동·60초 정지)라 RDB로 둔다. S3 객체를 만들지 않는다(레코드가 작고 많다).

## 5. 처리 흐름

1. 컨트롤러: 원문 바이트 → `RealtimeLocationService.updateAndBroadcast(memberId, payload)`.
2. 파싱(`FAIL_ON_UNKNOWN_PROPERTIES` off) → 본인 검증(기존) → 좌표 결정(하위 호환) → 기존 Redis·브로드캐스트·안전구역 로직 그대로.
3. `v==2`면 `LocationFixService.store(member, request, payload, times)`: `reason`·`is_mock` 검증(`session_id`·`seq` 누락도 `LOCATION_FIX_INVALID` — 멱등 키이자 NOT NULL 컬럼이다), 회차 귀속(`resolveRun(memberId, deviceId, tsMs)`), `existsByDeviceIdAndSessionIdAndSeq` → 있으면 스킵, INSERT(자기 트랜잭션). 실패해도 실시간 경로는 이미 끝났으므로 ACK는 기존대로 보내고 WARN(memberId·seq만).
4. `v` 없는 기존 요청은 원본 저장 없이 기존 동작.

## 6. 예외 / 에러 처리

`LOCATION_4000 LOCATION_FIX_INVALID`(400): `reason` 값 밖, `lat/lon` 범위 밖, `ts_ms` 없음. v2 검증 실패는 실시간 경로도 거부한다(잘못된 좌표를 브로드캐스트하지 않는다).

`store`는 `REQUIRES_NEW` — 실시간 경로 트랜잭션과 분리해 저장 실패가 ACK를 막지 않게 한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] v2 페이로드를 보내면 Redis 최신값·브로드캐스트가 기존과 같고 `location_fix`에 원문·필드·봉투 시각이 저장된다.
- [x] 기존 4필드 페이로드는 그대로 동작하고 `location_fix`에 남지 않는다.
- [x] `accuracy_m=250`인 fix가 저장된다. `is_mock=true`도 저장된다(값 그대로).
- [x] 같은 `(device, session, seq)` 재전송은 행이 늘지 않는다.
- [x] `reason=teleport`는 400.
- [x] 열린 회차·배정 폰이면 `run_id`가 채워진다.

## 8. 영향 범위 / 마이그레이션

`RealtimeLocationController` 파라미터 변경(`Message<byte[]>`), `LocationUpdateRequest` 필드 추가, 신규 테이블 DDL, ERD, CLAUDE.md location 절.

## 9. 미결정 사항 (Open Questions)

- 없음. 위치는 RDB 행으로 확정(레코드가 작고 많다). 폰 IMU(`imu_phone`)는 sensor 파이프라인 그대로.

## 10. 참고

- 작업지시서 v2 B6·부록 B 위치, 정책서 v1.1 1.6.5·1.6.6, 형식서 §3.4, 검사기 F1·F3·F8·F9·L8
- LLD-0001(실시간 위치), LLD-0041 v2(원문 바이트 수신 패턴), LLD-0045(회차 귀속)
