# LLD-0108: 회원의 시간 범위 센서 ZIP 내보내기

> 내부 실험의 시작·끝 시각만으로 자이로·심박 원본 샘플을 확보한다. 기존 회차 ZIP 형식과 API는 유지한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #879 |
| 관련 ADR | [ADR-0030](../adr/ADR-0030-raw-sensor-batch-storage.md), [ADR-0031](../adr/ADR-0031-heart-rate-batch-store-first.md), [ADR-0032](../adr/ADR-0032-run-export.md) |
| 작성자 | Codex |
| 작성일 | 2026-10-23 |

## 1. 목적 / 배경

현재 내보내기는 닫힌 측정회차 ID가 있어야 한다. 내부 실험에서 기록한 시간만 아는 경우 관리자가 매번 DB 인덱스와 S3 객체를 찾아야 한다. 관리자 API가 한 회원의 지정 구간에 포함된 IMU·심박 샘플을 ZIP으로 만들고 단기 다운로드 URL을 제공한다.

## 2. 범위

### In scope

- widyu-api / widyu-domain / MySQL DDL / Swagger.
- 관리자 전용 요청·상태 API, 영속 큐 워커, S3 ZIP 업로드와 15분 presigned GET.
- `sensor_batch`에서 회원·IMU/HR 스트림·측정 구간이 겹치는 배치를 찾고, S3 원문을 읽어 샘플 시각으로 `[startAtMs, endAtMs)`에 속하는 값만 JSONL에 쓴다.
- 회차가 없거나 아직 열려 있어도 저장된 배치를 대상으로 한다. 회원별 검색이므로 다른 회원의 값은 섞이지 않는다.

### Out of scope

- 위치·하트비트·ECG·판정 기록, 모든 회원 통합 ZIP, 관리자 웹 화면.
- 기존 `run_<runId>.zip` 계약 및 계약검사기 변경.
- 생성한 ZIP의 자동 파기. 기존 회차 내보내기와 함께 보존 정책 후속 결정을 받는다.

## 3. 인터페이스 / API

`ROLE_ADMIN`만 호출한다. 시각은 UTC Unix epoch milliseconds이며 서버 시간대와 무관하다. 요청 구간은 시작 포함·끝 제외이고 최대 24시간이다. 일주일 자료는 날짜별 요청으로 나눈다.

```http
POST /api/v1/admin/members/{memberId}/sensor-exports
Content-Type: application/json

{"startAtMs": 1792684800000, "endAtMs": 1792688400000}

GET /api/v1/admin/members/{memberId}/sensor-exports/{exportId}
```

요청은 `202`와 `data: {exportId, memberId, startAtMs, endAtMs, status:"QUEUED", ...}`를 반환한다. 같은 회원·구간의 진행 중 잡은 재사용한다. 상태는 `QUEUED → RUNNING → DONE|FAILED`; `DONE`을 조회할 때만 `downloadUrl`과 `downloadExpiresAtMs`를 발급한다. URL은 15분간 유효하다. 실패 상태의 `errorType`은 예외 클래스명만 담는다.

ZIP에는 `manifest.json` 및 값이 있는 스트림 파일만 담는다. `imu_watch_acc.jsonl`, `imu_watch_gyro.jsonl`, `imu_phone_acc.jsonl`, `imu_phone_gyro.jsonl`의 각 줄은 `{ts_ms, device_id, batch_id, x, y, z}`이고 값 단위는 manifest에 각각 `mg`·`mrad/s`로 명시한다. `hr.jsonl`의 각 줄은 `{ts_ms, device_id, batch_id, bpm, accuracy}`다. manifest는 형식 버전, 회원 참조, 요청 구간, `[start,end)` 경계, 생성 시각, 파일별 샘플 수·SHA-256·bytes를 담는다. 데이터가 없으면 파일 목록이 빈 manifest ZIP을 만든다.

## 4. 데이터 모델

`sensor_range_export` 신규 테이블: `id` PK, `export_id` unique, `member_id` FK, `start_at_ms`, `end_at_ms`, `status`(QUEUED/RUNNING/DONE/FAILED), `requested_at_ms`, `started_at_ms`, `finished_at_ms`, `s3_key`, `bytes`, `sha256`, `error_type`, `created_at`, `updated_at`. `(member_id,start_at_ms,end_at_ms,status)`와 `(status,requested_at_ms)` 인덱스를 둔다. 기존 `sensor_batch`·`run_export` 스키마는 바꾸지 않는다.

## 5. 처리 흐름

