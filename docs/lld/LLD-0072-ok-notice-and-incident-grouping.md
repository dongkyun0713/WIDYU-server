# LLD-0072: OK 정보성 알림·같은 사건 묶기·보호자 반응 기록

> **안심구역 S09·OK 안내와 `SAFE_ZONE_EXIT` 사건 묶기 기대는 [LLD-0078](LLD-0078-safe-zone-notice-not-incident.md)이 대체한다.** 심박 S08과 심박 사건 묶기·보호자 반응 기록은 유지한다.

> Low-Level Design. 이 문서는 이슈 #708의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-02 구현·검증 완료, 코디네이터 검수 대기) |
| Issue | #708 |
| 관련 ADR | ADR-0038 결정 4·6·8, ADR-0037 결정 2·3·4·5·6 |
| 선행 | LLD-0070(W11a-1), LLD-0071(W11a-2), W5의 `PushSettingGroup`·알림센터 enqueue |
| 작성자 | Codex / feature/708 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 사건 응답은 기한 안의 OK를 `OK_CLOSED`로 저장하지만 보호자에게 괜찮다는 안내를 보내지 않는다. 심박 판정 경로는 감지마다 사건을 열고, 단건 경로의 재사용 창은 사건 개시 시각을 기준으로 삼아 이어지는 감지를 정확히 묶지 못한다. 이 변경은 S08/S09 정보성 알림, 마지막 감지 기준의 사건 묶기, 실제 연락 행동의 첫 기록을 사건 트랜잭션에 연결한다.

## 2. 범위

### In scope

- `widyu-domain`: `Incident`의 `okNoticeSentAtMs`, `lastDetectedAtMs` 추가. `guardianResponseType`은 `MESSAGE_SENT|CALL_INITIATED` enum으로 다루고 `@Enumerated(STRING)`·`@JdbcTypeCode(SqlTypes.VARCHAR)`를 적용한다. 기존 `lastDecisionId`, `detectionCount`, `situationEndedAtMs`, `guardianResponseAtMs/By`를 사용한다.
- `widyu-api`: `IncidentService.respond`의 S08/S09 enqueue, `attachOrOpen`의 같은 사건 판정, 기존 판정·단건 `openForAlert`의 위임, 보호자 반응 API·DTO·저장소 조건부 UPDATE·Swagger 문서 및 관련 테스트.
- S08/S09의 `GENERAL` 푸시 자격은 ADR-0037 결정 3대로 기존 claim/preflight에서만 판정한다. `FcmOutboxService`·`FcmEligibility`는 변경하지 않는다.
- 운영 DDL과 ERD의 새 열·enum 값 설명 갱신은 구현 PR에 포함한다.

### Out of scope

- 실제 메시지 전송 성공 또는 전화 시작 자체의 구현, 자동전화 예약·취소 실행, `FINAL_ESCALATION`, 제공자 콜백과 통화 이력(W12).
- FE의 S10 표시 시간·화면 처리, 안전 예외 격상(S14), 낙상 `FALL_SUSPECTED` 정책 변경, FCM provider 전달 exactly-once 보장.
- `FcmCategory` 값 추가, 레거시 안전 알림 재분류, 기존 센터 행의 소급 생성.

## 3. 인터페이스 / API

기존 `POST /api/v1/incidents/{ref}/response`의 요청·응답 경로를 유지한다. `response=OK`가 기한 안에 수락되어 재조회 상태가 `OK_CLOSED`일 때 서버가 보호자 S08/S09를 enqueue하고 `okNoticeSentAtMs`를 기록한다. `respondedAtMs`는 서버 수신 시각이며 정확히 `respondByMs`는 늦은 응답이다. FE S10은 서버가 이 요청을 성공 반환한 뒤 표시한다. 현재 `IncidentResponse`에 `okNoticeSentAtMs`를 추가해 enqueue 시작 사실을 전달한다. 이는 provider 수락·단말 도착 시각이 아니다.

