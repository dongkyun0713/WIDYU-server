# LLD-0053: 심박 위급 판정 기록과 알림 도달 사실

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #661 |
| 관련 ADR | [ADR-0035](../adr/ADR-0035-alert-decision-and-incident.md), ADR-0033, ADR-0031 |
| 작성자 | Claude |
| 작성일 | 2026-09-21 |
| 선행 | PR #657(B10, `decision_record`). base upstream/develop f454832 |

## 1. 목적 / 배경

ADR-0035 결정 1~3. 실증 회차에 귀속된 심박 배치의 위급 판정을 `decision_record`에 남기고, 판정 사유·심박 값을 그 행에만 두며, 보호자 FCM 전송 성공을 그 판정의 알림 도달 사실로 채운다.

정책서 B 1.4.2의 판정 기록은 실행 번호와 쓴 자료 목록을 필수로 요구한다. 따라서 `run_id`와 `batch_id`를 가진 실증 배치 경로만 이 기록의 대상이다. 기존 제품용 REST·WebSocket 단건 경로는 회차와 배치가 없어 이 계약에 맞는 판정 기록을 만들 수 없으며, 이 PR에서 임의 식별자를 만들어 연구 자료로 섞지 않는다.

## 2. 범위

### In scope
- widyu-api / widyu-domain
- `decision_record` 열 4개 추가, `fcm_outbox`·`fcm_notification`에 판정 연결키 추가
- `HeartRateBatchService`에서 판정 기록, `HeartRateAnomalyDetector`가 `reason`을 받음(로그 금지 유지)
- `HeartRateEmergencyEvent`·`FcmSendDto`·outbox에 `decision_id` 전달, `FcmOutboxTransactions.finish` 성공 시 판정 갱신
- 내보내기 `decisions.jsonl`에 심박 행·근거 필드
- 설정 `sensor.heart-ai.*`

### Out of scope
- 인시던트(LLD-0054), 낙상 경로 변경, 정상 판정 행, 앱 수신 확인
- 회차·배치 식별자가 없는 기존 제품용 REST·WebSocket 단건 심박 경로의 판정 기록. 이 경로는 기존 `heart_rate_emergency`와 보호자 알림 동작을 유지한다

## 3. 인터페이스 / API

외부 API 변경 없음. AI 응답 DTO에 `reason`(String, null 허용)을 다시 매핑한다. `layer`·`baseline_source`는 계속 받지 않는다.

내보내기 `decisions.jsonl` 행(형식서 §8 필드 그대로) + 심박 행에만 `evidence` 객체:

```json
{"decision_id":"dec-…","run_id":"run-…","stream_ids_used":["01J…"],"decision_at_ms":…,
 "decision_output":"ALERT","decider_id":"widyu-ai-hr","decider_version":"ver7",
 "input_cutoff_ms":…,"feature_support_end_ms":…,"model_available_at_server_max_ms":…,
 "window_start_ms":…,"window_end_ms":…,"alert_id":"fcm-123","alert_at_ms":…,"severity":"EMERGENCY",
 "trigger_path":null,"alert_delivered":true,
 "evidence":{"hr_bpm":142,"hr_measured_at_ms":…,"hr_accuracy":"HIGH","reason":"…"}}
```

`evidence`는 심박 값이 있을 때만 붙인다(낙상 행에는 없음). 검사기 K1~K5가 추가 키를 거부하면 `evidence`를 빼고 PR에 적는다.

## 4. 데이터 모델

`decision_record` 추가 열(모두 NULL 허용, 낙상 행은 비움):

| 컬럼 | 타입 | 비고 |
| --- | --- | --- |
| `hr_bpm` | INT | 판정 대상 샘플의 bpm |
| `hr_measured_at_ms` | BIGINT | 샘플 `ts_ms` |
| `hr_accuracy` | VARCHAR(12) | 샘플 accuracy |
| `reason` | TEXT | AI 응답 `reason` 원문. **로그·응답 DTO에 넣지 않는다** |

