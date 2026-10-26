# LLD-0108: 회원·시간대로 위치 원본과 심박·움직임 자료를 CSV로 내보내기

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #898 |
| 관련 ADR | [ADR-0030](../adr/ADR-0030-raw-sensor-batch-storage.md)(센서 원문 S3 보관), [ADR-0032](../adr/ADR-0032-run-export.md)(회차 내보내기) |
| 작성자 | Claude |
| 작성일 | 2026-10-27 |

## 1. 목적 / 배경

연구팀의 「위듀 탑승 시험」은 사람이 적은 탄 시각·내린 시각마다 앞뒤 10분을 붙여, 서버에 저장된 위치 원본과 심박·움직임 자료를 CSV로 받아 「차를 타고 이동 중」인지 가리는 규칙을 시험한다. 기존 회차 내보내기(LLD-0050)는 회차에 귀속된 자료만 JSONL로 묶어 시간대로 자르지 못하고, 회차 없이 쌓인 자료(`run_id` NULL)는 꺼내지 못한다. 관리자가 회원 ID와 한국 시간 시간대만으로 한 번에 CSV zip을 받게 한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api, 운영 스크립트
- 관리자 전용 동기 API 하나. 응답은 CSV 여섯 개를 담은 zip.
- 위치 원본(`location_fix`), 기기 상태(`device_heartbeat`), 센서 묶음 목록(`sensor_batch`), 그리고 S3 원문을 표본 단위로 펼친 심박(`hr`)·가속도/자이로(`imu_watch`·`imu_phone`).
- 개발 서버에서 회원별 수신 상태를 보는 조회 스크립트 `scripts/ops/sensor-status.sh`.

### Out of scope
- 90분을 넘는 구간(배터리 시험 같은 하루 단위). 회차 내보내기나 날짜별 분할로 다룬다.
- 비동기 잡·다운로드 URL. 닫은 PR #880의 방식이며, 탑승 구간 크기에서는 동기로 충분하다.
- 판정 기록·사건, 여러 회원 통합 zip, 관리자 웹 화면.
- 시간대 값의 감사 기록(아래 5절 접속기록 참고).

## 3. 인터페이스 / API

```http
GET /api/v1/admin/members/{memberId}/sensor-exports?from=2026-10-30T09:10:00&to=2026-10-30T09:28:00&padMinutes=10
Authorization: Bearer <관리자 토큰>

200 OK
Content-Type: application/zip
Content-Disposition: attachment; filename="sensor_member28_20261030T0900-0938.zip"
```

| 파라미터 | 필수 | 설명 |
| --- | --- | --- |
| `from`, `to` | O | 탄 시각·내린 시각. **한국 시간(Asia/Seoul)** ISO 날짜시간. `from < to` |
| `padMinutes` | X | 앞뒤 여유(분). 기본 10, 0~60 |

내보내는 구간은 `[from - pad, to + pad]`(양 끝 포함)이고 최대 90분이다. 모든 CSV 행에 `phase`를 붙인다: 시각이 `from`보다 앞이면 `before`, `to`보다 뒤면 `after`, 그 사이(양 끝 포함)는 `during`. 시각 열은 사람이 읽는 `ts_kst`(`yyyy-MM-dd HH:mm:ss.SSS`, 한국 시간)와 원래 값 `ts_ms`(UTC epoch ms)를 함께 둔다.

| 파일 | 원천 | 행 단위 · 주요 열 |
| --- | --- | --- |
| `location.csv` | `location_fix` (`ts_ms` 기준) | 위치 한 점: `device_id, session_id, seq, lat, lon, accuracy_m, speed_mps, speed_accuracy_mps, heading_deg, altitude_m, provider, is_mock, reason, run_id, server_received_at_ms` |
| `hr.csv` | `hr` 묶음 S3 원문 `samples[]` | 심박 표본: `device_id, batch_id, bpm, accuracy` |
| `imu.csv` | `imu_*` 묶음 S3 원문 `acc`·`gyro` | 축 표본: `device_id, stream, batch_id, sensor(acc/gyro), x, y, z, unit(mg/mrad_s)` |
| `sensor_batches.csv` | `sensor_batch` (측정 구간이 내보내는 구간과 겹치는 묶음) | 묶음 한 건: `stream, device_id, batch_id, session_id, seq, measured_start_ms, measured_end_ms, acc_n, gyro_n, sample_count, on_body, wear_state, missing_reason, watch_battery_pct, quality_status, collection_mode, gyro_mode, trigger_kind, server_received_at_ms` (`phase`·`ts_*`는 측정 시작 기준) |
| `device_heartbeat.csv` | `device_heartbeat` (`ts_ms` 기준) | 기기 상태 한 건: `device_id, watch_device_id, watch_connected, watch_on_body, watch_battery_pct, phone_battery_pct, phone_charging, socket_connected, last_hr_ts_ms, last_imu_ts_ms, location_permission, background_restricted, phone_queue_depth, watch_queue_depth, phone_app_ver, watch_app_ver, phone_os` |
| `summary.csv` | — | `key,value`: 회원 ID, 요청 시각, 여유, 구간, 생성 시각, 파일별 행 수, 펼치지 못한 묶음(`skipped_batch`: 묶음 ID와 사유) |

