# LLD-0045: 측정회차·기기 배정·마커 등록 (B8)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #643 (작업지시서 B8) |
| 관련 ADR | [ADR-0030 v2](../adr/ADR-0030-raw-sensor-batch-storage.md) |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #634 v2, PR #642(B3 clock_mapping) — 이 브랜치는 feature/641 위에 쌓인다 |

## 1. 목적 / 배경

실증은 기기를 여러 참가자가 돌려 쓴다. 기기 번호만으로는 자료가 누구 것인지 알 수 없고, 측정회차가 「누가·어떤 기기를·어디에 차고·언제부터 언제까지·몇 회차로」를 묶는다(지시서 B8). 회차 동안 연구자가 찍는 표시(마커)는 원시 신호와 같은 시간축에 놓여야 하는 **정답 라벨**이며 판정 결과 기록과 섞지 않는다(정책 1.8.1~1.8.3 [확정]). 질의 ①은 경량 테이블로 확정됐다(2026-09-19): 서면 연구 동의 판은 회차의 `consent_version`에, 보존 날짜 셋은 회차의 null 허용 컬럼에 둔다.

이 문서는 회차·기기 배정·마커까지다. 인시던트(본인확인·SOS·판정 라벨, 형식서 §3.7)는 별도 LLD로 뺀다. 상태기계와 응답 마감 규약이 독립된 물건이다.

## 2. 범위

### In scope
- widyu-api / widyu-domain
- `collection_run`·`run_device_assignment`·`run_marker` 테이블
- 회차 열기·닫기·기기 배정·해제·마커 등록·조회 API
- 배치 수신 시 회차 귀속(`run_id`가 null인 배치를 열린 회차·기기 배정으로 서버가 묶음)
- B12가 쓸 「회원에게 열린 회차가 있는가」 조회

### Out of scope
- 인시던트(별도 LLD)
- 내보내기 `run.json` 생성(B9)
- 수용 영수증(`acceptance_receipt_id`, K3), 별도 ECG 시계 오프셋(K4) — 컬럼만 null 허용으로 둔다
- 「없으면 수집 거부」 게이트 — 실증 기간 완화 확정. 보존 날짜·동의 버전이 없어도 회차를 열 수 있다
- 마커 종류 값 목록(미정, 연구 프로토콜) — 문자열로 받고 검증하지 않는다

## 3. 인터페이스 / API

호출 주체는 운영자(연구자)이며 **관리자 계정(`ROLE_ADMIN`)으로 호출한다**(사용자 결정 2026-09-20). 경로는 `/api/v1/admin/collection-runs/**`이고 `SecurityConfig`의 기존 `/api/v1/admin/**` 규칙이 적용된다. 컨트롤러는 `com.widyu.run.controller.AdminCollectionRunController`, Swagger는 `controller/docs/AdminCollectionRunDocs`.

```http
POST   /api/v1/admin/collection-runs                          회차 열기 (+기기 배정 동시)
GET    /api/v1/admin/collection-runs/{runId}                  조회 (배정·마커 포함)
PATCH  /api/v1/admin/collection-runs/{runId}/close            회차 닫기
POST   /api/v1/admin/collection-runs/{runId}/devices          기기 배정 추가
PATCH  /api/v1/admin/collection-runs/{runId}/devices/{assignmentId}/unassign
POST   /api/v1/admin/collection-runs/{runId}/markers          마커 등록 (멱등)
```

열기 요청:
```json
{
  "subjectMemberId": 1023,
  "studyId": "STUDY-2026", "participationId": "P-001",
  "protocolRef": "PROTO-A", "consentVersion": "v1",
  "collectionMode": "research",
  "startedAtMs": 1760000000000,
  "retention": { "dataPolicy": "RETAIN", "identifiedUntil": "2027-03-31", "pseudonymizedAt": "2027-03-31", "researchUntil": "2029-03-31" },
  "devices": [ { "deviceId": "gw-3f2a", "role": "watch", "wearSite": "LEFT_WRIST" }, { "deviceId": "ph-9c1", "role": "phone", "wearSite": "POCKET" } ]
}
```
- `subjectMemberId` 필수(존재하는 회원). 나머지 null 허용. `collectionMode` 기본 `research`. `startedAtMs` 기본 now.
- `retention`은 통째로 null 허용. `dataPolicy == "RETAIN"`이면 세 날짜 필수(형식서 §6). `identified_until ≤ pseudonymized_at ≤ research_until`.
- `devices[].role ∈ watch | phone | external_ecg | operator_marker`, `wearSite` 문자열(값 집합 S1 미정). 같은 `deviceId`가 **다른 열린 회차**에 배정돼 있으면 409(기기는 한 번에 한 참가자). `assignedAtMs`는 회차 시작보다 앞설 수 없다.
- 회원당 열린 회차는 1개(409). 두 규칙은 사전 조회만으로 끝내지 않고 아래 nullable marker UK로 동시 요청에도 보장한다.

