# LLD-0070: 심박 위급의 본인확인 선행과 사건 모델 확장

> Low-Level Design. 이 문서는 #706 구현과 PR 검수의 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #706 |
| 관련 ADR | ADR-0038 결정 1~5·7·10, ADR-0037, [ADR-0035](../adr/ADR-0035-alert-decision-and-incident.md) 결정 3·5·7 개정 |
| 선행 | [LLD-0054](LLD-0054-incident.md), W5(feature/700)의 `NotificationType`·센터 행·`PushSettingGroup` 판정 |
| 작성자 | Codex / feature/706 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 심박 위급 배치에서는 동기 리스너가 보호자에게 먼저 알리고, 판정 기록이 있을 때만 뒤이어 시니어 사건을 연다. 단건 심박에는 사건이 없다. 배치·단건 모두 사건을 먼저 열어 시니어에게 본인확인을 요청하고, 보호자 최초 알림을 사건의 단일 발송 경로로 모은다. FE 준비 전에는 기본 OFF 플래그로 보호자 즉시 알림을 유지한다.

## 2. 범위

### In scope

- `widyu-domain`: `Incident.decisionId` NULL 허용·기존 UK 유지. `deviceRespondedAtMs`, `initialAlertSentAtMs`, `lastDecisionId`, `detectionCount`, `situationEndedAtMs`, `guardianResponseType`, `guardianResponseAtMs`, `guardianResponseBy`, `policyRevision` 추가. `IncidentKind` 값은 그대로 둔다. enum 열에는 `@Enumerated(STRING)`와 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 적용한다.
- `widyu-api`: `IncidentRepository.findDueIds`, `escalateIfDue` 조건부 UPDATE, `respond` 단말 시각 인자. 새 빈 `IncidentEscalation`(`REQUIRES_NEW`)에 보호자 S04 INITIAL_ALERT 조립·enqueue를 단일화한다. `IncidentTimeoutScheduler`의 `@Transactional` 제거와 `findDueIds → 사건별 escalateIfDue` 호출.
- `IncidentService.openForAlert`의 판정 없는 오버로드. `memberRepository.findByIdForUpdate`로 회원 행을 잠근 뒤 열린 심박 사건을 조회한다. `FallAssessmentService`가 쓰는 `openForAlert(decision, kind)` 시그니처는 유지한다.
- `HeartRateEmergencyNotificationService`를 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`로 변경. 두 심박 경로의 사건 열기·본인확인 푸시(`NotificationType.SAFETY_SELF_CHECK`, S01)를 맡긴다. `HeartRateBatchService.openIncident`는 삭제한다.
- `IncidentRespondRequest.deviceRespondedAtMs`(nullable), `application-sensor.yml`의 `self-check-sec=60`, `self-check-first=false`, `situation-window-min=5`, `scripts/mysql/alter_incident_self_check_first.sql`, ERD 갱신. 기존 5초 `timeout-poll-ms`는 유지한다.

### Out of scope

- 안심구역 사건·`SAFE_ZONE_EXIT`(W11a-2), OK 정보성 알림·배치와 단건을 아우르는 같은 사건 묶기 완성·보호자 반응 API(W11b), 자동전화·최종 단계 푸시(W12).
- FE 화면, 안전 예외 판정, 응답 원 시도 별도 이력, 형식서·검사기 개정. 후속 흐름용 사건 컬럼만 이번에 마련한다.

## 3. 인터페이스 / API

기존 `POST /api/v1/incidents/{incidentId}/response`에 선택 필드 `deviceRespondedAtMs`를 추가한다. FE의 `취소`는 서버 `OK`로 보내고 워치 호환을 위해 `HELP`도 받는다. `via`는 기존 `WATCH|PHONE`이다.

```json
{"response":"OK","via":"PHONE","deviceRespondedAtMs":1790900000000}
```

기존 `ApiResponse<IncidentResponse>` 래퍼와 오류 계약을 유지한다. `respondedAtMs`는 서버 수신 시각이고 `deviceRespondedAtMs`는 원 단말 클릭 시각이다. FE의 남은 시간 계산에는 기존 `openedAtMs`·`respondByMs`를 쓴다. 판정 없는 단건 사건의 `decisionId`는 null이다. 심박값·사유·좌표는 응답과 푸시에 싣지 않는다. 새 HTTP 경로는 없다.

## 4. 데이터 모델

`incident` 변경 열만 적는다. 시각은 epoch ms이며 `incident_ref`는 기존 `inc-` + UUID hex 식별자다.

| 열 | 타입·제약 | 의미 |
| --- | --- | --- |
| `decision_id` | `VARCHAR(40) NULL`, UK 유지 | 배치 판정 연결; 단건은 NULL. MySQL UK는 NULL 중복 허용 |
| `device_responded_at_ms` | `BIGINT NULL` | 원 단말 클릭 시각; 마감 판정에 미사용 |
| `initial_alert_sent_at_ms` | `BIGINT NULL` | 보호자 INITIAL_ALERT enqueue 시각 및 발송 게이트 |
| `last_decision_id` | `VARCHAR(40) NULL` | 마지막 연결 판정 ID |
| `detection_count` | `INT NOT NULL DEFAULT 1` | 사건에 귀속된 감지 수; W11b에서 누적 |
| `situation_ended_at_ms` | `BIGINT NULL` | 상황 종료 시각; 사용 로직은 W11b |
| `guardian_response_type` | `VARCHAR(20) NULL` | `MESSAGE_SENT|CALL_INITIATED`; 기록 API는 W11b |
| `guardian_response_at_ms` | `BIGINT NULL` | 보호자 실제 행동 시각 |
| `guardian_response_by` | `BIGINT NULL` | 행동한 보호자 회원 ID |
| `policy_revision` | `BIGINT NULL` | 안전 정책 revision 스냅샷 |

상태 enum 이름은 유지한다. `CHECKING=SELF_CHECK_PENDING`, `ESCALATED=GUARDIAN_PENDING`, `OK_CLOSED=CLOSED_OK`; `response=OK`는 FE의 `CANCELED`이다. `TIMEOUT`은 단말 응답값이 아니며 상태·시각에서 판단한다. FE 안전 데이터와 `safetyEventId=incident_ref`, `confirmationDeadlineAt=respond_by_ms`, `guardianAlertedAt=initial_alert_sent_at_ms`로 대응한다. `okNoticeSentAt`과 자동전화 필드는 후속 범위다. `idx_incident_alert_pending(initial_alert_sent_at_ms, respond_by_ms)`를 추가하고 기존 인덱스는 유지한다. 새 enum 저장 열은 MySQL native ENUM이 아닌 VARCHAR다.

## 5. 처리 흐름

### 5.1 사건 열기와 플래그

1. 기존 배치 및 REST·WebSocket 단건 위급 저장은 `HeartRateEmergencyEvent`를 발행한다. 저장 커밋 뒤 `HeartRateEmergencyNotificationService`가 새 트랜잭션에서 사건을 연다. 배치의 `decisionId`가 있으면 판정을 조회해 기존 `openForAlert(decision, HR_ANOMALY)`을 호출한다. 단건의 `decisionId=null`이면 판정 없는 오버로드를 호출한다. 회원 행 잠금 뒤 열린 심박 사건을 조회해 단건 중복을 막는다. `HeartRateBatchService.openIncident` 호출은 제거한다.
2. 새 사건의 `opened_at_ms`는 서버 생성 시각, `respond_by_ms=opened_at_ms+60000`이다. 배치 판정 재시도는 `decision_id` UK로 멱등 처리한다. 단건은 잠근 회원에 대해 `kind=HR_ANOMALY`, `state IN (OPEN,CHECKING,ESCALATED)`, `situation_ended_at_ms IS NULL`, `opened_at_ms >= now - situation-window-min`인 사건만 재사용한다. 더 오래된 열린 사건은 그대로 두고 새 사건을 열어 본인확인과 보호자 알림을 다시 만든다. 이 생성 시각 기준 5분 창은 W11b가 마지막 감지 시각 기준으로 정교화할 때까지의 임시 규칙이며, 상황 종료와 후속 판정 부착은 W11b에서 다룬다.
3. 새 사건에만 시니어 본인확인 `SAFETY_SELF_CHECK`를 enqueue하고 `CHECKING`으로 둔다. 문구 정본 S01은 제목 `평소와 다른 심박이 감지됐어요.`, 본문 `괜찮으시면 취소를 눌러주세요. 시간 안에 누르지 않으면 보호자에게 알려드려요.`이다. `eventId=incident_ref`, `entityId=incident_ref`를 싣는 `PUSH_ONLY`이며 센터 행은 없다.
4. `self-check-first=false`이면 같은 사건 트랜잭션에서 `IncidentEscalation.sendImmediately`를 호출해 S04 INITIAL_ALERT를 즉시 enqueue하고 `initial_alert_sent_at_ms`를 기록한다. 60초 뒤 무응답 상태 전환은 이 경우에도 스케줄러가 계속 담당한다. `true`이면 이 시점의 INITIAL_ALERT는 0건이고 스케줄러만 발송한다. 두 경로는 `IncidentEscalation`의 동일한 package-private `enqueueInitialAlert` 메서드만 사용한다. `HeartRateEmergencyNotificationService`에는 보호자 직접 푸시·S04 조립 코드를 두지 않는다.

동기 `@EventListener` 안의 `openForAlert` 실패는 호출 트랜잭션을 rollback-only로 표시해 심박 저장 커밋을 깨뜨릴 수 있다. `AFTER_COMMIT`만 쓰고 새 트랜잭션을 열지 않으면 outbox INSERT가 flush되지 않을 수 있다. 따라서 `AFTER_COMMIT`과 `REQUIRES_NEW`를 함께 적용한다. 사건 열기 실패는 선행 심박 저장을 되돌리지 않는다.

### 5.2 본인 응답과 마감

`IncidentRepository.respond` 조건부 UPDATE에 `deviceRespondedAtMs`를 추가한다. `respondedAtMs`는 서버 수신 시각이며 `respondedAtMs >= respondByMs`이면 정확히 마감의 OK도 `ESCALATED`다. 이미 `ESCALATED`이면 늦은 OK의 응답값·시각·경로만 저장하고 상태를 유지한다. 기한 안 OK만 `OK_CLOSED`다. HELP는 `ESCALATED`로만 바꾸고 즉시 발송하지 않는다. 기존 `response IS NULL` 조건으로 watch/phone 첫 응답만 수락하며 다음 응답은 409다. 단말 시각으로 소급 취소하지 않는다. OK 정보성 알림은 W11b에서 다룬다.

### 5.3 사건별 INITIAL_ALERT 게이트

스케줄러는 5초 폴링마다 `findDueIds(nowMs, afterId, limit)`로 `(state IN (OPEN,CHECKING) AND respond_by_ms < now) OR (state=ESCALATED AND initial_alert_sent_at_ms IS NULL)`인 심박 사건 ID를 조회한다. `limit`은 설정값 없이 상수 100으로 둔다. `incident_id > afterId`와 `ORDER BY incident_id` 키셋으로 한 폴링에서 최대 10페이지를 순회하므로 앞 페이지의 발송 실패가 뒤 사건을 가로막지 않는다. `OK_CLOSED`·`RESOLVED`는 제외한다. 스케줄러 메서드에는 `@Transactional`을 붙이지 않는다. 후보 조회는 상태 전환과 발송 결정의 입력일 뿐이다.

이 PR의 `findDueIds`·`escalateIfDue`는 `HR_ANOMALY`만 대상으로 한다. `FallAssessmentService.openForAlert(decision, kind)`로 생긴 `FALL_SUSPECTED` 사건은 기존 일반 본인확인 문구와 별도 `REQUIRES_NEW` 상태 갱신을 유지하며 심박 S01·S04를 보내지 않는다. 낙상 알림 정책 변경은 이번 범위 밖이다.

각 ID를 별도 빈 `IncidentEscalation.escalateIfDue(id, now)`에 전달한다. 같은 `@Transactional(REQUIRES_NEW)`에서 먼저 무응답 전환 UPDATE를 실행하고, 알림 여부와 관계없이 기한 지난 `OPEN`·`CHECKING`을 `ESCALATED`로 바꾼다. 이어서 별도 게이트 UPDATE가 1건을 바꿀 때에만 **같은 트랜잭션**에서 package-private `enqueueInitialAlert`를 호출한다. 플래그 OFF에서 이미 발송한 사건은 첫 UPDATE만 성공하고 게이트 UPDATE 0건으로 끝나는 정상 경로다. 게이트 반환값이 0이면 추가 발송·로그 없이 끝나며, enqueue 실패 시 두 UPDATE도 롤백돼 다음 폴링에서 재시도한다.

`enqueueInitialAlert`는 시니어 가족의 활성 보호자 전원에게 `FcmOutboxService.enqueue(guardianId, FcmSendDto)`를 호출하는 유일한 S04 생성 경로다. 제목은 `{시니어 이름} 님의 심박 상태를 확인해주세요.`, 본문은 `평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요.`다. `NotificationType.HEART_RATE_EMERGENCY`, `eventId=incident_ref`, `decisionId=incident.decisionId`(단건은 null), `relatedMemberId=seniorId`, `seniorId`를 함께 싣는다. outbox의 가족 검사를 통과한 수신자는 W5의 `PushSettingGroup.SAFETY` 정책으로 센터 행을 얻고, 설정 OFF이면 센터 행은 남되 claim에서 푸시가 취소된다.

가족 연결이 없거나 활성 보호자가 0명이면 S04 enqueue 없이 incident_ref만 WARN으로 남기고, OFF 경로는 `initial_alert_sent_at_ms`를 기록하며 ON 경로는 조건부 UPDATE에서 이미 기록한 게이트를 유지해 사건·S01을 보존한다.

```sql
UPDATE incident
   SET state = 'ESCALATED'
 WHERE incident_id = :id
   AND state IN ('OPEN', 'CHECKING')
   AND respond_by_ms < :now;

