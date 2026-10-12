# LLD-0083: 워치에서 시니어가 직접 도움 요청

> 이 문서는 이슈 #741(W21)의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11, 구현·검증 완료) |
| Issue | #741 (base develop, W20 #749 머지 뒤) |
| 관련 ADR | ADR-0039 결정 8 (Proposed, PR #750), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) |
| 선행 | [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0077](LLD-0077-initial-alert-leader-only.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md), [LLD-0082](LLD-0082-response-attempts-and-incident-status.md) |
| 작성자 | Codex / feature-741 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

시니어가 워치에서 직접 도움을 요청해도 현재 서버는 판정 없이 사건을 열 전용 경로가 없다. 워치 요청을 독립 사건 또는 진행 중인 심박 사건의 HELP로 기록하고, 60초 본인확인 없이 방장 ①과 3분 뒤 ②를 보내며, 시니어가 취소하면 남은 알림을 중단하고 실제 수신자에게 취소를 알린다.

## 2. 범위

### In scope

- `widyu-domain`: `IncidentKind.SELF_HELP_REQUEST`, `IncidentState.CANCELLED_BY_SENIOR`, `NotificationType.SELF_HELP_REQUESTED`·`SELF_HELP_REQUEST_CANCELLED` enum 값. 기존 `Incident` 열 사용.
- `widyu-api`: 시니어 요청·취소 API/Swagger/DTO, 사건 열기·HELP 부착·취소, ①·② 게이트의 종류별 처리, K01·K02 문구, 실제 수신자 조회, W20 상태 조회 보강, 테스트.
- 구현 단계에서 [ERD-0001](../erd/ERD-0001-initial-domain.md)의 종류·상태와 [LLD-0060](LLD-0060-notification-type-registry.md)의 타입 표를 갱신한다. 선행 LLD-0070·0077·0081의 심박 전용 서술도 이 경로를 반영한다.

### Out of scope

- 워치 3초 길게 누르기 UI, 소리 종류·재생, 워치↔폰 전달 구현, 보호자 모달 문구·화면. 서버는 `foregroundPresentation=MODAL`과 타입을 제공한다.
- 자동 119·전화, 세 번째 푸시, 안심구역 사건 재도입, 심박·낙상 판정 정책, 공통 FCM 전송 코드·`FcmCategory` 값 추가.

## 3. 인터페이스 / API 계약

### 3.1 시니어 도움 요청

```http
POST /api/v1/incidents/help-requests
Authorization: Bearer <senior access token>
Content-Type: application/json

{"deviceRequestedAtMs":1791700000000,"via":"WATCH"}
```

본문은 생략할 수 있다. 보내는 경우 `deviceRequestedAtMs`는 nullable epoch ms, `via`는 `WATCH`만 허용한다. 단말 시각은 멱등 판단·① 마감 기준이 아니다. 서버 수신 시각으로 사건을 연다. 시니어가 아니면 403이다. 신규·열린 요청 재호출·심박 부착은 모두 HTTP 200이고, 응답은 ref·kind·state·`attached`를 `ApiResponseTemplate.data`에 담는다. DTO는 `from()/of()` 팩토리를 쓴다.

```json
{"code":"INCIDENT_2007","message":"도움 요청 접수 완료","data":{"incidentId":"inc-0123456789abcdef0123456789abcdef","kind":"SELF_HELP_REQUEST","state":"OPEN","attached":false},"traceId":"example-trace-id"}
```

| 회원 행 잠금 뒤 사건 | 결과 | 알림·이력 |
| --- | --- | --- |
| 열린 심박 `HR_ANOMALY` (`CHECKING|ESCALATED`, 종료 시각 없음, 마지막 감지 뒤 5분 미만), `response=null` | 기존 사건에 HELP/WATCH를 조건부 기록하고 200 반환, `attached=true` | W20 `ACCEPTED|LATE` 시도 1건. 새 사건·S01 0건; 기존 5초 ① 게이트 사용. |
| 같은 심박 사건, 이미 `response` 있음(늦은 OK 포함) | 첫 응답·상태를 보존하고 같은 사건을 200 반환, `attached=true` | HELP/WATCH `DUPLICATE` 시도 1건. `ESCALATED`의 ①은 발송됐거나 다음 폴링 대상. 새 ① 없음. |
| 열린 심박 없음, 열린 `SELF_HELP_REQUEST` (`OPEN|CHECKING|ESCALATED`, 종료 시각 없음) | 원래 사건 200 반환, `attached=true` | 사건·①·응답 시도 추가 0건. |
| 위 사건 없음 | 새 `SELF_HELP_REQUEST` 200 반환, `attached=false` | S01 0건. ① 게이트 후보. |

이 API의 이미 답한 심박 사건 200은 기존 `POST /{incidentId}/response`의 중복 409 계약을 바꾸지 않는다. `attached`는 새 사건을 만들지 않았다는 뜻이며, 서로 다른 시니어 사건은 공유하지 않는다.

### 3.2 시니어 도움 요청 취소

```http
POST /api/v1/incidents/{incidentRef}/cancel
Authorization: Bearer <senior access token>
```

본문은 없다. 인증 시니어 자신의 열린 `SELF_HELP_REQUEST`만 취소한다. 사건 없음·타 시니어 사건은 404이고, 자기 사건이지만 이미 취소·종료·사후 판정되었거나 종류가 심박·낙상이면 409다. 심박 OK는 기존 respond 경로를 쓴다. 성공은 HTTP 200 `INCIDENT_2008`, `data`에는 사건 ref·`CANCELLED_BY_SENIOR`·`situationEndedAtMs`가 있다. 취소 재호출은 409이고 새 안내는 없다.

### 3.3 보호자 알림

| 타입/문구 | 수신·전달 | 레지스트리·data |
| --- | --- | --- |
| `SELF_HELP_REQUESTED` / K01 `{시니어 이름} 님이 워치로 직접 도움을 요청했어요.`; 본문 null | ① 현재 활성 방장 1명. ② ① 기준 3분 동안 멈춤 없으면 활성 안전 수신 보호자 전원. 방장은 ② 푸시를 다시 받되 센터 행은 재사용. 설정 OFF는 센터 행만. | 기존 `FcmCategory.HEART_MESSAGE`, 센터 `LOCATION`, `PUSH_AND_CENTER`, `CRITICAL`, `MODAL`, `SAFETY`, `HEART_EMERGENCY_180D`, `/location?seniorId={seniorId}`. `eventId=incident_ref`, `deliveryStage=INITIAL_ALERT|SECOND_ALERT`, `safetyEventId=incident_ref`, `relatedMemberId=seniorId`, `emergency=true`. |
| `SELF_HELP_REQUEST_CANCELLED` / K02 `{시니어 이름} 님이 도움 요청을 취소했어요.`; 본문 null | ①·②의 실제 센터 수신자마다 1회. ② 뒤면 양 단계 수신자의 합집합. | 기존 `FcmCategory.HEART_MESSAGE`, 센터 `LOCATION`, `PUSH_AND_CENTER`, `INTERACTION`, `BANNER`, `GENERAL`, `HEART_EMERGENCY_180D`, 같은 위치 딥링크. `eventId=incident_ref:CX`, `entityId=incident_ref`, `relatedMemberId=seniorId`, `emergency=false`. |

취소 수신자는 취소 시점의 가족 전체가 아니라 `fcm_notification.event_id=incident_ref`인 센터 행의 `recipient_member_id` 중복 제거 집합이다. 안전 설정 OFF여도 센터 행이 있으면 실제 수신 대상이다. `event_id VARCHAR(40)`이고 ref가 `inc-`+UUID 32자로 36자이므로 `:CANCEL`(43자) 대신 `:CX`(39자)를 쓴다. FE는 `type`으로 심박 모달과 구분한다. 문구에 「응급 감지」를 쓰지 않는다.

## 4. 데이터 모델과 동시성 설계

`incident.kind/state`는 `@Enumerated(STRING)`+`@JdbcTypeCode(SqlTypes.VARCHAR)` 열이다. `kind VARCHAR(32)`에 새 17자 값은 들어가지만 `state VARCHAR(16)`에는 `CANCELLED_BY_SENIOR` 19자가 들어가지 않는다. 따라서 `incident.state VARCHAR(24) NOT NULL` 확장 DDL, 엔티티 `@Column(length=24)`, ERD 갱신이 **필요하다**. `NotificationType`은 기존 `fcm_notification.type`·`fcm_outbox.notification_type VARCHAR(48)`에 저장한다. 새 테이블·새 열·`FcmCategory` DDL은 없다. `event_id`도 `:CX`로 현 폭을 유지한다. 운영의 실제 열 타입이 VARCHAR인지 적용 전 확인하고 MySQL native ENUM 값 변경 DDL은 작성하지 않는다.

`IncidentService.openHelpRequest(memberId)`는 `@Transactional`에서 `MemberRepository.findByIdForUpdate`로 회원 행을 먼저 잠근다. 시니어 타입·활성 상태를 확인한 뒤 3.1 순서대로 조회한다. 심박은 마지막 감지 뒤 5분 창을 확인하며 창이 지났으면 LLD-0072대로 종료 시각을 채우고 새 도움 요청을 연다. 새 사건은 `decisionId/runId/level=null`, `policyRevision=20261005`, `openedAtMs=서버 시각`, `respondByMs=openedAtMs`, `state=OPEN`이다. 60초 본인확인·S01·S08·후속 카드를 만들지 않는다. 이미 열린 심박에 응답이 없으면 기존 조건부 `respond`와 W20 시도 이력을 같은 트랜잭션에서 사용한다. 응답이 있으면 원래 응답은 보존하고 HELP/WATCH `DUPLICATE` 시도만 저장한다.

①은 **기존 5초 스케줄러 게이트**를 선택한다. `respondByMs=openedAtMs`로 즉시 due이고, `findDueIds`가 찾은 사건의 `claimInitialAlertIfDue`가 1건 바꾼 `REQUIRES_NEW` 트랜잭션만 `enqueueInitialAlert`를 실행한다. 게이트 UPDATE는 `initialAlertSentAtMs=now`와 `secondAlertDueAtMs=now+180000`을 함께 적는다. `self-check-first` 플래그와 무관하며 요청 경로의 `sendImmediately`를 호출하지 않는다. 게이트·재시작 복구·enqueue 실패 재시도를 재사용할 수 있기 때문이다. 정상 5000ms 폴링에서 5초 안에 enqueue한다. 스케줄러 지연이나 provider 장애가 있으면 단말 도착의 5초 보장은 아니다. 60초 무응답 전이 `escalateTimedOutIfDue`는 심박 전용으로 유지한다.

`IncidentRepository.findDueIds`·`claimInitialAlertIfDue`에는 도움 요청의 `OPEN`·즉시 due 분기를 추가하고, 기존 심박 조건을 보존한다. `IncidentEscalation.enqueueInitialAlert`은 kind에 따라 S04/심박 타입 또는 K01/새 타입을 선택하되 방장 선택·outbox enqueue를 공유한다. `findSecondAlertDueIds`·`claimSecondAlertIfDue`·`sendSecondAlertIfDue`의 종류 조건에도 도움 요청을 포함한다. 도움 요청 ②는 상태·종료 시각·멈춤을 확인하며 심박의 마지막 감지 +5분 종료를 적용하지 않는다. W18대로 사건 행 잠금 → due/sent/cancelled·멈춤 확인 → 조건부 claim → 수신자 재검증·enqueue를 같은 트랜잭션에서 수행한다. `eventId=incident_ref`로 방장 센터 행을 재사용한다.

`cancelHelpRequest(memberId, incidentRef)`도 회원 행을 먼저 잠그고 소유 확인 뒤 사건 행을 잠근다. `openHelpRequest`와 같은 잠금 순서로 취소와 연속 요청을 직렬화한다. 다음 조건부 UPDATE **1건**일 때만 성공하며, ② sender와 사건 잠금으로 순서를 정한다.

```sql
UPDATE incident
   SET state = 'CANCELLED_BY_SENIOR', situation_ended_at_ms = :now
 WHERE incident_ref = :incidentRef AND member_id = :memberId
   AND kind = 'SELF_HELP_REQUEST'
   AND state IN ('OPEN', 'CHECKING', 'ESCALATED')
   AND situation_ended_at_ms IS NULL;
```

같은 트랜잭션에서 미발송 ②는 W18의 `closeSecondAlertIfPending` 조건으로 `secondAlertCancelledAtMs`를 기록한다. ① 이전 취소면 ① 게이트가 0건이고 수신자가 없어 안내도 0건이다. ① 뒤에는 `FcmNotificationRepository`에 `eventId` 기준 수신자 조회를 추가해 K02를 enqueue한다. ②가 먼저 claim/enqueue했으면 ② 수신자 센터 행도 대상이고, 취소가 먼저 잠그면 ② claim은 0건이다. `eventId=incident_ref:CX`와 수신자×이벤트 UK가 안내 중복을 막는다. enqueue 실패 시 취소·② 차단·안내 예약을 함께 롤백한다.

W20 `IncidentStatusResponse`는 `CANCELLED_BY_SENIOR`를 `ENDED`, `endedReason=CANCELLED`, `endedAtMs=situationEndedAtMs`로 반환한다. `SELF_HELP_REQUEST`는 취소나 보호자 사후 판정 등 명시적 종료 전까지 5분만으로 `ENDED`가 되지 않는다. `guardian-response`의 미발송 ② 취소 조건도 도움 요청에는 심박 5분 창을 적용하지 않는다. 기존 `resolve`가 이 종류를 판정하면 `situationEndedAtMs`도 기록하되 `CANCELLED_BY_SENIOR`를 사후 판정으로 덮어쓰지 않는다. 기존 `respond` 조건부 UPDATE는 취소 상태를 제외해 시니어가 취소한 사건을 다시 열지 않는다. `findPending`은 본인확인 대상만 반환해 도움 요청을 제외한다.

## 5. 처리 흐름

1. Controller가 인증 회원 ID·선택 요청을 서비스에 넘긴다. 서비스가 타입 검증·회원 행 잠금 뒤 사건을 조회한다. Controller는 Repository에 접근하지 않는다.
2. 열린 심박이면 W20 첫 응답 불변·이력 규칙으로 HELP/WATCH를 부착한다. 열린 도움 요청이면 원래 사건을 반환한다. 둘 다 없으면 새 사건을 커밋한다.
3. `IncidentTimeoutScheduler` 다음 폴링이 ① 게이트를 claim하고, 3분 뒤 ② 폴링이 W18 규칙을 쓴다. 새 `@Async`나 FCM 전송 경로는 없다.
4. 시니어 취소는 조건부 종료, 미발송 ② 취소, 실제 수신자 K02 enqueue를 한 트랜잭션에서 수행한다. 워치 화면을 닫아도 사건은 유지된다.

## 6. 예외 / 에러 처리

| 조건 | HTTP·코드 / 효과 |
| --- | --- |
| 인증 없음 | 기존 인증 401. 사건 변경 없음. |
| 시니어가 아님·비활성 회원 | 403 `AUTH_4030`. 사건 조회 전 차단. |
| `via`가 WATCH 외 값, 단말 시각 형식 오류, 취소 본문 오류 | 400 `INCIDENT_4000` 또는 기존 요청 검증 코드. |
| 취소 사건 없음·타 시니어 소유 | 404 `INCIDENT_4040`. 존재 여부 비공개. |
| 자기 사건이지만 열린 도움 요청이 아님: 이미 취소/종료/판정, 심박·낙상 | 신규 409 `INCIDENT_4093` (`INCIDENT_HELP_REQUEST_NOT_OPEN`). 추가 안내 없음. 심박 OK는 기존 respond 경로. |
| 열린 도움 요청 연속·동시 요청 | 200 `INCIDENT_2007`, 같은 ref, 새 사건·① 0건. |
| 열린 심박 사건의 첫 응답이 이미 있음 | HELP/WATCH `DUPLICATE` 이력, 200 `INCIDENT_2007`. 기존 respond API의 409는 유지. |
| ①·② 수신자 없음 | W17/W18처럼 ref만 WARN, 게이트 기록. 취소 안내는 실제 센터 수신자에게만. |
| enqueue/DB 실패 | 해당 트랜잭션 롤백. 게이트는 다음 폴링에서, 취소 요청은 클라이언트가 재시도. |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 시니어 요청 한 번으로 도움 요청 사건 1건, 방장 ① 센터/outbox 각 1건을 정상 폴링 5초 안에 만들고 S01은 0건이다. `self-check-first` ON/OFF가 같다.
- [x] AC2. 연속·동시 요청은 같은 열린 사건 ref와 200을 반환하고 사건·①은 각각 1건이다.
- [x] AC3. ① 기준 3분 동안 멈춤이 없으면 ②를 안전 알림 수신 대상에게 1회 보내고 방장은 센터 행을 재사용한다. 멈춤이 먼저면 ② 0건이다.
- [x] AC4. ① 뒤 취소는 `CANCELLED_BY_SENIOR`·종료 시각을 기록하고 미발송 ② 0건, ① 수신자 K02 각 1회·센터 `LOCATION` 행을 만든다.
- [x] AC5. ② 뒤 취소는 ①·② 실제 수신자 합집합 전원에게 K02를 각 1회 보내고 이미 보낸 ②를 취소 처리하지 않는다. 취소/② 경합 결과가 센터 수신자와 일치한다.
- [x] AC6. 열린 심박 중 요청은 HELP/WATCH를 부착하고 새 사건 0건이다. 첫 응답이 있으면 보존·`DUPLICATE` 이력·200, 없으면 HELP 기록과 기존 5초 ① 게이트다.
- [x] AC7. 타 시니어 사건 취소 404, 자기 심박·이미 취소된 사건 409, 보호자 요청 403이다. 안내 중복 0건이다.
- [x] AC8. W20 상태 조회는 취소를 `ENDED`/`CANCELLED`로 보여주고 열린 도움 요청은 5분만으로 종료하지 않는다. 본인확인 대기 목록에 도움 요청은 없다.
- [x] AC9. 기존 심박 60초·HELP·S08, ①·② 멈춤/센터 재사용, 낙상, 가족 인가 회귀 테스트가 통과한다. 테스트 메서드는 한글 언더스코어·AAA·BDDMockito를 따른다.
- [x] AC10. 두 API의 성공·400/403/404/409가 `IncidentDocs` Swagger에 반영되고 `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- `IncidentKind`·`IncidentState`·`NotificationType`·`Incident.state` 길이, `IncidentService`·`IncidentEscalation`·`IncidentRepository`·`IncidentController`/`IncidentDocs`/DTO·`NotificationCopy`·FCM 수신자 조회·테스트를 변경했다. `IncidentTimeoutScheduler`의 기존 5초 폴링을 재사용했다. 공통 FCM enqueue/dispatcher와 `FcmCategory`는 유지한다.
- `scripts/mysql/create_incident.sql`의 `state VARCHAR(16)`과 기존 엔티티 매핑 길이 16 때문에 운영 DDL [alter_incident_state_length.sql](../../scripts/mysql/alter_incident_state_length.sql)을 준비했다. 배포 전에 적용해야 한다. `kind VARCHAR(32)`, 타입 `VARCHAR(48)`, `event_id VARCHAR(40)`은 그대로다. ERD와 LLD-0060 타입 표를 동기화했다.
- [LLD-0070](LLD-0070-self-check-first-heart.md)의 ① 심박 전용 조회 설명, [LLD-0077](LLD-0077-initial-alert-leader-only.md)의 방장 ①, [LLD-0081](LLD-0081-second-alert-and-stop-records.md)의 ②·멈춤, [LLD-0082](LLD-0082-response-attempts-and-incident-status.md)의 상태 종료 사유에 도움 요청의 영향 문구를 반영했다.

## 9. 미결정 사항 (Open Questions)

아래는 추가본 §1-A4의 합의 5건과 연동 경계다. **구현을 위한 추천 가정**이며 제품 합의가 바뀌면 계약·테스트를 갱신한다.

- [ ] ⓐ 열린 심박 사건(`CHECKING|ESCALATED`, 상황 미종료) 중 요청은 새 사건 없이 HELP로 부착한다. 첫 응답이 이미 있으면 W20 불변 규칙에 따라 HELP/WATCH `DUPLICATE` 시도만 남기고 200을 반환한다. 기존 ① 게이트가 5초 안에 발송한다.
- [ ] ⓑ 취소 안내는 센터 `LOCATION`에 저장한다(`PUSH_AND_CENTER`, `INTERACTION`, `GENERAL`).
- [ ] ⓒ ② 뒤 취소하면 ①·② 수신자 전원에게 안내한다.
- [ ] ⓓ 모달 문구는 FE 몫이다. 서버는 `foregroundPresentation=MODAL`·타입으로 구분한다.
- [ ] ⓔ 문구에 「응급 감지」를 쓰지 않는다.
- [ ] B7: 워치 직접 REST vs 폰 대리, 취소 전달 경로, 3초 길게 누르기·소리 구현 가능성을 앱 팀과 확정한다. 서버 `via=WATCH`는 원 클릭 기기다.
- [ ] 보존 등급은 현재 `HEART_EMERGENCY_180D`를 추천한다. 정책 승인 시 LLD-0060 표와 함께 확정한다.
- [ ] W20 상태 조회의 `CANCELLED_BY_SENIOR` → `ENDED`/`endedReason=CANCELLED`, 이후 FE 모달 S12·읽음 처리를 확정한다.
- [ ] `eventId` 접미사 `:OK`/`:CX`는 `VARCHAR(40)` 제약에 따른 선택이다. `:CANCEL` 대신 `:CX`를 앱·분석 소비자와 공유한다.
- [ ] `SELF_HELP_REQUEST`/`CANCELLED_BY_SENIOR` 이름과 API 경로는 추가본 제안명이다. ADR-0039 결정 8은 현재 Proposed다.

## 10. 참고

- 저장소 밖 정본: `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A3·§1-A4·§4 「7」, `BE-GAP-2026-10-05.md` §3 #6·#9·§4·§6 W21, `wave2-scratch/W21.md`.
- ADR-0039 결정 8은 별도 `feature/734`의 읽기 전용 참조이며 이 브랜치로 복사·수정하지 않는다.