마커 요청 (1.8.2 필수 항목 그대로):
```json
{
  "markerId": "01j8zmark0000000000000001",
  "kind": "TASK_START", "label": "sit_to_stand",
  "sourceElapsedNs": "55120000001", "tsMs": 1760000012000,
  "source": "OPERATOR_APP", "sourceDeviceId": "op-1",
  "clock": { "bootId": "…", "clockMappingId": "cm-op-1", "anchorElapsedNs": "…", "anchorEpochMs": 1760000000000, "uncertaintyMs": 2.0 }
}
```
- `markerId` 불변·멱등: 같은 id가 다시 오면 저장하지 않고 200 + 기존 행. 내용이 다르면 409.
- `source ∈ OPERATOR_APP | PARTICIPANT | AUTO`. `sourceElapsedNs` 문자열. `clock`은 마커 단말의 매핑이며 **`ClockMappingService.register`로 같은 규칙으로 등록**한다(1.8.3, B3 재사용). 관측 범위는 `sourceElapsedNs` 한 점.
- 마커 시각은 회차 구간 `[started, ended)` 안이어야 한다(검사기 H4). 열린 회차면 상한 없음.

응답은 `ApiResponseTemplate`, `data`는 회차 전체(`CollectionRunResponse.from`) — `runId`, 회원, 기간, 프로토콜, 동의 판, 수집 모드, 보존 정보, `devices[]`, `markers[]`, `status`.

## 4. 데이터 모델

### `collection_run` (엔티티 `com.widyu.run.CollectionRun`, widyu-domain)

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `run_id` | VARCHAR(40) | NOT NULL UK. 서버 발급 `run-` + `UUID.randomUUID()` 하이픈 제거 32자 소문자 hex. 정렬 가능성이 필요 없어 ULID를 만들지 않는다 |
| `member_id` | BIGINT FK member | NOT NULL (subject) |
| `study_id`, `participation_id` | VARCHAR(64) | NULL |
| `protocol_ref` | VARCHAR(64) | NULL |
| `consent_version` | VARCHAR(50) | NULL (서면 연구 동의 판) |
| `collection_mode` | VARCHAR(10) | NOT NULL (`product`/`research`) |
| `started_at_ms` | BIGINT | NOT NULL |
| `ended_at_ms` | BIGINT | NULL |
| `status` | VARCHAR(10) | NOT NULL `OPEN`/`CLOSED` |
| `open_marker` | TINYINT | OPEN이면 1, CLOSED이면 NULL. `(member_id, open_marker)` UK로 회원당 OPEN 하나를 강제 |
| `data_policy` | VARCHAR(20) | NULL (`RETAIN` 등, 값 집합 미정) |
| `identified_until`, `pseudonymized_at`, `research_until` | DATE | NULL |
| `quality_notes` | VARCHAR(500) | NULL |
| `missing_reason` | VARCHAR(64) | NULL |
| `acceptance_receipt_id` | VARCHAR(64) | NULL (K3) |
| `created_at`, `updated_at` | | |

인덱스: UK `run_id`, UK `(member_id, open_marker)`; `idx_collection_run_member_status (member_id, status)`.

### `run_device_assignment`

`id`, `assignment_id` VARCHAR(40) UK(`asg-` + UUID 32 hex), `run_id` FK collection_run(id), `device_id` VARCHAR(64), `role` VARCHAR(20), `wear_site` VARCHAR(30) NULL, `assigned_at_ms`, `unassigned_at_ms` NULL, `active_marker` TINYINT NULL. 배정 중이면 `active_marker=1`, 해제하면 NULL이고 UK `(device_id, active_marker)`가 기기 이중 배정을 막는다. 인덱스 `(device_id, unassigned_at_ms)`.

### `run_marker`

`id`, `marker_id` VARCHAR(64) UK(앱 발급, `^[a-z0-9._-]{1,64}$`), `run_id` FK, `kind` VARCHAR(30), `label` VARCHAR(100) NULL, `source_elapsed_ns` BIGINT, `ts_ms` BIGINT, `source` VARCHAR(20), `source_device_id` VARCHAR(64), `clock_mapping_id` VARCHAR(64), `created_at`. 인덱스 `(run_id, ts_ms)`.

`sensor_batch` 변경 없음(`run_id`·`study_id`·`participation_id` 컬럼 이미 있음).

## 5. 처리 흐름