```http
POST /api/v1/incidents/{ref}/guardian-response
Authorization: Bearer <guardian-access-token>
Content-Type: application/json

{"type":"MESSAGE_SENT"}
```

`type`은 필수 enum `MESSAGE_SENT|CALL_INITIATED`다. `MESSAGE_SENT`는 실제 메시지 발송 성공 뒤, `CALL_INITIATED`는 보호자가 직접 전화 걸기를 시작한 뒤 호출한다. 화면 열람·팝업 확인·provider `answered`는 입력 근거가 아니다. 요청자는 같은 가족의 활성 보호자여야 한다. 서버가 `guardianResponseAtMs`를 찍고 `guardianResponseBy`에 인증 회원 ID를 저장한다. 기기 시각은 받지 않는다.

```json
{
  "code": "INCIDENT_2005",
  "message": "보호자 반응 기록 완료",
  "data": {
    "incidentId": "inc-0123456789abcdef0123456789abcdef",
    "guardianResponseType": "MESSAGE_SENT",
    "guardianResponseAtMs": 1790900000000,
    "guardianResponseBy": 123
  },
  "traceId": "request-trace-id"
}
```

응답 DTO는 `from(Incident)` 팩토리로 만든다. 기존 `IncidentResponse`에도 `guardianResponseType/AtMs/By`를 추가해 가족 사건 조회에서 첫 행동을 읽을 수 있게 한다. 안전 상태의 `safetyEventId`는 `incident_ref`, `guardianAlertedAt`은 `initial_alert_sent_at_ms`, `okNoticeSentAt`은 `ok_notice_sent_at_ms`에 대응한다. 내부 `IncidentState` 값과 기존 API 필드명은 유지한다.

## 4. 데이터 모델

| 대상 | 변경·제약 | 의미 |
| --- | --- | --- |
| `incident.ok_notice_sent_at_ms` | `BIGINT NULL` | S08/S09를 같은 트랜잭션에서 enqueue한 서버 시각. 수신자 0명이어도 처리 완료를 기록한다. |
| `incident.last_detected_at_ms` | `BIGINT NULL`, 신규 사건은 개시 시각으로 초기화 | 마지막 감지의 서버 시각. 5분 창은 이 값으로만 계산한다. 기존 행의 NULL은 `opened_at_ms`로 읽고 운영 백필한다. |
| `incident.last_decision_id` | 기존 `VARCHAR(40) NULL` | 이 사건에 마지막으로 붙인 판정 ID. 최초 판정의 `decision_id` UK는 그대로 둔다. |
| `incident.detection_count` | 기존 `INT NOT NULL DEFAULT 1` | 새 감지를 실제로 붙일 때만 1 증가. 같은 `decisionId`의 바로 이어진 재시도는 증가시키지 않는다. |
| `incident.situation_ended_at_ms` | 기존 `BIGINT NULL` | 심박 OK·RESOLVED 또는 마지막 감지 + 5분 경과 때 채운다. 안심구역은 재진입이 종료 신호다(LLD-0071). |
| `incident.guardian_response_type` | 기존 `VARCHAR(20) NULL` | Java enum `MESSAGE_SENT|CALL_INITIATED`; native MySQL ENUM으로 변경하지 않는다. |
| `incident.guardian_response_at_ms/by` | 기존 `BIGINT NULL` | 첫 실제 연락 행동의 서버 시각·보호자 회원 ID. |

`incident_ref:OK`는 39자이므로 기존 센터 `event_id VARCHAR(40)`에 들어간다. `FcmCategory`는 유지한다. 신규 `ok_notice_sent_at_ms`·`last_detected_at_ms`의 DDL을 추가하고 기존 행을 `last_detected_at_ms=opened_at_ms`로 백필한다. `incident.kind/state/response/outcome`과 새 보호자 반응 enum 열은 `VARCHAR` 매핑을 유지한다. `last_detected_at_ms`를 `updated_at`으로 대체하지 않는다. 응답·알림·사후 판정이 행 수정 시각을 바꿔 5분 창을 부당하게 연장하기 때문이다.