`fcm_outbox` 추가 열: `decision_id VARCHAR(40) NULL`, 인덱스 없음(완료 지점에서 행 자신이 갖고 있는 값만 쓴다).

`fcm_notification` 추가 열: `decision_id VARCHAR(40) NULL`, 인덱스 `idx_fcm_notification_decision`. 전송 성공 이력도 판정 연결키를 가져야 연구 철회 때 같은 회원의 다른 알림을 건드리지 않고 대상만 찾을 수 있다.

`DecisionRecord`에 갱신 메서드 `markDelivered(String alertId, long alertAtMs)`: `alert_delivered=true`, `alert_id`·`alert_at_ms`는 비어 있을 때만 채운다(첫 성공만 기록, 보호자가 여럿이라도 한 번).

설정 `sensor.heart-ai`: `decider-id`(`${SENSOR_HEART_AI_DECIDER_ID:widyu-ai-hr}`), `decider-version`(`${SENSOR_HEART_AI_DECIDER_VERSION:ver7}`). `SensorProperties`에 `HeartAi` 레코드 추가.

## 5. 처리 흐름

### 5.1 `HeartRateBatchService.storeAndAssess(member, batchId, runId, samples, serverReceivedAtMs)`
`runId`를 인자로 받는다(호출자 `SensorBatchService.ingestHeartRate`가 배치의 run 귀속값을 넘긴다. 없으면 null).

샘플 루프(기존 순서 유지: 정렬 → 중복 skip → AI 대상 판정 → detect → 저장):
1. AI 대상이고 `detect` 성공 → `aiTargetCount++`. 결과가 `emergency`면 `ALERT` 행을 **조립만 해서** `saveBatchSample(..., DecisionRecord decision)`에 넘기고, `HeartRatePersistenceService`의 `@Transactional` 안에서 `decisionRecordRepository.save` → 심박 이벤트 저장 → 위급 저장 → `HeartRateEmergencyEvent(memberId, decisionId)` 발행 순으로 **한 트랜잭션**에 묶는다. 아니면 메트릭 `heart.decision{output=NO_ALERT}` 증가.
   - **판정 행을 먼저 따로 커밋하지 않는다.** REQUIRES_NEW로 앞서 커밋하면 이어지는 심박 저장이 실패했을 때 심박 이벤트도 위급 알림도 없이 판정만 남아, 「알림이 갔다고 적혔지만 아무것도 가지 않은」 자료가 된다.
   - 행 **조립**이 실패하면(설정 누락 등) WARN만 남기고 `decision = null`로 심박만 저장한다.
2. `detect`가 실패(aiAvailable=false)하면 기록 없음(기존대로 UNKNOWN 저장).
3. 루프 뒤 **중복 skip을 뺀 저장 샘플이 1개 이상**이고 `aiTargetCount == 0`이고 AI가 한 번도 호출되지 않았다면 → `ABSTAIN_INSUFFICIENT_INPUT` 행 1개(배치 단위). AI 호출 실패로 대상이 0이 된 경우는 제외한다. **중복 skip된 샘플은 세지 않는다** — 이미 판정한 시각이 다시 온 것이라 입력 부족이 아니다. 그래서 전부 중복인 재전송 배치는 아무 행도 남기지 않는다(남기면 재전송 횟수만큼 없던 「판정 불가」가 쌓여 사후 분석이 오염된다).

`ALERT` 행 값:

