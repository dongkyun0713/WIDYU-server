# LLD-0081: 보호자 2차 알림과 멈춤 기록 확장

> 이 문서는 이슈 #739(W18)의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11, 구현·검증 완료; 코디네이터 검수 대기) |
| Issue | #739 (base W19 `feature/736`) |
| 관련 ADR | [ADR-0039](../adr/ADR-0039-two-stage-guardian-alert-and-safe-zone-notice.md) 결정 2·3 (Proposed, 별도 PR #734), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) |
| 선행 | [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0077](LLD-0077-initial-alert-leader-only.md), [LLD-0078](LLD-0078-safe-zone-notice-not-incident.md) |
| 작성자 | Codex / feature-739 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

현재 심박 사건은 방장에게 ① `INITIAL_ALERT`를 한 번 예약한 뒤 보호자 멈춤이 없어도 후속 알림을 보내지 않는다. 보호자 반응도 사건당 첫 한 건만 남는다. ① 예약 3분 뒤 멈춤이 없으면 ② `SECOND_ALERT`를 한 번 보내고, 보호자별 멈춤 기록이 생기면 미발송 ②를 취소한다.

## 2. 범위

### In scope

- `widyu-domain`: `Incident`의 ② due/sent/cancelled 시각, 새 `IncidentGuardianResponse`, `GuardianResponseType.ACKNOWLEDGED`.
- `widyu-api`: ① 두 경로의 due 기록, 기존 `IncidentTimeoutScheduler`의 ② 폴링, 게이트·멈춤 저장, guardian-response API·DTO·Swagger와 테스트.
- `scripts/mysql/alter_incident_second_alert.sql`과 [ERD-0001](../erd/ERD-0001-initial-domain.md) 갱신.

### Out of scope

- ② 뒤 `FINAL_ESCALATION`, S06·S07, 반복 알림, 자동전화·자동 119.
- 낙상 2단계화, 워치 도움 요청(W21), 이미 W19에서 일반 위치 소식으로 분리한 안심구역 사건.
- 끝난 사건 상태 조회·늦게 온 `ACKNOWLEDGED` 정책(W20), 앱 모달·읽음 구현, 보호자 간 멈춤 전파.
- 공통 `FcmOutboxService`, `FcmEligibility`, `FcmSendDto`, `FcmCategory` 변경.

## 3. 인터페이스 / 알림 계약

①은 [LLD-0077](LLD-0077-initial-alert-leader-only.md)대로 현재 활성 방장 한 명이다. ②는 `initial_alert_sent_at_ms + 180000ms` 뒤 멈춤 기록이 0건인 활성 심박 사건에 한 번 예약한다. 기준은 provider 접수가 아닌 ① **enqueue 시각**이다. ① 전송 실패가 지속돼도 마감은 바뀌지 않는다. `DELIVERY_FAILED`는 정본의 전달 실패 표시명이며 현재 outbox DB 상태는 `EXHAUSTED`·`EXPIRED`다. W18은 새 실패 상태나 후속 발송을 만들지 않는다(B2 가정).

② 수신자는 enqueue 시점 시니어와 같은 가족의 활성 보호자 전원(방장 포함)이다. `NotificationType.HEART_RATE_EMERGENCY`의 `PushSettingGroup.SAFETY` 푸시가 켜진 사람은 실제 푸시 대상이고, 끈 사람은 센터 행만 받는다. `IncidentEscalation`에서 설정을 선판정해 수신자를 걸러내지 않는다. 기존 `FcmEligibility`가 claim/preflight에서 설정·가족·토큰을 검사한다. 따라서 OFF 수신자도 물리 outbox 행이 잠시 생길 수 있으나 전송 가능한 푸시는 0건이며, 토큰이 없어도 센터 행은 생긴다.

②도 S04 제목 `{시니어 이름} 님의 심박 상태를 확인해주세요.`, 본문 `평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요.`를 쓴다. `eventId=incident_ref`, `entityId=incident_ref`, `relatedMemberId=seniorId`, `seniorId`, `deepLink=/location?seniorId={seniorId}`를 보낸다. data에는 `deliveryStage=SECOND_ALERT`, `safetyEventId=incident_ref`, `incidentRef=incident_ref`를 싣고 판정 ID가 있으면 기존 `decisionId`도 유지한다. ① data는 유지한다.

```json
{"data":{"eventId":"inc-0123456789abcdef0123456789abcdef","safetyEventId":"inc-0123456789abcdef0123456789abcdef","incidentRef":"inc-0123456789abcdef0123456789abcdef","deliveryStage":"SECOND_ALERT","type":"HEART_RATE_EMERGENCY","deepLink":"/location?seniorId=17","seniorId":"17"}}
```

현재 `FcmOutboxService.enqueue`는 `findByRecipientMemberIdAndEventId`로 센터 행을 찾은 뒤 없을 때만 insert한다. `fcm_notification`에는 UK `(recipient_member_id,event_id)`가 있다. 따라서 방장의 ②는 같은 `eventId=incident_ref`로 ① 센터 행을 재사용하고, 비방장은 새 센터 행을 얻는다. 방장에게는 ②용 별도 outbox 행이 생긴다. 센터 행의 기존 읽음·본문·제목을 덮지 않는다. 사건당 ② 게이트가 동일 단계 outbox 중복을 막지만 provider 수준 exactly-once는 보장하지 않는다.

멈춤은 `ACKNOWLEDGED`(덮는 모달 `확인`), `MESSAGE_SENT`(실제 메시지 전송 완료), `CALL_INITIATED`(앱이 전화 앱을 연 순간) 세 종류다. 사건×보호자×종류당 한 건이다. 같은 종류 재호출은 원래 시각을 돌려주는 200 멱등 응답이며 다른 종류는 새 행이다. 어느 보호자든 멈춤 하나를 남기면 미발송 ②를 취소한다. 푸시 도착·탭, 앱·위치 열람, 읽음, 메시지 작성 화면 이동은 멈춤이 아니므로 이 API를 호출하지 않는다.

## 4. 데이터 모델과 동시성 설계

| 위치 | 변경 | 의미 |
| --- | --- | --- |
| `incident` / `Incident` | `second_alert_due_at_ms BIGINT NULL` | ① enqueue 시각 + 180000, 재시작 후 폴링 기준 |
| `incident` / `Incident` | `second_alert_sent_at_ms BIGINT NULL` | ② enqueue 게이트 시각, provider 성공 시각 아님 |
| `incident` / `Incident` | `second_alert_cancelled_at_ms BIGINT NULL` | 멈춤·상황 종료·배포 시 기존 사건 억제로 닫힌 시각 |
| `incident_guardian_response` / `IncidentGuardianResponse` | `id` PK, `incident_id` FK, `guardian_member_id` FK, `response_type VARCHAR(20) NOT NULL`, `responded_at_ms BIGINT NOT NULL` | UK `uk_incident_guardian_response_kind(incident_id,guardian_member_id,response_type)` |

sent와 cancelled를 별도 열로 둬 예약 완료와 취소를 명확히 구별한다. sent 음수나 취소 플래그로 두 의미를 합치지 않는다. 엔티티는 `widyu-domain`, repository·service·DTO는 `widyu-api`에 둔다. 새 enum 열은 `@Enumerated(STRING)`와 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 적용한다. MySQL native ENUM 변경은 없다. 기존 `Incident.guardian_response_type/at_ms/by`는 옛 조회 응답 호환용 **읽기 전용**으로 남기고 새 기록에서는 쓰지 않는다. 새 테이블이 멈춤 판정의 정본이며, 구 컬럼 삭제·조회 응답 전환은 다음 정리에서 한다.

운영 DDL `scripts/mysql/alter_incident_second_alert.sql`은 세 열, `idx_incident_second_alert_pending(second_alert_sent_at_ms,second_alert_due_at_ms)`, 새 테이블·FK·UK를 만든다. 구 첫 기록의 type·시각·보호자 ID가 모두 있으면 새 테이블로 멱등 백필한다. **기존 사건은 due를 백필해 ②를 재개하지 않는다.** migration 시 이미 있던 심박 사건은 최초 알림이 아직 없어도 `second_alert_cancelled_at_ms`를 migration 시각으로 채운다. 나중에 ①이 나가도 ②는 억제된다. 이는 [LLD-0070](LLD-0070-self-check-first-heart.md)의 기존 열린 사건 ① 백필처럼 배포 직후 옛 사건의 중복 알림을 막기 위한 선택이다. DDL을 앱 전환 전에 적용하고 혼합 쓰기 창을 피한다. H2로 운영 백필 결과를 재현하기 어려운 한계는 PR 비고에 남긴다.

① ON 경로의 `claimInitialAlertIfDue`는 `initial_alert_sent_at_ms=:now`와 `second_alert_due_at_ms=:now+180000`을 **같은 UPDATE**로 채운다. OFF `sendImmediately`는 `markInitialAlertSent`가 두 열을 함께 변경해 동일한 엔티티 flush UPDATE에 싣는다. S04 enqueue 실패 시 게이트·due가 함께 롤백된다. 방장이 없더라도 기존 정책대로 ① 게이트와 due는 기록한다.

기존 `IncidentTimeoutScheduler`는 ① 폴링 뒤 `findSecondAlertDueIds(now,afterId,PageRequest.of(0,limit))`를 id 키셋·페이지 상한으로 순회한다. 폴링 시작 시각은 **후보 조회에만** 쓴다. 조회 조건은 심박, due 경과, sent/cancelled NULL이다. 사건별 `IncidentEscalation.sendSecondAlertIfDue(id)`는 `REQUIRES_NEW`에서 사건 행 잠금을 얻은 뒤 현재 시각을 한 번 새로 구한다. 이 사건별 시각을 전제 검사·마지막 감지 + 5분 창·조건부 발송/취소 게이트와 기록 시각에 함께 쓴다. 앞 사건 처리나 행 잠금 대기가 길어져도 지난 시각으로 만료된 창을 통과시키지 않는다. 사건 행을 잠가 멈춤 기록의 동일 행 잠금과 직렬화한다. 마지막 감지(없으면 개시) + 5분 창, `situation_ended_at_ms`, 상태 `CHECKING|ESCALATED`, 새 테이블 멈춤 0건을 확인한다. 이미 끝났거나 멈춤이 있으면 미발송 조건부 취소 UPDATE로 닫는다. 정상 후보에서 다음 게이트가 1건일 때만 같은 트랜잭션에서 사건 활성·멈춤 0건, 현재 가족·방장·활성 보호자 전원을 다시 읽고 enqueue한다. 수신자가 0명이면 incident ref만 WARN으로 남기고 게이트를 닫는다. 푸시 설정은 공통 eligibility가 판단한다.

```sql
UPDATE incident SET second_alert_sent_at_ms = :now
 WHERE incident_id = :id AND second_alert_sent_at_ms IS NULL
   AND second_alert_cancelled_at_ms IS NULL
   AND second_alert_due_at_ms <= :now
   AND situation_ended_at_ms IS NULL;
```

게이트 전 행 잠금 안의 검증에서 종료·멈춤을 발견하면 같은 트랜잭션에서 조건부 취소한다. 게이트 후 멈춤 재확인에 실패하면 롤백하고 다음 폴링에서 다시 판단한다. enqueue 예외는 게이트·센터·outbox를 함께 롤백해 다음 폴링에서 재시도한다. `attachOrOpen`은 새 감지가 올 때만 지난 5분 창을 종료하므로 `situation_ended_at_ms`가 아직 NULL이어도 계산상 끝난 사건은 취소한다.

guardian-response는 가족 검사 404 → 같은 가족 보호자 계정 활성 403 순서를 유지한다. 선행 `findByIncidentRef` 일반 SELECT가 MySQL `REPEATABLE READ` 스냅샷을 만들고 사건을 영속성 컨텍스트에 적재하므로, 검사 뒤 `EntityManager.clear()`를 한 번 호출한다. 이어 `findByIdForUpdate`로 **새 인스턴스**의 최신 사건 행을 잠근다. `clear()`만으로는 이미 만들어진 DB 읽기 스냅샷을 갱신할 수 없다. 따라서 같은 보호자·종류 멈춤 조회에도 `PESSIMISTIC_WRITE`를 적용해 잠금 읽기의 최신 커밋을 본다. 기존 행이 있으면 원래 시각과 잠금 뒤 사건의 취소 상태를 반환한다. 없으면 INSERT(UK 방어) 뒤 다음 UPDATE를 실행하고, 취소 여부는 UPDATE 결과 또는 잠금 뒤 사건 값으로 계산한다. 다른 보호자·종류는 별도 행이다. 멈춤이 행 잠금을 먼저 얻으면 ② claim은 취소 뒤 0건이다. ②가 먼저 얻으면 enqueue 뒤 멈춤의 취소 UPDATE는 0건이고 멈춤 기록은 남는다. sent와 cancelled가 모두 채워지는 상태는 없다(B3 가정). `sendSecondAlertIfDue`는 독립된 `REQUIRES_NEW` 트랜잭션에서 선행 일반 SELECT 없이 사건 행을 먼저 잠그므로 이 선행 스냅샷 결함의 영향을 받지 않는다.

```sql
UPDATE incident SET second_alert_cancelled_at_ms = :now
 WHERE incident_id = :id AND second_alert_sent_at_ms IS NULL
   AND second_alert_cancelled_at_ms IS NULL;
```

## 5. 처리 흐름

1. `IncidentService.attachOrOpen`은 기존 5분 묶기와 시니어 S01을 유지한다. ON은 60초 무응답/HELP의 ① 키셋 폴링, OFF는 즉시 ①에서 due를 기록한다.
2. 같은 `sensor.incident.timeout-poll-ms`(현재 5000ms)로 ②를 폴링한다. 각 사건은 `REQUIRES_NEW`여서 한 사건의 실패가 다음 후보를 막지 않고, 재시작 뒤 지난 due도 DB에서 다시 읽는다.
3. ② 서비스는 행 잠금·조건부 게이트·직전 재검증 뒤 활성 보호자별 `FcmOutboxService.enqueue`를 호출한다. 새 `@Async`를 만들지 않고 기존 커밋 후 dispatcher를 쓴다.
4. 모달 `확인`은 FE가 `ACKNOWLEDGED`와 기존 읽음 PATCH를 각각 호출한다. 메시지 작성 화면 이동은 읽음만, 실제 전송 완료는 `MESSAGE_SENT`, 전화 앱 열기는 `CALL_INITIATED`다. 다른 보호자에게 멈춤을 알리지 않는다.

## 6. HTTP API / 예외 처리

```http
POST /api/v1/incidents/{incidentRef}/guardian-response
Content-Type: application/json

{"type":"ACKNOWLEDGED"}
```

요청 필드는 기존 `GuardianResponseRequest.type`을 유지한다. `responseType` 개명이나 별칭을 추가하지 않는다. 허용값은 세 멈춤 종류다. 기존 응답 필드를 유지하고 기록 시각·② 취소 여부를 더한다. 중복은 원래 `guardianResponseAtMs`를 반환하며 `secondAlertCancelled`는 응답 시점 취소 열의 유무다. DTO는 `from()/of()` 팩토리를 쓴다.

```json
{"code":"INCIDENT_2005","message":"보호자 반응 기록 완료","data":{"incidentId":"inc-0123456789abcdef0123456789abcdef","guardianResponseType":"ACKNOWLEDGED","guardianResponseAtMs":1791700000000,"guardianResponseBy":21,"secondAlertCancelled":true},"traceId":"example-trace-id"}
```

| 조건 | HTTP·코드 / 효과 |
| --- | --- |
| 인증 없음 | 기존 401, 기록 없음 |
| 누락·잘못된 종류 | 기존 400 `REQ_4000`, 기록 없음 |
| 사건 없음·타 가족·시니어 요청 | 기존 404 `INCIDENT_4040`, 사건 존재 노출 없음 |
| 같은 가족의 비활성 보호자 | 기존 403 `AUTH_4030`, 가족 검사 뒤 판정 |
| 같은 보호자·종류 중복 | 200 `INCIDENT_2005`, 최초 시각 반환, 추가 행 없음 |
| 다른 보호자·종류 | 200 `INCIDENT_2005`, 별도 행, 미발송 ②만 취소 |
| ② enqueue 실패 | 게이트와 outbox/센터 롤백, 다음 폴링 재시도 |

기존 `INCIDENT_4092`(`INCIDENT_GUARDIAN_RESPONSE_ALREADY_RECORDED`)의 “사건당 첫 반응” 의미는 폐기한다. 같은 보호자·종류 중복에는 쓰지 않고 `IncidentDocs` 409 설명도 제거한다. 현재 다른 호출처는 없으므로 구현 시 enum 코드를 미사용 호환 항목으로 남기는 안을 택한다. 새 `INCIDENT_*` 코드는 필요 없다. 필요해지면 `backend/widyu-domain/src/main/java/com/widyu/global/error/ErrorCode.java`에 정의하고 이 LLD를 갱신한다. 끝난 사건의 늦은 ACK 정책은 W20에서 다루며 이번에는 기존 **보호자 계정 활성 검사**를 유지한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. ON `claimInitialAlertIfDue`와 OFF `sendImmediately` 모두 ① 시각과 due=①+180000을 같은 UPDATE로 기록한다. enqueue 실패는 둘 다 롤백한다.
- [x] AC2. 멈춤 없이 3분이 지나면 ②가 1회다. SAFETY ON 활성 보호자 전원(방장 포함)은 outbox/센터를 갖고, OFF 보호자는 센터 행과 유효 푸시 0건을 갖는다(물리 outbox는 CANCELLED 가능). 방장 센터는 ①과 합쳐 1행이다. 토큰이 없어도 센터는 남는다. ② 뒤 추가 발송은 0회다.
- [x] AC3. S04 문구·딥링크·`eventId=safetyEventId=incidentRef=incident_ref`·`deliveryStage=SECOND_ALERT`·있을 때 `decisionId`를 검증한다. `FINAL_ESCALATION`·S06·S07·자동전화는 0건이다.
- [x] AC4. 3분 안 어느 보호자의 `ACKNOWLEDGED`, `MESSAGE_SENT`, `CALL_INITIATED`든 cancelled 시각이 남고 ②는 0건이다. 읽음·탭·열람만 있으면 ②는 나간다.
- [x] AC5. 선행 일반 조회 뒤 다른 트랜잭션이 같은 보호자·종류를 커밋해도 재호출은 200·원래 시각·한 행이다. 다른 보호자가 먼저 ②를 취소했으면 응답의 `secondAlertCancelled=true`다. 다른 보호자·종류는 새 행이다. 타 가족 404 → 같은 가족 비활성 403 순서와 잘못된 입력 400을 지킨다.
- [x] AC6. H2 경합 테스트에서 ② claim과 멈춤 INSERT→취소 UPDATE 중 sent 또는 cancelled만 기록된다. ②가 먼저 이겨도 뒤 멈춤 행은 저장된다. 동시·반복 폴링에서 ② 중복 outbox가 없다.
- [x] AC7. 재시작 후 지난 due는 DB에서 발송된다. OK·사후 판정·마지막 감지 뒤 5분 창 종료 후에는 due가 지나도 ②가 0건이다. 종료 시각이 아직 NULL인 만료 창도 막는다.
- [x] AC8. HELP와 플래그 OFF에서도 due를 예약한다. 기존 HR 묶기·낙상·W19 안심구역 경로를 회귀 검증한다.
- [x] AC9. DDL은 새 열 3개·인덱스·테이블·UK와 구 기록 이관을 포함한다. 기존 사건은 migration 취소로 ②가 갑자기 나가지 않는다. ERD 열·관계·인덱스·변경 이력을 갱신한다. 운영 백필의 H2 검증 불가 범위는 PR 비고에 남긴다.
- [x] AC10. Swagger에 새 요청·200 멱등·404/403 순서·409 변경을 적는다. 테스트는 한글 언더스코어 이름·BDDMockito를 쓰고 상태를 우선 검증한다. `./gradlew compileJava`, Domain+API 테스트, `bash scripts/harness/verify.sh`가 통과한다.

운영 MySQL DDL 적용과 백필 검증은 수행하지 않았다. 코디네이터가 PR 비고에 이 범위를 명시하고 배포 전 운영 유사 데이터로 점검한다.

## 8. 영향 범위 / 마이그레이션

LLD-0072 §5.3의 “사건당 첫 건·중복 409·모달 확인은 멈춤 아님”은 이 문서의 guardian-response 계약으로 대체한다. LLD-0077의 ① 수신자와 LLD-0070의 ① 게이트·키셋 폴링은 유지하면서 due만 더한다. W19 비사건 안심구역은 건드리지 않는다. 기존 알림센터 UK가 방장 센터 재사용을 보장하므로 별도 센터 migration은 없다.

DDL을 앱 전환 전에 실행한다. 구 기록은 `guardian_response_type/at_ms/by`가 모두 있는 행만 새 테이블로 이관하고 UK 기준으로 중복 실행을 피한다. 배포 전 사건의 cancelled 시각은 사용자 멈춤이 아니라 migration 억제일 수 있으므로 운영 해석에는 migration 시각과 새 기록 테이블을 함께 본다. 인덱스의 실제 쿼리 계획과 배포 창의 기존 사건 억제는 운영 유사 데이터로 점검한다.

멈춤 API는 검사 후 영속성 컨텍스트를 비우고 사건·동일 종류 멈춤을 잠금 읽기로 다시 조회한다. 이 선택은 기존 API 경로와 `INCIDENT_4092` 미사용 계약을 유지하면서 MySQL `REPEATABLE READ`의 낡은 스냅샷·영속성 컨텍스트 재사용을 함께 막는다. `EntityManager.clear()`는 트랜잭션의 다른 미flush 변경도 분리하므로 이 API는 자체 요청 트랜잭션에서 호출한다. H2 `TransactionTemplate` 회귀 테스트는 선행 사건 조회와 다른 트랜잭션의 멈춤 커밋 뒤 동일 종류 200·원래 시각·한 행, 다른 보호자의 선행 취소 상태를 검증한다. H2는 MySQL의 읽기 스냅샷을 완전히 대변하지 않으므로 운영 검증에서 MySQL 반복 읽기도 확인한다.

①의 `escalateIfDue(id, nowMs)`도 폴링 시작 시각을 사건별 처리에 넘기는 패턴이다. 이는 LLD-0070 범위라 W18에서는 유지하고 별도 후속 검토 대상으로 남긴다.

## 9. 미결정 사항 (Open Questions)

정본 §4의 B2·B3·B4는 확정 답변이 아니다. 아래 **W18 구현 가정**은 ADR-0039(Proposed) 결정 2·3과 일치하며, 코디네이터 게이트에서 대조한다. 회신이 다르면 구현 전 이 문서를 고친다.

| 항목 | W18 가정 / 후속 확인 |
| --- | --- |
| B2 | ① enqueue 시각부터 180000ms. provider가 계속 실패해도 ② 마감을 유지한다. 정본의 `DELIVERY_FAILED`는 전달 실패 표시명이고 현재 outbox DB 상태는 `EXHAUSTED`·`EXPIRED`다. 새 후속 발송은 없다. |
| B3 | 기존 DB 스케줄러·키셋 조회. 직전 사건 상태·종료 시각·마지막 감지 + 5분 창·멈춤 0건·현재 가족·방장·활성 수신자를 다시 읽는다. 푸시 설정은 공통 `FcmEligibility`가 판정한다. 사건 행 잠금과 sent/cancelled 조건부 UPDATE로 승자를 정한다. |
| B4 | 새 경로 없이 guardian-response를 확장한다. `ACKNOWLEDGED`, 사건×보호자×종류 UK를 쓰고 기존 요청 필드 `type`을 유지한다. 정본 §1-A6은 필드 이름을 정하지 않는다. |
| 모달 `확인` 읽음 | FE가 기존 `PATCH /api/v1/notifications/{id}/read`를 별도 호출한다. 읽음만으로는 ②가 멈추지 않는다. |
| 끝난 사건 ACK | W20 상태 조회와 함께 처리한다. 이번에는 기존 가족·보호자 계정 활성 검사만 유지한다. |
| 낙상 | 이번 2단계 알림 범위 밖이며 별도 정책 결정이 필요하다. |

## 10. 참고

- 저장소 밖 정본 `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A3·A5·A6·A14 인수 3·4, §4 「1·2」.
- 저장소 밖 분석 `apiDocs/notification/BE-GAP-2026-10-05.md` §3 #2·#3, §4 B2~B4; 계획 `apiDocs/notification/BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11 W18.
- [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0077](LLD-0077-initial-alert-leader-only.md), [LLD-0078](LLD-0078-safe-zone-notice-not-incident.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