## 5. 처리 흐름

### 5.1 기한 안 OK와 정보성 알림

1. `IncidentService.respond`는 기존처럼 소유자 확인 후 `IncidentRepository.respond` 조건부 UPDATE를 실행한다. 0건이면 409이고 아무 알림도 만들지 않는다. 성공하면 clear된 영속성 컨텍스트에서 사건을 재조회한다. **재조회 상태가 `OK_CLOSED`이고 요청이 `OK`인 경우에만** 뒤 단계를 실행한다. `ESCALATED`인 늦은 OK는 응답 사실만 보존하고 S08/S09와 `okNoticeSentAtMs`를 만들지 않는다.
2. 심박 사건은 `SAFETY_SENIOR_OK_NOTICE_HEART`/S08, 안심구역 사건은 `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE`/S09를 사용한다. `NotificationCopy.of`에 실제 시니어 이름을 넣는다. S08 제목은 `{시니어 이름} 님의 신체 지표가 평소와 달랐어요.`, S09 제목은 `{시니어 이름} 님이 안심구역을 벗어났어요.`이며 본문은 모두 `본인은 괜찮다고 하셨어요. 필요하면 연락해보세요.`다. 심박 수치·사유·좌표는 넣지 않는다. 낙상은 매핑하지 않는다.
3. 시니어 가족의 **활성 안전 수신 보호자 전원**(방장 포함)을 구한다. 수신자별 `FcmOutboxService.enqueue`에 `eventId=incident_ref:OK`, `entityId=incident_ref`, `seniorId`·`relatedMemberId=seniorId`, 해당 `NotificationType`을 전달한다. 두 type은 `PUSH_AND_CENTER`, 일반 우선순위(`INTERACTION`), 센터 `LOCATION`, `PushSettingGroup.GENERAL`, [LLD-0076](LLD-0076-guardian-deeplink-paths.md)의 `/location?seniorId={seniorId}` 딥링크다. 보존 기간은 심박 180일·안심구역 90일이다. `emergency=false`이며 `INITIAL_ALERT` 게이트나 5분 자동전화 기산점을 건드리지 않는다.
4. 센터 행은 설정·토큰 유무에 관계없이 수신자×`incident_ref:OK` 한 건을 저장한다. 활성 토큰별 outbox는 기존 enqueue 경로가 만든다. `GENERAL` 설정 OFF이면 기존 claim/preflight가 outbox를 `CANCELLED`로 만들고 실제 푸시는 0건이다. `pushEligible`은 타입 정책 스냅샷이므로 OFF여도 true일 수 있다. 설정 판정을 enqueue로 옮기거나 타입별 분기를 추가하지 않는다.
5. 같은 `respond` 트랜잭션에서 `ok_notice_sent_at_ms`를 서버 시각으로 채운다. 조건부 응답 UPDATE, 센터 행·outbox, 이 시각은 함께 커밋하거나 함께 롤백한다. 0명 수신에도 플래그를 기록하고 incident ref와 수신자 0명만 WARN으로 남긴다. 센터 `UK(recipient_member_id,event_id)`와 최초 응답 `response IS NULL` 조건이 중복 센터 행·enqueue를 막는다. 실제 FCM 전달은 ADR-0028의 at-least-once 범위다.

### 5.2 마지막 감지 기준의 `attachOrOpen`