| 필드 | 값 |
| --- | --- |
| `member_id`, `run_id` | 인자 |
| `stream_ids_used` | `["<batchId>"]` |
| `decision_at_ms` | AI 응답 수신 직후 `now` |
| `decision_output` | `ALERT` |
| `decider_id`, `decider_version` | 설정 `sensor.heart-ai.*` |
| `input_cutoff_ms`, `model_available_at_server_max_ms` | `serverReceivedAtMs` |
| `feature_support_end_ms`, `window_start_ms`, `window_end_ms` | 샘플 `ts_ms` (AI 내부 이력은 서버가 모른다, ADR-0035) |
| `severity` | AI `level` 문자열 |
| `trigger_path` | null |
| `trigger_batch_id` | batchId |
| `hr_*`, `reason` | 샘플 값, AI `reason` |
| `alert_delivered` | false (초기) |

`ABSTAIN` 행(짝이 될 심박 이벤트도 알림도 없어 묶을 트랜잭션이 없다. `DecisionRecordPersistenceService.save` REQUIRES_NEW 유지): 위와 같되 `decider_id/version`은 `sensor.fall-ai.server-decider-*`(서버 자체 판정, 기존 값 재사용), `window_start/end` = 배치 첫/마지막 샘플 `ts_ms`, `feature_support_end` = 마지막, `hr_*`·`reason`·`severity` null.

인과성: `feature_support_end_ms > input_cutoff_ms`여도 **기록한다**(기기 시계 앞섬). 예외를 던지지 않는다. 검사기가 신고한다.

### 5.2 알림 도달
1. `HeartRatePersistenceService.saveBatchSample`이 판정 행을 같은 트랜잭션에서 저장하고 그 `decisionId`로 `HeartRateEmergencyEvent(memberId, decisionId)`를 발행한다. 회차·배치 식별자가 없는 제품용 단건 경로는 이 LLD 범위 밖이므로 기존처럼 `decisionId = null`이다.
2. `HeartRateEmergencyNotificationService`가 `FcmSendDto`에 `decisionId`를 싣고, `FcmOutboxService.enqueue`가 outbox 행 `decision_id`에 쓴다.
3. `FcmOutboxTransactions.finish` 성공 분기는 `fcm_notification.decision_id`에도 같은 값을 복사한다. 전송 이력에서 연구 판정 알림만 선택할 수 있게 하기 위해서다.
4. 같은 성공 분기에서 `row.getDecisionId() != null`이면 `decisionRecordRepository.markDeliveredIfFirst(decisionId, "fcm-" + row.getId(), nowMs)` 한 번. `set alert_delivered = true, alert_id = :alertId, alert_at_ms = :alertAtMs where decision_id = :decisionId and alert_id is null`인 **원자적 벌크 UPDATE**다. 반환값은 보지 않는다(0은 오류가 아니라 다른 보호자의 전송이 먼저 적혔다는 뜻이다). 행이 없으면 0건이라 무시된다. 실패 분기는 손대지 않는다.
   - **읽고 나서 쓰지 않는다.** 보호자가 여럿이면 완료 트랜잭션이 동시에 돌아, 엔티티를 읽어 `markDelivered`하면 둘 다 빈 `alert_id`를 보고 나중 것이 앞선 시각을 덮어쓴다. 조건을 UPDATE 문에 넣어 DB가 한 번만 성공시킨다.
   - `DecisionRecord.markDelivered`는 도메인 규칙(첫 성공만 남는다)의 자리로 남기고 outbox 경로에서는 쓰지 않는다.

### 5.3 내보내기
`RunExportAssembler.toDecisionRecord`에 `hr_bpm != null`이면 `evidence{}` 추가. 그 외 변경 없음(심박 행은 `run_id`로 이미 조회된다).

