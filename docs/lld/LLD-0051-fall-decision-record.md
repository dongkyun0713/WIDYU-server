# LLD-0051: IMU 판정 입력 창과 판정 기록 (B10)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #656 (작업지시서 B10) |
| 관련 ADR | [ADR-0033](../adr/ADR-0033-fall-decision-record.md), ADR-0032 |
| 작성자 | Claude |
| 작성일 | 2026-09-20 |
| 선행 | PR #655(B9, 462e323). 이 브랜치는 feature/654 위에 쌓인다 |

## 1. 목적 / 배경

ADR-0033 맥락과 같다. 충격 표시가 붙은 IMU 배치를 판정 창으로 묶어 낙상 AI 입구로 넘기고, 정책서 1.4.2 필수 10필드로 판정을 기록하며, 내보내기에 `decisions.jsonl`을 싣는다. 모델은 미배포라 입구는 설정으로 꺼 둔다.

## 2. 범위

### In scope
- widyu-api / widyu-domain
- `decision_record` 테이블·엔티티
- `FallAssessmentService`(창 조립·인과성 필드·기록)와 `FallAssessmentClient`(AI HTTP 호출, 임시 입력 형식)
- `SensorBatchService.ingestImu` 저장 뒤 훅
- B9 내보내기에 `decisions.jsonl` 추가
- 설정 `sensor.fall-ai.*`

### Out of scope
- 낙상 모델 자체, 실제 입력 형식(AI 담당 다음 판)
- `ALERT` → 알림 경로, `alert_delivered` 갱신
- 심박 판정의 `decision_record` 기록
- 인시던트(별도 LLD)

## 3. 인터페이스 / API

외부 API 변경 없음. 배치 수신 API의 응답도 그대로다(판정은 응답에 영향 없음).

**AI 입구(임시 형태, 작업지시서 B10 제안)**: `POST {ai.server.url}{sensor.fall-ai.path}`(기본 `/api/fall`)

```json
{
  "user_id": "1023", "run_id": "run-…",
  "window": { "start_ms": 1760000098000, "end_ms": 1760000101000 },
  "acc": { "fs_hz_requested": 50, "t0_ms": 1760000098000, "dt_ms": [20, 20], "mg": [[20, -980, 110]] },
  "gyro": null,
  "trigger": { "kind": "impact", "smv_g": 3.2, "ts_ms": 1760000100600 },
  "hr": [ { "bpm": 71, "ts_ms": 1760000099123, "accuracy": "HIGH" } ],
  "wear_state": null,
  "input_cutoff_ms": 1760000101200
}
```

- `acc`: 창 안 배치들의 샘플을 시각 순으로 이어 붙인 것. `t0_ms`는 첫 샘플 환산 시각, `dt_ms`는 인접 간격(ms, 정수 반올림 — **임시 형식이라 허용**, 원문은 S3에 있다), `mg`는 n×3. 배치 경계에서는 실제 간격을 그대로 둔다(보간 없음).
- `gyro`: 창 안 배치에 자이로가 하나라도 있으면 같은 모양, 없으면 **`null`**(0으로 채우지 않는다, 1.2.6).
- `hr`: 창 안 `heart_rate_event`(bpm·measured_at→ts_ms·accuracy). 없으면 `[]`.
- `wear_state`: 배치의 `wear_state`(null 허용).

응답: `{ "decision": "ALERT|NO_ALERT|ABSTAIN_INSUFFICIENT_INPUT", "decider_id": "...", "decider_version": "...", "severity": null, "trigger_path": null }`. `decision`이 세 값 밖이면 `FALL_AI_INVALID_RESPONSE`(기록하지 않음).

## 4. 데이터 모델