1. W11a-1의 `openForAlert(DecisionRecord, kind)`와 `openForAlert(memberId, kind)`는 공통 `attachOrOpen`을 호출한다. 기존 호출 시그니처는 유지한다. 배치·단건 심박과 W11a-2의 안심구역 이벤트가 같은 사건 생성·재사용 경계를 쓴다. `FallAssessmentService`의 낙상 경로는 기존 동작을 보존한다.
2. `attachOrOpen`은 항상 `MemberRepository.findByIdForUpdate(memberId)`로 **회원 행을 먼저 잠근 뒤** 사건을 조회한다. 배치 판정의 `decision_id` UK를 잠금 뒤 확인하고, 같은 회원·종류의 열린 사건(없으면 가장 최근 사건)의 `decision_id`·`last_decision_id`와도 비교한다. 일치하면 시간 창을 보기 전에 사건을 변경 없이 반환한다. 안심구역은 잠금 뒤 최신 `location:stay`를 확인해 재진입했다면 새 사건을 열지 않는 LLD-0071 규칙을 유지한다.
3. 판정 재시도 확인 뒤 심박은 같은 회원·`HR_ANOMALY`에서 `state IN (CHECKING, ESCALATED)`, `situation_ended_at_ms IS NULL`인 최근 사건의 시간 창을 판단한다. `last_detected_at_ms`(기존 행은 `opened_at_ms`)부터 **5분 미만**이면 같은 상황이다. 정확히 5분 또는 이후면 이전 사건에 `situation_ended_at_ms=last_detected_at_ms + 5분`을 기록하고 새 사건을 연다. `situation-window-min` 기본값은 5다. `OK_CLOSED`·`RESOLVED`는 시간이 남아도 재사용하지 않는다. 심박 OK·RESOLVED는 상태 전이와 같은 트랜잭션에서 `situation_ended_at_ms`를 채운다. 이미 지난 열린 사건은 상태를 바꾸지 않는다.
4. 새 감지에만 같은 심박 사건의 `last_detected_at_ms`를 서버 감지 시각으로, `last_decision_id`를 새 판정 ID로, `detection_count`를 1 증가시킨다. 판정 없는 단건도 감지마다 시각·횟수를 갱신하되 `last_decision_id`는 기존 값을 유지해 마지막 배치 판정의 재시도를 식별한다. 마지막 귀속 판정의 재시도는 5분 창이 지난 뒤에도 count를 늘리지 않는다. 이 분기에서는 사건·S01·S04·센터 행을 새로 만들지 않는다. 처음 사건을 연 분기에서만 기존 S01과 플래그 OFF S04를 enqueue한다.
5. 안심구역 이탈은 판정 ID가 없고 물리적 재진입 전까지 동일한 이탈이다. LLD-0071대로 `situation_ended_at_ms IS NULL`인 `SAFE_ZONE_EXIT`를 상태와 시간 창에 관계없이 재사용하고 S02·S05를 다시 만들지 않는다. 재진입이 종료 시각을 기록한 뒤 다음 이탈은 새 사건이다. OK/RESOLVED만으로 안심구역의 물리적 재진입을 추정하지 않는다.

감지 시각은 서버가 사건 경로에 들어온 시각이며 단말 시각·JPA `updated_at`을 쓰지 않는다. 두 진입점 모두 회원 행 잠금 → incident 조회·갱신 순서를 유지한다. 사건·본인확인·플래그 OFF 최초 알림은 선행 LLD의 동일 트랜잭션 정책을 따른다. 심박 `AFTER_COMMIT` 리스너의 새 트랜잭션과 안심구역 리스너의 새 트랜잭션을 합치거나 원 심박·위치 저장 트랜잭션으로 옮기지 않는다.

### 5.3 보호자의 첫 실제 연락 행동