UPDATE incident
   SET initial_alert_sent_at_ms = :now
 WHERE incident_id = :id
   AND initial_alert_sent_at_ms IS NULL
   AND state IN ('OPEN', 'CHECKING', 'ESCALATED')
   AND (respond_by_ms < :now OR state = 'ESCALATED');
```

게이트는 **`initial_alert_sent_at_ms IS NULL`** 이다. 마감과 다음 폴링 사이에 늦은 OK가 `ESCALATED`를 적어도 알림이 빠지지 않는다. `state IN (…)`은 조회 뒤 정상 취소·종결된 사건을 제외한다. HELP로 `ESCALATED`가 되면 마감 전에도 다음 폴링에서 보낸다. UPDATE 성공과 enqueue는 사건당 1회지만 실제 outbox 전송은 ADR-0028의 at-least-once 범위다. 배치 `decisionId`를 전달하고 `alert_delivered`는 INITIAL_ALERT 첫 FCM 성공에서만 참이 된다.

기존 `idx_incident_alert_pending(initial_alert_sent_at_ms, respond_by_ms)`와 LLD-0054의 `(state, respond_by_ms)` 계열 인덱스를 유지한다. 이 수정에는 새 열·테이블·인덱스가 없다.

## 6. 예외 / 에러 처리

| 조건 | 처리 |
| --- | --- |
| 사건이 없거나 본인 사건이 아님 | 기존 `INCIDENT_NOT_FOUND`·404 |
| 이미 응답했거나 종결됨 | 기존 `INCIDENT_ALREADY_ANSWERED`·409, 최초 응답 유지 |
| 사건 열기·INITIAL_ALERT enqueue 실패 | 해당 새 트랜잭션 롤백. 심박 저장 유지. 미발송 기존 사건은 다음 폴링 재시도. 신규 사건 자체가 생성되지 못한 경우 자동 재시도 경로는 없으므로 식별자 없는 오류 건수를 운영 경보로 남긴다 |
| 가족 미연결·활성 보호자 0명 | S04 enqueue 0건, incident_ref만 WARN 1줄. 사건·S01을 유지하고 `initial_alert_sent_at_ms`로 게이트를 닫는다 |
| 시니어 FCM 전달 실패 | 전달 실패와 무응답을 구별한다. 본인확인 푸시 5분 TTL 소진에도 INITIAL_ALERT는 60초 게이트대로 보낸다(T1-③ 가정) |

## 7. 인수조건 (Acceptance Criteria)

- [ ] 플래그 OFF의 배치 위급 1건은 사건 1, 시니어 S01 푸시 1, 안전 수신 보호자별 S04 INITIAL_ALERT 즉시 1을 만들고 `initial_alert_sent_at_ms`를 기록한다. `eventId=incident_ref`, 배치 `decisionId`를 전달한다.
- [ ] 플래그 ON의 배치 위급 직후 INITIAL_ALERT는 0이고, 60초 뒤 스케줄러가 `ESCALATED`와 `initial_alert_sent_at_ms`를 기록하며 보호자별 1회 enqueue한다.
- [ ] 61초의 OK는 응답·`device_responded_at_ms`를 저장하고 `ESCALATED`를 유지하며 다음 폴링에서 INITIAL_ALERT를 1회 보낸다. 정확히 60초의 OK도 늦은 응답이다.
- [ ] HELP는 `ESCALATED`만 기록하고 다음 폴링에서 INITIAL_ALERT를 1회 보낸다. watch/phone 동시 응답은 첫 건만 반영하고 다음 응답은 409다.
- [ ] REST·WebSocket 단건 위급은 `decision_id=NULL` 사건을 연다. 동일 회원의 1초 간격 단건 3건은 사건·본인확인 푸시 각 1건이다.
- [ ] 6분 전에 열린 `ESCALATED` 사건이 있어도 새 단건 위급은 새 사건을 열고 S01과 플래그 OFF의 S04를 각 1회 enqueue한다. 오래된 사건은 그대로 남는다.
- [ ] `escalateIfDue` 반복·동시 호출에도 조건부 UPDATE 성공과 INITIAL_ALERT enqueue는 사건당 1회다. 기한 전 OK_CLOSED·RESOLVED는 발송하지 않는다.
- [ ] 사건 열기 실패가 심박 저장을 되돌리지 않는다. `@DataJpaTest` + `TransactionTemplate`의 실제 커밋으로 AFTER_COMMIT을 검증하고 새 트랜잭션의 outbox INSERT도 확인한다.
- [ ] S01·S04 문구, 시니어 가족의 활성 보호자 전원, FCM data의 `eventId`·`type`·`decisionId`(있을 때), `relatedMemberId`·`seniorId`를 검증한다. 플래그 OFF와 스케줄러가 같은 S04 조립 메서드를 쓰고, `FcmOutboxService.enqueue`의 가족 검사 및 수신자×사건 센터 행 멱등을 거친다. 설정 OFF 수신자의 센터 행은 유지되고 outbox는 claim에서 취소된다. Swagger에 선택 입력 필드와 기존 오류가 반영된다.
- [ ] 보호자가 없는 시니어의 위급은 사건·S01을 남기고 INITIAL_ALERT 0건과 `initial_alert_sent_at_ms` 기록을 검증한다.
- [ ] 앞 페이지 사건의 enqueue가 실패해도 키셋의 다음 페이지 사건을 같은 폴링에서 처리하고 실패 사건은 다음 폴링 재시도 대상으로 남긴다.
- [ ] 플래그 OFF로 이미 INITIAL_ALERT를 보낸 사건은 60초 무응답 뒤 `ESCALATED`로 바뀌고 추가 INITIAL_ALERT는 0건이다.
- [ ] 플래그 ON 사건은 무응답 전환과 INITIAL_ALERT를 각 1회 수행하고, 이미 `ESCALATED`인 HELP 미발송 사건은 상태를 유지하며 INITIAL_ALERT만 1회 보낸다.
- [ ] `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과한다. 테스트는 JUnit 5·BDDMockito `given/willReturn`, 한글 언더스코어 이름, 행위형 `@DisplayName`과 상태 검증을 따른다. H2를 MySQL ENUM 검증으로 간주하지 않는다.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/alter_incident_self_check_first.sql`은 `decision_id`를 NULL 허용으로 바꾸고 위 열·`idx_incident_alert_pending(initial_alert_sent_at_ms, respond_by_ms)`를 추가한다. 같은 인덱스를 엔티티 `@Table(indexes=…)`에도 둔다. 기존 UK와 행은 유지한다. 새 enum 저장 열은 VARCHAR이고 `FcmCategory` 값은 추가하지 않는다. 운영 DDL 적용 후 기동한다. 구현 PR에서 [ERD-0001](../erd/ERD-0001-initial-domain.md)의 Incident 열·인덱스·마이그레이션 이력도 갱신한다.

기존 사건은 옛 흐름에서 보호자 최초 알림을 이미 보냈으므로, 열 추가 직후 `initial_alert_sent_at_ms=opened_at_ms`로 백필한다. 새 앱 배포 전 같은 배포 창에서 수행하고, 앱 기동 전 `SELECT COUNT(*) FROM incident WHERE initial_alert_sent_at_ms IS NULL AND state IN ('CHECKING','ESCALATED')`가 0인지 확인한다. 배포 뒤 같은 조회의 NULL 행은 새 흐름이 만든 사건만이어야 하며, H2 테스트를 운영 MySQL 백필 검증으로 간주하지 않는다.

ADR-0037·0038은 PR #694에서 합류할 예정이며 이 브랜치에 파일을 복사하지 않는다. 10절의 참조는 합류 뒤 같은 경로를 가리킨다.

이 문서는 LLD-0054의 배치 직접 사건 열기와 「무응답은 상태만 변경」을 대체한다. ADR-0035 결정 5의 45초는 60초로, 결정 7의 보호자 선발송은 플래그 OFF 때의 이행 동작으로 한정한다. 결정 3의 `alert_delivered`는 **INITIAL_ALERT 전송 성공**을 뜻한다. 플래그 ON에서 60초 안 OK는 `alert_delivered=false`가 정상이다. 형식서·검사기의 과거 45초 해석 개정은 정본 소유자 후속 작업이다.

운영의 `self-check-first=true` 전환은 FE의 S01 수신·1분 확인 화면·응답 API 배포 확인 뒤에 한다. 기본값은 `false`다.

## 9. 미결정 사항 (Open Questions)

2026-10-02 구현 시점에도 T1 회신은 확인되지 않았다. BE 구현 계획 §5의 아래 가정을 적용했다. 회신이 다르면 배포 전 결정 게이트를 연다.

| 항목 | 구현 가정 | 후속 확인 |
| --- | --- | --- |
| T1-① 1분 기준 시각·경계 | 서버 사건 생성 시각부터 +60초, 정확히 마감은 초과 | FE 시간 계산 일치 |
| T1-② HELP 값 유지 | 워치 호환을 위해 HELP 유지, `ESCALATED` 후 스케줄러가 INITIAL_ALERT | 폐기 회신이면 400 |
| T1-③ FCM 전체 실패·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, INITIAL_ALERT는 1분에 그대로 발송 | 전달 실패 표시 계약 |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms`는 스케줄러 enqueue 시각(플래그 OFF는 즉시 enqueue 시각). provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속 | W12 자동전화 기산점 |

W11b 전까지 단건 사건 재사용은 `situation-window-min=5`를 사건의 `opened_at_ms`에 적용한다. 마지막 감지 시각과 상황 종료가 도입되면 이 임시 기준을 교체한다.

## 10. 참고

- `B-SELFCHECK-ORDER-SPEC-v0.1` §1~§4, `FE-HANDOFF-v0.4` §5.2·§6 안전 상태 데이터, 대조표 T1, `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5.
- 충돌 시 `HEMLO-REVIEW-v0.5`를 우선하고 문구는 `NOTIFICATION-COPY-CATALOG-v0.4` S01·S04를 따른다. `FE-DELIVERY-PACKAGE-v1.2`, `NOTIFICATION-CENTER-UX-SPEC-v0.3`, 코드 변경점 표 S1·S1b·S2·S3·S4·S12·S13도 대조했다.