### 5.4 로그
어떤 레벨에도 bpm·`reason`·`severity`·AI 응답 본문을 찍지 않는다. 판정 기록 저장 로그는 `memberId`·`decisionId`·`output`·`batchId`만. `HeartRateAnomalyDetectorTest`의 로그 검사(#639)를 `reason` 매핑 뒤에도 통과시킨다.

## 6. 예외 / 에러 처리

판정 행 **조립** 실패는 심박 저장·알림을 막지 않는다. `try/catch(Exception)`로 감싸 WARN(예외 클래스명·batchId)만 남기고 `decision = null`로 진행한다. `ABSTAIN` 행의 **저장** 실패도 같은 방식으로 삼킨다.

`ALERT` 행의 **저장**은 심박 이벤트와 한 트랜잭션이라 실패하면 그 샘플 전체가 롤백된다. 이는 의도한 것이다. 판정만 남고 알림이 가지 않는 자료보다 그 샘플이 통째로 없는 편이 낫고, 배치 저장 실패는 앱의 재전송으로 회복된다. 새 ErrorCode 없음.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 배치에 위급 샘플 1개가 있으면 `ALERT` 행 1개가 저장되고 필수 10필드·`hr_bpm`·`hr_measured_at_ms`·`hr_accuracy`·`reason`·`severity`가 채워진다. `stream_ids_used`는 `[batchId]`, `run_id`는 인자값.
- [ ] 같은 배치의 정상 샘플은 행을 만들지 않고 메트릭만 는다.
- [ ] 배치의 모든 샘플이 `UNRELIABLE`이면 `ABSTAIN_INSUFFICIENT_INPUT` 행 1개, AI 호출 0회.
- [ ] 배치의 모든 샘플이 중복으로 skip되면 판정 행이 없다(재전송은 입력 부족이 아니다).
- [ ] AI 호출이 실패하면 판정 행이 없고 심박은 `UNKNOWN`으로 저장된다(기존 동작).
- [ ] `HeartRateEmergencyEvent`에 `decisionId`가 실리고 outbox 행 `decision_id`에 저장된다.
- [ ] 전송 성공으로 만들어진 `fcm_notification` 행에도 같은 `decision_id`가 저장되고 일반 알림은 null이다.
- [ ] outbox 전송 성공 시 해당 판정의 `alert_delivered=true`, `alert_id="fcm-<id>"`, `alert_at_ms`가 채워진다. 두 번째 성공은 값을 덮지 않는다. 전송 실패 시 `false` 유지.
- [ ] `decisionId`가 null인 outbox 행(다른 알림)은 판정 갱신을 시도하지 않는다.
- [ ] 판정 행 조립이 실패해도(설정 누락) 심박 저장·알림은 그대로 되고 `decisionId`는 null이다.
- [ ] `ALERT` 행 저장과 심박 이벤트 저장·알림 발행이 같은 트랜잭션 안에서 일어난다.
- [ ] 같은 판정에 전송 성공이 두 번 들어와도 `alert_id`·`alert_at_ms`는 첫 성공 값이다(원자적 조건부 UPDATE).
- [ ] 로그 캡처 테스트: bpm·reason 문자열이 어떤 로그에도 없다.
- [ ] 내보내기 통합 테스트에 심박 `ALERT` 행을 넣으면 `decisions.jsonl`에 `evidence`가 있고 검사기 K1·K2·K4·K5 PASS(수동, PR 본문).
- [ ] 회차·배치 식별자가 없는 제품용 단건 심박은 연구 판정 기록을 만들지 않고 기존 위급 저장·보호자 알림을 유지한다.
- [ ] `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh`, `verify.sh --base` 통과.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/alter_decision_record_for_hr.sql`(열 4개), `scripts/mysql/alter_fcm_outbox_decision_id.sql`(`fcm_outbox`·`fcm_notification` 연결키). ERD `decision_record`·`fcm_outbox`·`fcm_notification` 갱신. `backend/CLAUDE.md` heart·decision 절. `application-sensor.yml` `heart-ai` 블록(`export:`와 같은 들여쓰기, #660 참고).

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고
- `HeartRateBatchService`, `HeartRateAnomalyDetector`(#639 로그 규칙), `HeartRatePersistenceService`, `HeartRateEmergencyNotificationService`, `FcmOutboxTransactions.finish`, `FallAssessmentService.save`(판정 행 조립 패턴), LLD-0051 4절