IMU 표본 시각은 축마다 `t0_elapsed_ns`에서 `dt_ns`를 누적한 경과 시각을 `anchor_epoch_ms + floor((elapsed_ns - anchor_elapsed_ns) / 1_000_000)`으로 바꾼다. 가속도와 자이로는 시간축이 독립이라 합치지 않는다. 경계에 걸친 묶음은 구간 안의 표본만 쓴다. 자료가 없으면 머리글만 있는 CSV가 들어간다.

## 4. 데이터 모델

스키마 변경 없음. 저장소 조회 메서드만 추가한다.

- `LocationFixRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc` — 기존 인덱스 `(member_id, ts_ms)`
- `DeviceHeartbeatRepository.findByMemberIdAndTsMsBetweenOrderByTsMsAsc` — 기존 인덱스 `(member_id, ts_ms)`
- `SensorBatchRepository.findMemberWindowBatches` — `member_id = ? AND measured_at_end_ms >= start AND measured_at_start_ms <= end`, 기존 인덱스 `(member_id, stream, measured_at_start_ms)`

응답 파일 이름·바이트는 `dto/response/SensorWindowExportFile` record로 컨트롤러에 넘긴다.

## 5. 처리 흐름

1. `SecurityConfig`의 `/api/v1/admin/**` `hasRole("ADMIN")`로 관리자만 들어온다. `AdminAccessLogInterceptor`(LLD-0057)가 관리자·시각·경로의 `memberId`·결과를 접속기록에 남긴다. 쿼리 값(시간대)은 정책상 남기지 않는다.
2. `SensorWindowExportService.export`가 `from < to`, `padMinutes` 0~60, 여유 포함 구간 90분 이하를 검사하고 회원 존재를 확인한다.
3. 세 저장소에서 행을 읽는다. 각 조회가 자기 읽기 트랜잭션에서 끝나므로 S3를 받는 동안 DB 커넥션을 붙잡지 않는다. 서비스 메서드에는 트랜잭션을 두지 않는다.
4. `hr`·`imu_*` 묶음의 S3 원문을 전용 고정 스레드 32개로 병렬로 받는다. 출력은 묶음 순서를 지킨다. 원문을 읽지 못하거나 축 구조가 깨진 묶음은 건너뛰고 `summary.csv`에 남긴다. S3 호출 자체가 실패하면 요청 전체를 실패시킨다(빠진 자료를 조용히 감추지 않는다).
5. CSV를 zip 스트림에 차례로 쓰고 바이트 배열로 돌려준다. 응답을 쓰기 전에 zip을 다 만들기 때문에 실패하면 정상 오류 응답이 나간다.
6. 로그에는 회원 ID와 행 수만 남기고 좌표·심박 값은 남기지 않는다(LLD-0029).

## 6. 예외 / 에러 처리

| 상황 | 응답 |
| --- | --- |
| `from >= to` 또는 여유 포함 구간이 90분 초과 | 400 `SENSOR_4004` 내보내기 시간대가 올바르지 않습니다. |
| `padMinutes`가 0~60 밖, `from`·`to` 누락·형식 오류 | 400 (공통 요청 검증) |
| 회원 없음 | 404 `MEMBER_4041` |
| 관리자 아님 | 403 (보안 설정) |
| S3 조회 실패 | 500 (요청 전체 실패) |
| 원문 손상 묶음 | 200, 해당 묶음만 건너뛰고 `summary.csv`에 `skipped_batch` |

## 7. 인수조건 (Acceptance Criteria)

- [x] 회원 ID와 한국 시간 `from`·`to`를 주면 앞뒤 여유를 붙인 구간의 CSV 여섯 개가 zip으로 내려온다.
- [x] 구간 밖 위치·기기 상태·표본은 들어가지 않고, 경계에 걸친 묶음은 구간 안 표본만 들어간다.
- [x] IMU 표본 시각이 3절 환산식과 같고, 각 행의 `phase`가 `from`·`to` 기준으로 매겨진다.
- [x] 원문이 손상된 묶음은 건너뛰고 `summary.csv`에 묶음 ID와 사유가 남는다.
- [x] `from >= to`나 90분 초과 구간은 `SENSOR_4004`, 없는 회원은 `MEMBER_4041`이다.
- [x] 관리자 경로라 접속기록에 관리자·대상 회원이 남는다(기존 인터셉터).
- [x] 좌표·심박 값은 로그에 남지 않는다.
- [x] Swagger에 파라미터·zip 구성·오류가 반영된다.
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

신규 API와 조회 메서드만 추가한다. DDL·ENUM 변경 없음. S3 병렬 다운로드는 이 기능 전용 스레드 32개를 쓰며 다른 요청의 실행기와 섞이지 않는다. 기존 회차 내보내기는 바뀌지 않는다.

## 9. 미결정 사항 (Open Questions)

없음.