1. Controller는 인증 보호자 ID와 `{ref}`·요청 type을 `IncidentService.recordGuardianResponse`에 넘긴다. 서비스는 사건을 조회해 시니어 ID를 얻고 요청자의 `GUARDIAN` 타입과 같은 가족 연결을 먼저 검증한다. 사건 없음·시니어 본인·타 가족은 계정 활성 여부와 관계없이 404로 응답한 뒤, 같은 가족 보호자의 계정 활성 상태를 검사해 비활성이면 403으로 응답한다. 가족 연결은 행 존재로 활성 여부를 판단한다.
2. `IncidentRepository.recordGuardianResponse`는 `guardian_response_type IS NULL`을 조건으로 type·서버 시각·보호자 ID를 한 UPDATE에서 기록한다. 영향 행 1건이면 재조회한 값으로 DTO를 반환한다. 0건이면 409이며 첫 기록은 덮지 않는다. 보호자 둘의 동시 요청도 한 건만 성공한다.
3. 기록 성공 지점에 `// W12: 미실행 자동전화·FINAL_ESCALATION 예약 취소` 훅 자리만 남긴다. 이번 변경에서 스케줄러·제공자 호출·예약 상태 전이를 추가하지 않는다. 알림 열람, 팝업 확인, provider `answered`는 이 API를 호출하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | HTTP·코드 / 효과 |
| --- | --- |
| 인증 없음 | 기존 인증 401. |
| 보호자 반응 type 누락·허용값 밖 | 기존 요청 역직렬화·검증 오류 400 `REQ_4000`; 사건 변경 없음. |
| 사건 없음·타 가족 사건(보호자 계정 비활성 포함)·시니어 본인이 보호자 반응 요청 | 404 `INCIDENT_4040`; 타 가족 사건의 존재를 노출하지 않음. |
| 같은 가족 보호자 계정 비활성 | 403 `AUTH_4030`; 기록 없음. |
| 보호자 반응이 이미 기록됨 | 신규 `INCIDENT_4092`; 첫 type·시각·작성자를 유지. |
| 이미 응답한 본인확인 | 기존 `INCIDENT_4090`; S08/S09 추가 없음. |
| OK 알림 enqueue 또는 DB 저장 실패 | 응답 UPDATE·센터/outbox·`ok_notice_sent_at_ms` 함께 롤백. 재요청 가능. |
| 가족 없음·활성 안전 수신 보호자 0명 | S08/S09 센터/outbox 0건, `ok_notice_sent_at_ms` 기록, incident ref만 WARN. |

## 7. 인수조건 (Acceptance Criteria)

