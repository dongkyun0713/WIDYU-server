# LLD-0082: 시니어 응답 시도 이력과 상황 상태 조회

> 이 문서는 이슈 #740(W20)의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11, 구현·검증 완료; 코디네이터 검수 대기) |
| Issue | #740 (base W18 `feature/739`) |
| 관련 ADR | ADR-0039 결정 6·7 (Proposed, 별도 PR #734; 현재 브랜치에 없음), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) |
| 선행 | [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0077](LLD-0077-initial-alert-leader-only.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md) |
| 작성자 | Codex / feature-740 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

현재 `incident`에는 조건부 UPDATE가 반영한 시니어 응답 한 건만 남는다. 폰·워치의 두 번째 응답이나 마감 뒤 응답의 값·기기·시각을 확인할 수 없고, 보호자는 마지막 감지 뒤 5분이 지나도 저장 상태만으로 S12 「이미 완료된 알림」을 분기할 수 없다. 응답 시도마다 이력을 남기고 상황 종료 여부를 조회 시점에 계산한다.

## 2. 범위

### In scope

- `widyu-domain`: `IncidentResponseAttempt`, `ResponseAttemptOutcome` 신규. `Incident` 열 추가 없음.
- `widyu-api`: 기존 응답 API의 시도 기록, 보호자 상태 조회 서비스·DTO·Controller·Swagger, 끝난 뒤 늦은 `ACKNOWLEDGED` 응답 보강, 테스트.
- 구현 단계에서 `scripts/mysql/create_incident_response_attempt.sql`과 [ERD-0001](../erd/ERD-0001-initial-domain.md) 갱신. 이 LLD 작업은 문서만 작성한다.

### Out of scope

- 워치의 직접 REST 호출 또는 폰 대리 전송 방식 결정, 워치 도움 요청(W21), 낙상 정책 변경.
- 응답 시도 이력 조회·삭제 API, 알림 목록 상태 필드, FE 화면·읽음 처리, 공통 FCM 코드 변경.

## 3. 인터페이스 / API 계약

### 3.1 시니어 응답 시도

기존 `POST /api/v1/incidents/{incidentId}/response`의 요청·성공 DTO·중복 409 `INCIDENT_4090`은 유지한다. 요청은 `response=OK|HELP`, `via=PHONE|WATCH`, `deviceRespondedAtMs`(nullable, epoch ms)다. 서버가 `receivedAtMs`를 한 번 찍는다. 인증한 시니어의 **자기 사건에 들어온 유효한 respond 호출마다** `incident_response_attempt` 한 행을 남긴다. 다른 회원·없는 사건은 기존 404이고 인증·입력 검증 실패는 사건에 귀속할 시도가 없어 기록하지 않는다.

| 조건부 `IncidentRepository.respond` | 추가 판정 | 이력 outcome | 기존 HTTP·사건 효과 |
| --- | --- | --- | --- |
| 1건 갱신 | 서버 수신 시각 `< respondByMs` | `ACCEPTED` | 200, 기존 OK/HELP 전이 |
| 1건 갱신 | 서버 수신 시각 `>= respondByMs` | `LATE` | 200, 첫 응답을 저장하되 `ESCALATED` 유지 |
| 0건 갱신 | 기존 `response` 있음 | `DUPLICATE` | 409 `INCIDENT_4090`, 첫 응답 불변 |
| 0건 갱신 | 기존 응답 없이 `RESOLVED` 등으로 갱신 불가 | `LATE` | 기존 409 `INCIDENT_4090`, 종료 상태 불변 |

0건이면 선행 `findOwned`의 관리 객체와 MySQL 반복 읽기의 일반 SELECT 스냅샷을 믿지 않고 `findByIdForUpdate` 잠금 읽기로 최신 사건을 분류한다. 정확히 마감 시각의 응답도 `LATE`다. 단말 시각은 소급 수락에 쓰지 않는다. 이력의 `LATE`는 서버 수신이 마감 뒤이거나 이미 종료된 첫 시도이며, 기존 사건 행에 늦은 응답이 반영될 수 있는 동작과 구별한다. `via`는 요청을 전송한 기기가 아니라 시니어가 누른 기기다(B7 가정).

### 3.2 보호자 상황 상태

```http
GET /api/v1/incidents/{incidentRef}/status
Authorization: Bearer <guardian access token>
```

서비스가 사건을 찾아 시니어 ID를 얻고 W18 `guardian-response`와 같은 순서로 권한을 검사한다. 사건 없음·시니어 요청·타 가족은 404 `INCIDENT_4040`, 같은 가족의 비활성 보호자는 403 `AUTH_4030`이다. 경로에 `seniorId`가 없으므로 목록 API의 `@ValidateFamilyAccess(memberIdParam="seniorId")`를 그대로 붙이지 않고 서비스의 명시적 가족 검사를 재사용한다. 성공 코드 문자열은 `INCIDENT_2006`, 래퍼는 기존 `ApiResponseTemplate.data`다.

```json
{
  "code": "INCIDENT_2006",
  "message": "인시던트 상황 상태 조회 완료",
  "data": {
    "incidentId": "inc-0123456789abcdef0123456789abcdef",
    "status": "OPEN",
    "endedAtMs": null,
    "endedReason": null,
    "kind": "HR_ANOMALY",
    "state": "ESCALATED",
    "lastDetectedAtMs": 1791700000000,
    "secondAlertSentAtMs": null,
    "myResponse": ["ACKNOWLEDGED"]
  },
  "traceId": "example-trace-id"
}
```

`status=OPEN|ENDED`; OPEN의 `endedAtMs`·`endedReason`은 null이다. `endedReason=SENIOR_OK|RESOLVED|WINDOW_ELAPSED|null`은 W20 가정이다. `kind`·`state`는 저장된 값을 그대로 보낸다. 계산된 `status=ENDED`여도 `state=ESCALATED`일 수 있다. `lastDetectedAtMs`는 `Incident.lastDetectedAtOrOpenedAt()`으로 구하므로 구 행에서도 null이 아니다. `secondAlertSentAtMs`는 W18의 ② enqueue 게이트 시각이며 nullable이다. `myResponse`는 해당 보호자의 `incident_guardian_response.response_type` 목록이고, 없으면 `[]`이다. 저장소에서 `(incident_id, guardian_member_id)`로 읽어 `ACKNOWLEDGED`, `MESSAGE_SENT`, `CALL_INITIATED` 순으로 반환한다. 다른 보호자의 기록·시각·ID를 노출하지 않는다.

ENDED 판정은 `situation_ended_at_ms != null` **또는** `state=OK_CLOSED|RESOLVED` **또는** `nowMs >= lastDetectedAtOrOpenedAt() + situationWindowMin * 60000`이다. 시각 경계는 정확히 5분에 닫힌다. 저장 종료 시각이 있으면 `endedAtMs`는 그 값이다. 없고 OK_CLOSED이면 `respondedAtMs`, RESOLVED이면 `resolvedAtMs`, 시간 창 만료이면 마지막 감지+창을 쓴다. 이유는 OK_CLOSED → `SENIOR_OK`, RESOLVED → `RESOLVED`, 그 외 저장 종료 또는 창 만료 → `WINDOW_ELAPSED` 순이다. 오래된 불일치 행에서 종료 상태의 응답·판정 시각도 null이면 `openedAtMs`를 마지막 대체값으로 쓰고 운영 진단 대상으로 남긴다. 다른 보호자의 멈춤은 ENDED가 아니다. S12의 `확인`은 FE가 멈춤 API를 호출하지 않는다.

### 3.3 끝난 뒤 늦은 보호자 확인

기존 `POST /api/v1/incidents/{incidentRef}/guardian-response`에 `type=ACKNOWLEDGED`가 늦게 도착해도 같은 가족의 **활성** 보호자이면 200 `INCIDENT_2005`로 저장한다. `GuardianResponseResult`에 `incidentEnded: boolean`을 추가해 같은 종료 판정을 표시한다. 같은 종류 재호출은 W18대로 원래 기록 시각의 200이다. 끝난 사건에는 ② 취소 UPDATE가 0건이다. 현재 W18 코드의 403은 `guardian.getStatus()!=ACTIVE`에만 걸려 있고 종료에 대한 403은 없다. 따라서 활성 검사를 유지하면서 200 저장을 택한다. 기존 `secondAlertCancelled`는 실제 취소 여부만 뜻하며 종료 여부로 바꾸지 않는다.

## 4. 데이터 모델과 구현 설계

| 위치 | 항목 | 타입·의미 |
| --- | --- | --- |
| `incident_response_attempt` / `IncidentResponseAttempt` (`widyu-domain`) | `id`, `incident_id` | `BIGINT` PK·FK, 사건당 여러 시도 |
| 동일 | `response` | `VARCHAR(8) NOT NULL`, 기존 `IncidentResponseValue.OK|HELP` |
| 동일 | `response_via` | `VARCHAR(16) NOT NULL`, 기존 `ResponseVia.PHONE|WATCH` |
| 동일 | `device_responded_at_ms` | `BIGINT NULL`, 단말 시각 원문 |
| 동일 | `received_at_ms` | `BIGINT NOT NULL`, 서버 수신·마감 기준 |
| 동일 | `outcome` | `VARCHAR(16) NOT NULL`, 새 `ResponseAttemptOutcome.ACCEPTED|LATE|DUPLICATE` |
| 동일 | `created_at`, `updated_at` | `DATETIME(6) NOT NULL`, `BaseTimeEntity` 감사 열 |

세 enum 열에는 `@Enumerated(STRING)`와 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 쓴다. 엔티티는 `IncidentResponseAttempt.of(...)` 팩토리를 제공한다. 저장소는 `widyu-api`의 `IncidentResponseAttemptRepository`다. DDL `scripts/mysql/create_incident_response_attempt.sql`에 FK와 `idx_incident_response_attempt_incident(incident_id)`를 둔다. 기존 단일 응답은 시도별 원시 시각·기기가 없어 백필하지 않는다. MySQL native ENUM과 `Incident` 열은 변경하지 않는다. 구현 시 ERD에 테이블·Incident 1:N 관계·enum·인덱스·마이그레이션 이력을 더한다.

`IncidentService.respond`는 별도 빈 `IncidentRespondTransaction.execute`(`REQUIRES_NEW`)에서 자기 사건 확인·조건부 UPDATE·수락 시도 INSERT·기존 후속 카드/S08을 한 트랜잭션으로 수행한다. `receivedAtMs`는 그 안에서 한 번 얻는다. 1건 갱신 뒤 후속 작업이 실패하면 사건 UPDATE와 이력도 함께 롤백된다. 0건이면 `findByIdForUpdate` 잠금 읽기로 최신 응답을 판정하고 거절 결과를 반환해 **사건 트랜잭션을 먼저 종료**한다. 그 뒤 별도 빈 `IncidentRejectedAttemptRecorder.record`(`REQUIRES_NEW`)에서 409 시도를 INSERT하고 `BusinessException(INCIDENT_ALREADY_ANSWERED)`을 던진다. 부모 사건 행의 잠금을 가진 채 새 FK 이력을 INSERT하면 두 트랜잭션 사이에 잠금 대기가 생길 수 있어 순서를 분리한다. 같은 빈 내부 호출은 프록시를 우회하므로 쓰지 않는다. INSERT 실패는 오류로 전파한다. 기존 `response IS NULL`·`state <> RESOLVED` 조건과 상태 전이는 유지한다.

상태 조회는 `@Transactional(readOnly=true)` 서비스 메서드와 `IncidentStatusResponse.from(incident, nowMs, windowMs, myResponses)` 팩토리를 쓴다. 종료 판정은 이 팩토리의 공통 계산으로 만들어 `recordGuardianResponse`의 `incidentEnded`에도 재사용한다. 조회는 DB를 UPDATE하지 않는다. `attachOrOpen`은 새 감지가 올 때만 지난 창의 `situation_ended_at_ms`를 기록한다. 따라서 새 감지 없이 5분이 지난 사건은 조회에서만 ENDED이고 DB는 `ESCALATED`·종료 열 NULL일 수 있다.

`recordGuardianResponse`는 W18의 사건 잠금·멈춤 UK·조건부 취소 흐름을 유지한다. 끝난 사건에도 `ACKNOWLEDGED` INSERT를 허용한다. 계산상 5분이 지났지만 DB 종료 열이 NULL일 때에도 취소 UPDATE가 0건이 되도록 `cancelSecondAlertIfPending(id, nowMs, windowMs)`에 상태·종료 열·마지막 감지 창 조건을 넣는다. 사건 행 잠금 아래에서 판단하고 W18의 ②와 멈춤 경합을 유지한다.

## 5. 처리 흐름

1. 시니어 응답: `IncidentRespondTransaction`에서 인증·요청 검증 뒤 자기 사건 확인(404 보호) → 서버 시각 → 조건부 UPDATE → outcome 결정 → 수락 시도 INSERT·기존 후속 처리 → 사건 트랜잭션 종료. 0건이면 잠금 읽기로 분류해 결과만 반환한 뒤 `IncidentRejectedAttemptRecorder`가 409 시도를 새 트랜잭션에 INSERT하고 409를 던진다.
2. 상태 조회: 인증 보호자 → 사건 조회 → 가족 404·활성 403 검사 → **본인** 멈춤 종류 조회 → `IncidentStatusResponse.from(...)`에서 저장 종료/상태 종료/5분 창 계산 → 200. 다른 보호자 기록은 계산에도 출력에도 쓰지 않는다.
3. 늦은 `ACKNOWLEDGED`: 가족·활성 검사 → 사건 행 잠금 → 중복 확인/멈춤 INSERT → 종료 판정 → 미발송 ② 조건부 취소(종료면 0건) → `incidentEnded=true` 200. S12의 정상 `확인`은 API 호출이 없으며 지연된 S11 요청만 이 경로를 쓴다.

## 6. 예외 / 오류 처리

| 조건 | HTTP·코드 / 기록 |
| --- | --- |
| 시니어 응답 입력 누락·잘못된 enum | 기존 400 `REQ_4000`, 시도 행 없음 |
| 시니어 응답 사건 없음·타인 사건 | 기존 404 `INCIDENT_4040`, 시도 행 없음 |
| 중복 또는 종료되어 UPDATE 0건 | 기존 409 `INCIDENT_4090`, `DUPLICATE` 또는 `LATE` 행 |
| 상태 조회 사건 없음·시니어·타 가족 | 404 `INCIDENT_4040`, 존재 노출 없음 |
| 같은 가족의 비활성 보호자 | 403 `AUTH_4030`, 조회·멈춤 기록 거부 |
| 끝난 사건의 활성 보호자 ACK | 200 `INCIDENT_2005`, 멈춤 저장, `incidentEnded=true`, ② 취소 0건 |

새 `ErrorCode`는 필요 없다. 필요해지면 `backend/widyu-domain/src/main/java/com/widyu/global/error/ErrorCode.java`의 `INCIDENT_*` 블록에 추가하고 계약을 갱신한다. `INCIDENT_2006`은 성공 응답 코드 문자열이다. Swagger `IncidentDocs`에 GET의 200·403·404, respond의 시도 저장·409, 늦은 ACK의 200을 명시한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 첫 마감 전 OK는 `OK_CLOSED`와 `ACCEPTED` 이력 한 행을 만들고 response/via/단말·서버 시각을 보존한다. 기존 S08·후속 카드 흐름이 유지된다.
- [x] AC2. 두 번째 기기 OK는 409와 `DUPLICATE` 한 행이다. 첫 응답·경로·시각은 바뀌지 않으며 폰·워치 동시 경합에서도 사건에 반영되는 응답은 한 건이다.
- [x] AC3. 마감과 같은 시각 또는 뒤의 첫 OK는 `LATE` 한 행, 사건 응답 저장, `ESCALATED` 유지, S08 0건이다. 단말 시각이 마감 전이어도 같다.
- [x] AC4. 마지막 감지 뒤 5분 미만의 열린 사건 상태는 `OPEN`, `endedAtMs=null`, `endedReason=null`이다. `myResponse`는 본인 멈춤 종류만 담는다.
- [x] AC5. `OK_CLOSED`는 `ENDED/SENIOR_OK`, `RESOLVED`는 `ENDED/RESOLVED`, 정확히 마지막 감지+5분은 `ENDED/WINDOW_ELAPSED`다. 5분 계산 응답의 `endedAtMs`는 마지막 감지+창이며 DB `state`·`situation_ended_at_ms`는 바뀌지 않는다.
- [x] AC6. 타 가족·시니어·없는 사건은 404, 같은 가족의 비활성 보호자는 403이다. 다른 보호자의 멈춤은 `myResponse`에도 종료 판정에도 영향을 주지 않는다.
- [x] AC7. 끝난 사건의 늦은 `ACKNOWLEDGED`는 200 `incidentEnded=true`, 멈춤 한 행, ② 취소 UPDATE 0건이다. 같은 종류 재호출은 원래 시각의 200이고 가족·활성 검사를 유지한다.
- [x] AC8. HELP·①·②·멈춤 경합의 기존 회귀가 통과한다. 응답 이력은 공통 FCM 코드·센터 행 수를 바꾸지 않는다.
- [x] AC9. Swagger 성공·주요 오류·nullable 필드, DDL·ERD가 일치한다. `./gradlew compileJava`, Domain+API 테스트, `bash scripts/harness/verify.sh`가 통과한다. 테스트는 한글 언더스코어 이름·행위형 `@DisplayName`·BDDMockito와 상태 검증을 쓴다.

## 8. 영향 범위 / 마이그레이션

[LLD-0083](LLD-0083-watch-self-help-request.md)은 워치 HELP가 열린 심박 사건에 부착될 때 첫 응답을 보존하고 기존 응답이 있으면 `DUPLICATE` 이력·200을 남긴다. 도움 요청 취소는 상태 조회에서 `ENDED`/`endedReason=CANCELLED`이며, 열린 도움 요청은 심박 5분 창으로 자동 종료하지 않는다.

앱 전환 전에 새 테이블 DDL을 적용한다. 기존 `incident` ALTER와 native ENUM ALTER는 없다. 과거 사건 응답은 정확한 시도별 시각·기기를 재구성할 수 없어 백필하지 않으며, 배포 뒤 요청부터 이력이 생긴다. `incident_guardian_response`의 W18 이관 결과는 유지한다.

W18의 원래 `cancelSecondAlertIfPending`은 sent/cancelled NULL만 검사해, 5분이 지났지만 `situation_ended_at_ms`가 NULL인 사건에서도 1건을 갱신할 수 있었다. W20은 종료 상태·기록 종료·마지막 감지 5분 창 조건을 추가해 이 경우 0건으로 만든다. W18 스케줄러가 종료 후보의 게이트를 닫는 일은 `closeSecondAlertIfPending`으로 분리해 재조회가 반복되지 않게 한다. W18의 `secondAlertCancelled`에는 배포 억제 시각도 있으므로 `incidentEnded`와 뜻을 섞지 않는다.

## 9. 미결정 사항 (Open Questions)

아래는 확정 정책이 아닌 **W20 구현 가정**이다. 정본·FE 회신이 다르면 구현 전 갱신한다.

| 항목 | W20 가정 / 후속 확인 |
| --- | --- |
| B11 | 별도 `GET /api/v1/incidents/{incidentRef}/status`를 쓴다. 알림 목록 항목에는 상태 필드를 추가하지 않는다. 끝난 뒤 도착한 S11 `ACKNOWLEDGED`는 같은 가족의 활성 보호자에게 200으로 저장하고 `incidentEnded=true`를 돌려준다. S12의 `확인`은 멈춤을 보내지 않는다. |
| B7 | 워치 직접 호출·폰 대리 전송은 FE 합의 대기다. 서버는 기존 응답 API의 `response_via`만 저장한다. 동시 호출은 조건부 UPDATE로 첫 응답 하나만 반영하고 모든 유효 시도를 기록한다. |
| `endedReason` | `SENIOR_OK|RESOLVED|WINDOW_ELAPSED|null`을 제안한다. 정본은 이유 enum·필드명·우선순위를 고정하지 않았다. |
| 5분 종료 계산과 기록 불일치 | 새 감지가 없으면 `attachOrOpen`이 돌지 않아 DB 종료 열은 NULL일 수 있다. 조회는 계산된 ENDED를 반환하고 DB를 보정하지 않는다. DB 열을 직접 읽는 운영·연구 집계 해석은 후속 결정이 필요하다. |
| 낙상 | 이 LLD는 낙상 알림·종료 정책을 바꾸지 않는다. 낙상 사건에 상태 조회를 쓸 때 종료 이유 해석은 FE·제품과 별도 확인한다. |

## 10. 참고

- 저장소 밖 정본 `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A1·A5, §5 B7. B11은 이 추가본 §1-A5에서 질문으로 남고, 상세 선택지는 아래 분석 §4에 있다.
- 저장소 밖 분석 `apiDocs/notification/BE-GAP-2026-10-05.md` §3 #7·#8·§4 B11; 계획 `apiDocs/notification/BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11 W20.
- ADR-0039는 현재 브랜치에 없으며 `feature-734/docs/adr/ADR-0039-two-stage-guardian-alert-and-safe-zone-notice.md`를 읽기 전용으로 참조했다(Proposed, 별도 PR #734). [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