`decision_record` (엔티티 `com.widyu.decision.DecisionRecord`, widyu-domain, `BaseTimeEntity`):

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `id` | BIGINT PK | | |
| `decision_id` | VARCHAR(40) | NOT NULL UK | `dec-` + UUID hex |
| `member_id` | BIGINT | NOT NULL | |
| `run_id` | VARCHAR(40) | NULL | 배치의 `run_id`(회차 밖이면 null) |
| `stream_ids_used` | TEXT | NOT NULL | 창에 쓴 `batch_id` JSON 배열 문자열 |
| `decision_at_ms` | BIGINT | NOT NULL | AI 응답을 받은 시각 |
| `decision_output` | VARCHAR(32) | NOT NULL | 세 값 |
| `decider_id`, `decider_version` | VARCHAR(64) | NOT NULL | AI 응답. ABSTAIN(입력 부족)일 때는 `server`·설정값 `sensor.fall-ai.server-decider-version` |
| `input_cutoff_ms` | BIGINT | NOT NULL | 창 조립 시각(이 시각까지 저장된 배치만 씀) |
| `feature_support_end_ms` | BIGINT | NOT NULL | 창의 끝 = 충격 배치의 `measured_at_end_ms` |
| `model_available_at_server_max_ms` | BIGINT | NOT NULL | 쓴 배치들의 `model_available_at_server_ms` 최대 |
| `window_start_ms`, `window_end_ms` | BIGINT | NOT NULL | 창 |
| `alert_id`, `alert_at_ms`, `severity`, `trigger_path` | VARCHAR(40)/BIGINT/VARCHAR(20)/VARCHAR(40) | NULL | 1.4.3 선택 |
| `alert_delivered` | BOOLEAN | NOT NULL | 항상 `false`(ADR-0033 결정 4) |
| `trigger_batch_id` | VARCHAR(26) | NOT NULL | 충격 배치 |

인덱스 `(run_id, decision_at_ms)`, `(member_id, decision_at_ms)`, `(trigger_batch_id)`.

설정(`application-sensor.yml` `sensor.fall-ai.*`): `enabled`(false), `path`(`/api/fall`), `window-before-sec`(2), `server-decider-id`(`widyu-server`), `server-decider-version`(`abstain-v1`). AI URL·타임아웃은 기존 `ai.server.*`·`aiRestTemplate` 재사용.

## 5. 처리 흐름

### 5.1 훅 (`SensorBatchService.ingestImu`)
인덱스 INSERT 성공 뒤(`STORED` 반환 직전), `request.trigger() != null`이면 `fallAssessmentService.assessAfterImpact(savedBatch)`를 `try/catch(Exception)`로 감싸 호출. 예외는 WARN(memberId·batchId·예외 클래스명)만, 응답은 `STORED`. `DUPLICATE`·거부 경로에서는 호출하지 않는다.

### 5.2 `FallAssessmentService.assessAfterImpact(SensorBatch trigger)` — 트랜잭션 없음
1. `sensor.fall-ai.enabled == false` → 즉시 반환(기록 없음).
2. `input_cutoff_ms = now`. 창: `window_start = trigger.triggerTsMs − before_sec×1000`, `window_end = trigger.measuredAtEndMs`.
3. 창 안 배치 조회: `sensor_batch` where `member_id`, `stream in (imu_watch, imu_phone)`, `measured_at_end_ms ≥ window_start`, `measured_at_start_ms ≤ window_end`, `persisted_at_ms ≤ input_cutoff_ms`, 정렬 `measured_at_start_ms`. 충격 배치 자신이 포함된다.
4. `stream_ids_used` = 그 배치들의 `batch_id`. `model_available_at_server_max_ms` = 그 배치들의 `model_available_at_server_ms` 최대. `feature_support_end_ms = window_end`.
5. 가속도가 있는 배치가 하나도 없으면(`acc_n` 전부 null) → `DecisionRecord` `ABSTAIN_INSUFFICIENT_INPUT`(decider = 서버 설정값, `decision_at_ms = now`) 저장, 반환.
6. 창 조립: 각 배치 원문을 S3에서 읽어(`s3Service.downloadBytes`) 파싱, `acc`(있으면)·`gyro`(있으면)의 샘플을 LLD-0041 4.3 환산식으로 epoch ms로 옮겨 이어 붙인다. `hr` = `heart_rate_event` where member, `measured_at` in 창(Asia/Seoul 환산 역변환).
7. `FallAssessmentClient.assess(request)` → `RestClientException`이면 WARN(예외 클래스명)·메트릭 후 **기록 없이 반환**. 응답 `decision`이 세 값 밖이면 같은 처리.
8. `DecisionRecord` 저장(`@Transactional`은 저장 메서드만): 4절 필드 전부. `decision_at_ms = now`(응답 수신 시각). **인과성 검사**: `feature_support_end_ms ≤ input_cutoff_ms ≤ decision_at_ms`, `model_available_at_server_max_ms ≤ decision_at_ms`가 아니면 `IllegalStateException`(설계 오류이므로 기록하지 않고 ERROR). 로그는 memberId·decisionId·output·batch 수만.