1. 요청 트랜잭션에서 `member`를 잠그고 존재 여부와 `0 <= startAtMs < endAtMs <= now`, 최대 24시간을 검증한다. 같은 회원·구간의 `QUEUED`/`RUNNING` 잡이 있으면 반환하고 아니면 `QUEUED`를 저장한다. 회원 잠금이 동시 중복 요청을 직렬화한다.
2. `@Scheduled` 워커가 오래된 잡 하나를 조건부 갱신으로 선점한다. 정지된 `RUNNING`은 timeout 뒤 다시 큐에 넣는다. 상태 변경은 별도 빈의 `REQUIRES_NEW` 트랜잭션을 쓴다. 선점 시각을 작업 시도 식별자로 써서 만료된 이전 시도가 새 시도의 상태를 덮어쓰지 못하게 한다.
3. 조립기는 스트림별 배치를 `(measured_at_start_ms, id)` 키셋 페이지로 읽는다. 조건은 `member_id=?`, `stream=?`, `measured_at_end_ms >= startAtMs`, `measured_at_start_ms < endAtMs`다. 배치마다 S3 원문을 다운로드한다.
4. HR은 각 `samples[].ts_ms`로 필터한다. IMU는 축별 `t0_elapsed_ns + 누적 dt_ns`를 `anchor_epoch_ms + floor((elapsed_ns-anchor_elapsed_ns)/1_000_000)`으로 변환해 필터한다. overflow·원문 손상 시 부분 ZIP을 공개하지 않고 잡을 `FAILED`로 바꾼다. 가속도와 자이로의 독립 시간축을 합치거나 0으로 채우지 않는다.
5. 임시 JSONL·manifest를 ZIP으로 만들고 시도별 S3 키에 업로드한 후 현재 시도인 경우에만 `DONE`과 크기·SHA-256·키를 저장한다. 임시 디렉터리는 성공·실패 모두 삭제한다. 만료된 시도의 객체는 URL로 공개되지 않으며 추후 보존 정책의 정리 대상이다.

## 6. 예외 / 에러 처리

| 조건 | 결과 |
| --- | --- |
| 관리자 권한 없음 | 기존 `/api/v1/admin/**` 보안 규칙의 401/403 |
| 회원 없음 | `MEMBER_NOT_FOUND` 404 |
| 시각 누락·역전·미래 끝·24시간 초과 | `SENSOR_EXPORT_RANGE_INVALID` 400 |
| 회원·exportId 불일치 또는 없는 잡 | `SENSOR_EXPORT_NOT_FOUND` 404 |
| S3/원문/ZIP 실패 | 작업 `FAILED`·예외 클래스명만 응답/로그, 원본 값·URL 없음 |

## 7. 인수조건 (Acceptance Criteria)

- [ ] 관리자 요청은 영속 큐에 등록되고, 동일 회원·구간 동시 요청은 같은 잡을 돌려준다. 다른 회원의 잡은 조회할 수 없다.
- [ ] `[start,end)` 경계와 배치 경계를 가로지르는 IMU·HR 샘플을 실제 측정 시각으로 잘라 ZIP에 담는다. 다른 회원·범위 밖 값은 없다.
- [ ] 빈 구간은 빈 manifest ZIP을 반환한다. ZIP manifest의 샘플 수·바이트·SHA-256은 실제 파일과 일치한다.
- [ ] 범위 오류·회원 없음·권한 없음·S3 실패를 검증하고, 실패 시 부분 다운로드 URL과 원본 값이 노출되지 않는다.
- [ ] Swagger, MySQL DDL·ERD, 단위/통합 테스트, `bash scripts/harness/verify.sh`가 확인된다.

## 8. 영향 범위 / 마이그레이션

운영은 `ddl-auto: validate`이므로 배포 전 `scripts/mysql/create_sensor_range_export.sql`을 적용해야 한다. 기존 회차 내보내기는 그대로 동작한다. 생성 ZIP은 S3에 남으므로 보존·파기 정책이 정해질 때 정리 작업을 별도로 적용한다.

## 9. 미결정 사항 (Open Questions)

- 없음. 요청의 한 회원 범위·24시간 상한은 현재 내부 실험을 위해 채택한 API 계약이다. 여러 회원 통합이나 더 긴 단일 ZIP이 필요하면 후속 계약으로 변경한다.

## 10. 참고

- [LLD-0050](LLD-0050-run-export.md), [ADR-0032](../adr/ADR-0032-run-export.md), [ERD](../erd/ERD-0001-initial-domain.md)