- [ ] AC1. `self-check-first=true`인 심박 사건에 서버 시각 30초의 OK를 보내면 `OK_CLOSED`, `ok_notice_sent_at_ms`가 남고 활성 안전 수신 보호자 각각에 S08 센터 행이 `eventId=incident_ref:OK`로 1건씩 생긴다. S04 `INITIAL_ALERT`는 0건이다. 안심구역은 같은 조건에서 S09를 만든다.
- [ ] AC2. `GENERAL`을 끈 보호자(방장 포함)도 S08/S09 센터 `LOCATION` 행 1건을 받는다. 활성 토큰의 outbox는 claim/preflight에서 `CANCELLED`가 되고 실제 푸시는 0건이다. `pushEligible`은 타입 정책 스냅샷이라 true일 수 있다. 토큰 0개여도 센터 행은 1건이다.
- [ ] AC3. 정확히 60초와 61초의 OK는 `ESCALATED` 응답만 남기고 S08/S09·`ok_notice_sent_at_ms`는 0/NULL이다. 마감 전 OK와 스케줄러 경합, 중복 OK에서 S08/S09는 수신자당 최대 1건이며 실패 트랜잭션은 재시도할 수 있다.
- [ ] AC4. 심박 `CHECKING` 중 새 배치 판정 또는 단건 재감지는 같은 사건에 붙어 사건 0건·본인확인/최초 알림 0건을 추가하고 `detection_count=2`, `last_detected_at_ms` 갱신, 배치면 `last_decision_id` 갱신을 확인한다. 동일 판정의 즉시 재시도는 count를 늘리지 않는다.
- [ ] AC5. 마지막 감지 기준 5분 미만이면 `ESCALATED` 심박도 재사용한다. 정확히 5분과 그 이후의 재감지는 이전 `situation_ended_at_ms`를 채우고 새 사건·S01을 만든다. 이전 사건 상태는 유지한다.
- [ ] AC6. 심박 `OK_CLOSED`·`RESOLVED` 뒤 재감지는 5분 안이어도 새 사건이다. 안심구역 `OK_CLOSED`·`RESOLVED`는 재진입 전에는 기존 사건을 재사용하고, 재진입 뒤 새 이탈은 새 사건이다. 동시 진입은 회원 행 잠금으로 사건 하나에 수렴한다.
- [ ] AC7. 같은 가족의 활성 보호자만 `POST /api/v1/incidents/{ref}/guardian-response`에 `MESSAGE_SENT|CALL_INITIATED`를 기록할 수 있다. 첫 요청은 type·서버 시각·보호자 ID를 저장하고 두 번째 또는 동시 요청의 패자는 409이며 첫 값은 유지된다. 타 가족·시니어·비활성 회원·잘못된 type의 오류와 무변경을 검증한다.
- [ ] AC8. 보호자 반응 기록만으로 자동전화·최종 푸시 실행은 시작되지 않는다. S08/S09도 긴급 채널·`INITIAL_ALERT`·5분 기산점을 사용하지 않는다. 문구는 COPY-CATALOG S08/S09, 보존은 원 사건 종류를 따른다.
- [ ] AC9. Swagger에 새 API의 요청·성공/400·403·404·409와 기존 OK 응답의 `okNoticeSentAtMs`가 반영된다. `FcmCategory` 값은 추가하지 않고 새 enum 열은 VARCHAR다. ERD·운영 DDL을 갱신한다.
- [ ] AC10. `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과한다. 테스트는 JUnit 5·Mockito의 BDDMockito `given/willReturn`, 한글 언더스코어 메서드명, `<행위>하면 <결과>한다` DisplayName을 따르고 상태를 우선 검증한다. H2 결과를 운영 MySQL native ENUM 검증으로 간주하지 않는다.

## 8. 영향 범위 / 마이그레이션

- W11a-1의 `openForAlert(decision, kind)`가 판정마다 새 사건을 여는 경로와 단건의 `opened_at_ms` 기준 5분 재사용을 `attachOrOpen`으로 교체한다. W11a-2의 안전구역 재진입·Redis 30분 키 제거 설계를 유지한다. 기존 `decision_id` UK는 그대로 두고 새 열 `ok_notice_sent_at_ms`, `last_detected_at_ms`만 DDL에 추가한다.
- 새 `scripts/mysql/alter_incident_ok_notice_grouping.sql`은 `alter_incident_self_check_first.sql` 뒤에 적용한다. `ALTER TABLE incident ADD COLUMN ok_notice_sent_at_ms BIGINT NULL, ADD COLUMN last_detected_at_ms BIGINT NULL;` 뒤 `UPDATE incident SET last_detected_at_ms = opened_at_ms WHERE last_detected_at_ms IS NULL;`로 백필한다. W11a-1 스크립트는 수정하지 않는다. 새 행은 애플리케이션에서 마지막 감지 시각을 채운다. `guardian_response_type`은 기존 VARCHAR(20)을 사용하므로 native ENUM ALTER는 없다. `FcmCategory`를 바꾸지 않는다.
- S08/S09의 센터 행과 outbox 생성은 기존 enqueue 경로를 사용하고 푸시 설정은 claim/preflight에서 판정한다. 운영 `ddl-auto=validate` 환경에서는 DDL을 기동 전에 적용하고, 운영 MySQL 컬럼 타입은 `SHOW COLUMNS`로 확인한다.
- LLD-0054의 「판정 하나 = 사건 하나」 설명과 LLD-0070의 `opened_at_ms` 임시 창은 이 LLD의 같은 사건 규칙으로 대체한다. `alert_delivered`는 `INITIAL_ALERT` 실제 전송 성공만 뜻하며 S08/S09로 참이 되지 않는다.

## 9. 미결정 사항 (Open Questions)

전체 구현 계획 §5의 회신 대기 항목과 작업 가정을 그대로 유지한다. 회신이 가정과 다르면 해당 후속 작업의 결정 게이트에서 다시 판단한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
|---|---|---|---|
| T1-① 1분 기준 시각·경계 | 기준=서버 사건 생성 시각, 마감=+60s, 정확히 마감=초과(B §3 제안 수용) | 그대로 | — |
| T1-② HELP 값 유지 | 유지 제안(워치 호환, ESCALATED → 스케줄러가 INITIAL_ALERT) | 유지 | — |
| T1-③ FCM 전체 실패 시 동작·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, **INITIAL_ALERT는 그대로 1분에 발송**(시니어 미수신이 보호자 알림을 미루지 않음) | 그대로 | — |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속) | 그대로 | W12 |
| S10 표시 시간 | FE·제품 몫 | — | — |
| T2 Incident kind ↔ 제품 이벤트 | `HR_ANOMALY↔HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXIT↔SAFE_ZONE_EXITED`, `FALL_SUSPECTED`는 범위 밖(AI 입구 꺼짐) | 그대로 | — |
| T2 run 없는 경로 | `decision_id` null 허용, `incidents.jsonl`에는 run 귀속 사건만 | 그대로 | — |
| T3 자동전화 제공자·콜백·푸시 OFF 비방장 | 미정 → 구현 보류 | — | **W12 전면** |
| BE 발송본 5 「같은 사건 묶는 범위」 | 열린 사건 + 마지막 감지 5분 창, 안심구역은 재진입으로 종료 | ADR-0038 S-D6 | — |
| BE 발송본 6 「판단 단위 저장」 | 이미 저장됨(`heart_rate_event.status` 샘플별) | — | — |
| ADR-0035 결정 3 「알림=FCM 성공」 | INITIAL_ALERT 기준으로 좁힘, 60초 안 OK는 `alert_delivered=false`가 정상 | S-D10 | — |
| W02 적용 시작일 | 현행 「다음 날」 유지, 문구에 넣지 않음 | 그대로 | — |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |
| 시니어 푸시 설정 항목 | `GENERAL` 하나 | 그대로 | — |
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |
| G02 동시 달성 묶음 | 단일 트리거가 목표 2개를 동시에 적립하는 경로가 없어 G01만 발생 | G01만 | — |
| A 활성화 조건 | 카드·10p는 플래그 OFF로 머지, IRB 확인 뒤 ON | — | — |

판정 없는 단건 감지는 독립 이벤트 ID가 없어서 이전 감지의 늦은 재전송과 실제 새 감지를 구분하지 못한다. 배치 판정도 `decision_id`·현재 `last_decision_id`보다 오래된 중간 판정의 재시도는 이 두 열만으로 식별할 수 없다. 이 LLD의 count는 사건 경로가 새 감지로 받은 호출 기준이며, 입력 전체의 영구 중복 제거가 필요하면 별도 감지 ID/귀속 원장이 필요하다. 해당 입력 계약은 후속 결정 항목으로 남긴다.

2026-10-02 구현 검수 기준으로 위 가정표의 답변 변경은 없었다. W12의 자동전화 제공자·콜백과 오래된 중간 판정 재시도 식별 계약은 여전히 미결정이며, 이번 구현에는 자동전화 취소 훅 주석만 남겼다.

## 10. 참고

- ADR-0038 결정 4·6·8, ADR-0037 결정 2·3·4·5·6, [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0071](LLD-0071-safe-zone-incident.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
- `B-SELFCHECK-ORDER-SPEC-v0.1` §3~§4·§6, `FE-HANDOFF-v0.4` §1-13·§4-20·§5.2-4·5·§5.3, `HEMLO-REVIEW-v0.5` §11.2·§13.3, `NOTIFICATION-COPY-CATALOG-v0.4` S08/S09(충돌 시 HEMLO-REVIEW 우선, 문구는 COPY-CATALOG 우선).
- `FE-DELIVERY-PACKAGE-v1.2`, `NOTIFICATION-CENTER-UX-SPEC-v0.3`, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` S5·S6·S8·S11, `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5.