### 5.3 내보내기 (`RunExportAssembler`)
`decision_record` where `run_id` 정렬 `decision_at_ms` → zip 최상위 `decisions.jsonl`, 한 줄에 형식서 §8 필드: 필수 10개 + `window_start_ms`·`window_end_ms`·`alert_id`·`alert_at_ms`·`severity`·`trigger_path`·`alert_delivered`. `stream_ids_used`는 JSON 배열로 파싱해 싣는다. 판정이 0건이면 파일을 만들지 않는다(K1이 NM). `manifest.files[]`에는 넣지 않는다(스트림 파일이 아니다).

## 6. 예외 / 에러 처리

| 코드 | 조건 | 처리 |
| --- | --- | --- |
| `FALL_AI_INVALID_RESPONSE` (`SENSOR_5001`, 502) | AI 응답이 세 값 밖 | 기록 없음, WARN. 배치 응답에는 영향 없음 |

그 밖의 예외는 훅에서 삼킨다. 어떤 경우에도 배치 저장 결과를 바꾸지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] `enabled=true`이고 창에 가속도 배치 2개가 있을 때 충격 배치가 저장되면 AI가 1회 호출되고 `decision_record` 1건이 필수 10필드와 함께 저장된다. `stream_ids_used`에 두 배치의 `batch_id`가 있고 `model_available_at_server_max_ms`는 둘 중 큰 값이다.
- [ ] 창에 가속도 자료가 없으면(충격 배치가 `acc: null` 보강 배치뿐) AI를 호출하지 않고 `ABSTAIN_INSUFFICIENT_INPUT`이 기록된다.
- [ ] 창 안 배치에 자이로가 없으면 AI 요청의 `gyro`가 `null`이다(0 배열 아님).
- [ ] AI가 `RestClientException`을 던지면 기록이 없고 배치 응답은 `STORED`다.
- [ ] `enabled=false`면 AI 호출·기록이 없다.
- [ ] 기록의 `feature_support_end_ms ≤ input_cutoff_ms ≤ decision_at_ms`, `model_available_at_server_max_ms ≤ decision_at_ms`.
- [ ] `alert_delivered`가 `false`다.
- [ ] 내보내기 통합 테스트에 판정 2건을 넣으면 zip에 `decisions.jsonl` 2줄이 있고 각 줄에 필수 10필드가 있으며, 검사기 K1·K2·K4·K5가 PASS다(수동 실행, PR 본문에 결과).
- [ ] `./gradlew compileJava`(QDecisionRecord), `bash scripts/harness/run-module-tests.sh` 통과.

## 8. 영향 범위 / 마이그레이션

신규 테이블 → `scripts/mysql/create_decision_record.sql`. `SensorBatchService.ingestImu`에 훅 한 줄. `RunExportAssembler`에 파일 하나 추가. `application-sensor.yml` `sensor.fall-ai.*`(기본 꺼짐이라 운영 동작 변화 없음). ERD, `backend/CLAUDE.md`에 `decision` 절 신설.

## 9. 미결정 사항 (Open Questions)

- 없음. 입력 형식 교체·알림 경로·심박 기록은 ADR-0033 후속 절.

## 10. 참고

- 정책서 v1.1 B 1.4.1~1.4.5, 작업지시서 v2 B10, 형식서 §8, 검사기 K1~K5
- LLD-0041 v2(4.3 환산), LLD-0047(hr 샘플), LLD-0050(내보내기), `HeartRateAnomalyDetector`(AI 호출 패턴)