- **열기**: 회원 존재 → 회원의 OPEN 회차 없음 → 각 기기의 다른 OPEN 회차 미배정 → retention 검증 → `run_id` 발급 → 저장. `open_marker`·`active_marker` UK 충돌은 각각 409으로 변환한다. 제약 위반을 독립 `REQUIRES_NEW` 삽입에서 감지해 바깥 트랜잭션이 rollback-only가 되지 않게 한다.
- **닫기**: OPEN만. `ended_at_ms`(기본 now, `≥ started`) 기록, 미해제 배정은 같은 시각으로 해제, `status=CLOSED`.
- **배정/해제**: OPEN 회차만. 배정 시각 `≥ run.started`, 해제 시각 `≥ assigned`; 이미 해제한 배정은 다시 해제할 수 없다.
- **마커**: OPEN 또는 CLOSED 모두 허용(현장에서 늦게 올릴 수 있다). `markerId` 중복 → 내용 비교 뒤에도 요청 clock을 다시 `ClockMappingService.register(clock, sourceDeviceId, sourceElapsedNs, sourceElapsedNs)`로 대조한다. 신규 insert의 `marker_id` UK 경합은 독립 transaction에서 감지한 뒤 다시 조회·동일 내용 비교로 멱등 복구한다. 시각 범위 검사.
- **배치 귀속** (`SensorBatchService.ingest`, 검증 뒤·S3 앞): `request.run_id` 또는 재전송의 `original_run_id`가 있으면, 그 회차가 요청 회원 소유이고 해당 기기가 `measured_at_start_ms`에 실제 배정됐는지 확인한다. 실패하면 404이고 클라이언트의 연구 식별자는 신뢰하지 않는다. 통과하면 서버 회차의 `run_id`·`study_id`·`participation_id`를 저장한다. 둘 다 없으면 회원의 OPEN 회차 중 그 `device_id`가 배정된 것을 찾아 같은 값을 채운다. 없으면 null 유지(운영 외 자료).
- **B12용**: `existsByMemberIdAndStatus(memberId, OPEN)`.

트랜잭션: 회차 서비스 메서드는 `@Transactional`. 배치 귀속 조회는 `readOnly`. Facade·이벤트 없음.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `RUN_4041 RUN_NOT_FOUND` | 404 | `runId`·`assignmentId` 없음, 또는 명시 회차가 회원·기기·측정 시각 배정과 불일치 |
| `RUN_4090 RUN_ALREADY_OPEN` | 409 | 회원에게 OPEN 회차 존재 |
| `RUN_4091 RUN_DEVICE_ALREADY_ASSIGNED` | 409 | 기기가 다른 OPEN 회차에 배정 |
| `RUN_4092 RUN_MARKER_CONFLICT` | 409 | 같은 `markerId`에 다른 내용 |
| `RUN_4000 RUN_NOT_OPEN` | 400 | 닫힌 회차에 닫기·배정·해제 |
| `RUN_4001 RUN_RETENTION_INVALID` | 400 | `RETAIN`인데 날짜 누락, 순서 위반 |
| `RUN_4002 RUN_MARKER_OUT_OF_RANGE` | 400 | 마커 시각이 회차 구간 밖 |
| `SENSOR_4090` | 409 | 마커 `clock` 충돌 (B3) |

표에 없는 두 위반은 기존 범용 코드를 쓴다. 종료 시각이 시작보다 앞서거나 해제 시각이 배정보다 앞서면 `REQ_4000 BAD_REQUEST`에 상세 메시지를 붙여 400으로 응답한다.
마커의 나노초 값이 long 범위를 넘으면 `RUN_MARKER_OUT_OF_RANGE`다(형식 자체는 DTO `@Pattern`이 먼저 거른다).

## 7. 인수조건 (Acceptance Criteria)

- [x] 워치 한 대를 오전 A 회차, 오후 B 회차에 배정하면(A 닫고 B 열기) 오전·오후 배치의 `run_id`가 각각 다르고 `member_id`와 일치한다(지시서 B8 완료 기준).
- [x] 회원에게 OPEN 회차가 있으면 새 회차 열기는 409, 기기가 다른 열린 회차에 배정돼 있으면 409. 두 규칙은 DB UK로 동시 요청에도 보장한다.
- [x] `RETAIN`인데 날짜 하나가 빠지면 400. `retention` 전체 null은 허용.
- [x] 같은 `markerId` 재등록은 행이 늘지 않고 200, 내용이 다르면 409. 마커의 `clock`이 `clock_mapping`에 등록된다.
- [x] 마커 `tsMs`가 `started_at_ms` 앞이면 400.
- [x] `run_id` null인 배치가 열린 회차·배정 기기에 맞으면 서버 회차의 `run_id`·`study_id`·`participation_id`가 채워진다. 명시 `run_id`와 재전송 `original_run_id`도 회원·기기·시각 배정을 통과해야 한다.
- [x] 닫기 시 미해제 배정이 종료 시각으로 해제된다.
- [x] Swagger 반영, `compileJava`(Q클래스 3개), `run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

신규 테이블 3개 → `scripts/mysql/create_collection_run.sql`. `SensorBatchService.ingest`에 귀속 호출 추가. `SecurityConfig` 변경 없음(`/api/v1/admin/**` 기존 규칙).

## 9. 미결정 사항 (Open Questions)

- 없음. `run_id`는 서버 발급(`run-<UUID hex>`)이며 사람이 읽는 회차 번호는 `protocol_ref`에 둔다. `wear_site`·`data_policy`·마커 `kind` 값 집합(S1·S2·프로토콜)은 문자열로 보관한다. 인시던트는 별도 LLD.

## 10. 참고

- 작업지시서 B8·부록 C, 정책서 1.8.1~1.8.3, 형식서 §6, 검사기 C8·C9·C11·H1~H5
