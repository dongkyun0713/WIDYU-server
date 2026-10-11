# LLD-0073: 종료 후 질문 카드와 시니어 원답

> **안심구역 `SAFE_ZONE_V1` 발급·질문은 [LLD-0078](LLD-0078-safe-zone-notice-not-incident.md)이 제거한다.** 심박·낙상 카드 계약은 유지한다.

> Low-Level Design. 이 문서는 이슈 #716 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-02 구현·검증 완료, 코디네이터 검수 대기) |
| Issue | #716 |
| 관련 ADR | [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) 결정 2·4·6·7 |
| 선행 | LLD-0072(W11b), LLD-0070, LLD-0054 |
| 작성자 | Codex / feature/716 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

응급 순간의 `OK`는 운영 응답이며 실제 사건이나 도움 필요 여부의 정답이 아니다. `OK_CLOSED` 직후 시니어에게 비차단 후속 질문 카드를 발급하고 Q1~Q3 원답과 단말·서버 시각을 별도로 보존한다. `sensor.followup.enabled=false`를 기본값으로 머지하여 활성화 전 기존 사건 흐름에 영향을 주지 않는다.

## 2. 범위

### In scope

- `widyu-domain`: `com.widyu.followup.FollowupCard`·`FollowupAnswer`와 상태·원답 enum.
- `widyu-api`: `IncidentService.respond`의 OK 종료 분기에서 발급, 시니어 API 3개와 DTO·Repository·Swagger, 만료 스케줄러와 `sensor.followup.enabled` 설정·테스트.
- 두 신규 테이블의 운영 DDL과 [ERD-0001](../erd/ERD-0001-initial-domain.md) 갱신. 본인확인의 `device_responded_at_ms`는 LLD-0070 계약을 유지한다.

### Out of scope

- 10p 적립·원장·보상 재시도(W15/LLD-0075), 연구 export·동의·라벨 자동 판정(LLD-0074), 원답 수정·대리 응답.
- `RESOLVED` 사건 발급과 새 안전 사건 발생 시 미제출 초안 숨김·복원은 후속 계약이다. 이번 발급 조건은 모든 `OK_CLOSED` 사건이다.
- OS 푸시·알림센터 행·모달·전화. `FcmCategory`와 `NotificationType` 값은 추가하지 않는다. 카드는 앱 내 조회 화면에서만 노출한다.

## 3. 인터페이스 / API

세 경로는 인증된 **시니어 본인**만 호출한다. `{id}`는 카드 PK다. 클라이언트는 앱 방문 시작 때 UUID `visitKey`를 생성하고 방문 중 재조회에 재사용한다. 응답 DTO는 `from()`/`of()` 팩토리로 만든다.

| 메서드·경로 | 요청 | 성공 `data` |
| --- | --- | --- |
| `GET /api/v1/followups/current?visitKey={uuid}` | 본인 토큰·방문 키 | `card` 객체 또는 `null`, `serverTimeMs` |
| `POST /api/v1/followups/{id}/answers` | `questionSetVersion`, `q1`, `q2`, 선택 `q3`, `deviceSubmittedAtMs` | 첫 원답·`serverReceivedAtMs`, `state=ANSWERED` |
| `POST /api/v1/followups/{id}/decline` | `deviceSubmittedAtMs` | `state=DECLINED`, 서버 접수 시각 |

GET 카드에는 `id`, `incidentRef`, `questionSetVersion`, `eventAtMs`, `issuedAtMs`, `expiresAtMs`, `state`, 문항·허용 응답을 싣는다. 안전 확인 화면에서는 호출하지 않는다. 머리말은 A §2의 “아까 [시각] 상황을 알려주세요. 기억나지 않거나 답하고 싶지 않으셔도 괜찮아요.”를 사건 개시 시각으로 채운다. Q1은 심박 `HR_DISCOMFORT`(불편함, `HR_V1`), 낙상 `FALL_OCCURRED`(넘어짐, `FALL_V1`), 안심구역 `SAFE_ZONE_OUTSIDE`(구역 밖 위치, `SAFE_ZONE_V1`)로 사건 종류에 따라 정한다. 안심구역 문항은 A §2의 확장 후보 문구를 이 LLD의 구현 판본으로 고정한다. Q2는 도움 필요 여부다. Q1 값은 `YES|NO|DONT_KNOW|REFUSE`, Q2는 `NEEDED|NOT_NEEDED|DONT_KNOW|REFUSE`. 선택 Q3은 `EXERCISE|POSTURE_CHANGE|LOOSE_WATCH|DIZZINESS|FELL_AND_ROSE_ALONE|OTHER|DONT_KNOW`에서 최대 2개이며 자유서술은 없다. Q1/Q2의 모름·거절, 카드 전체 `DECLINED`, 미제출 `EXPIRED_NO_RESPONSE`는 서로 다르다.

```json
{"questionSetVersion":"HR_V1","q1":"DONT_KNOW","q2":"NOT_NEEDED","q3":["EXERCISE"],"deviceSubmittedAtMs":1790900000000}
```

성공은 기존 `ApiResponseTemplate`의 `code`·`message`·`data`·`traceId` 래퍼로 반환한다. Docs 성공 코드는 GET `FOLLOWUP_2001`, 답변 `FOLLOWUP_2002`, 전체 거절 `FOLLOWUP_2003`이다. OFF일 때 세 경로는 404 `FOLLOWUP_4040`이다. ON이고 자격 있는 카드가 없으면 GET의 `card`는 null이다. Spring 바인딩 오류는 기존 `REQ_4000`을 사용하고, 서비스 검증 오류 코드 `FOLLOWUP_4000/4040/4090/4100`은 `widyu-domain`의 `ErrorCode`에 둔다.

## 4. 데이터 모델

엔티티는 `widyu-domain`, Repository·Service·Controller·DTO는 `widyu-api`에 둔다. 시간 열은 UTC epoch milliseconds다. 모든 enum 열에 `@Enumerated(STRING)`과 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 선언하며 운영 DDL도 `VARCHAR`다.

| 테이블·열 | 제약·의미 |
| --- | --- |
| `followup_card.id` | `BIGINT` PK |
| `incident_ref` | `VARCHAR(40) NOT NULL`, UK. `incident.incident_ref`와 연결하며 사건당 최대 1건 |
| `senior_id` | `BIGINT NOT NULL`, 사건 `member_id` 스냅샷이자 본인 접근 조건 |
| `issued_at_ms`, `expires_at_ms` | `BIGINT NOT NULL`, 발급 서버 시각과 정확히 +12시간 |
| `event_at_ms` | `BIGINT NOT NULL`, `incident.opened_at_ms` 복사. 질문 머리말의 상황 시각 |
| `state` | `VARCHAR(32) NOT NULL`: `ISSUED|ANSWERED|DECLINED|EXPIRED_NO_RESPONSE` |
| `question_set_version` | `VARCHAR(32) NOT NULL`, 발급 시 사건 종류에 맞춰 고정한 문항 판본 |
| `visit_key` | `VARCHAR(36) NULL`, 처음 선택한 앱 방문 키. `UK(senior_id,visit_key)`; MySQL의 NULL 중복 허용 |
| `followup_answer.id`, `card_id` | PK·`UK(card_id)` FK. 카드당 첫 제출 한 건(답변 또는 전체 거절) |
| `question_set_version` | `VARCHAR(32) NOT NULL`, 카드의 발급 판본을 복사 |
| `q1`, `q2`, `q3` | Q1/Q2 enum 원값 `VARCHAR(32) NULL`; Q3 순서 보존 표현 `VARCHAR(128) NULL`. `ANSWERED`의 Q1/Q2는 필수이고 모름·거절을 NULL로 바꾸지 않음. `DECLINED`이면 세 칸 모두 NULL |
| `device_submitted_at_ms`, `server_received_at_ms` | `BIGINT NOT NULL`, 단말 원시 시각과 서버 접수 시각 |

`idx_followup_card_senior_state_expiry(senior_id,state,expires_at_ms)`와 `idx_followup_card_expiry(state,expires_at_ms,id)`를 둔다. `UK(card_id)`와 조건부 상태 UPDATE로 첫 제출을 보장한다. 구현 PR에서 `scripts/mysql/create_followup_card.sql`에 두 테이블·FK·인덱스를 담고 ERD 관계·마이그레이션 이력을 갱신한다.

## 5. 처리 흐름

### 5.1 발급·노출·만료

1. `IncidentService.respond`는 LLD-0072대로 조건부 응답 UPDATE 후 사건을 재조회한다. 요청이 `OK`이고 재조회 `state=OK_CLOSED`일 때 **같은 트랜잭션**에서 `FollowupCardService.issueIfEnabled(incident)`를 한 줄 호출한다. 기존 S08/S09 조립·enqueue는 수정하지 않는다. 발급과 알림 중 하나가 실패하면 사건 응답까지 함께 롤백한다.
2. ON이면 `incident_ref` UK로 기존 발급을 막고 `event_at_ms=incident.opened_at_ms`, `issued_at_ms=serverNow`, `expires_at_ms=issued+43_200_000`, `state=ISSUED`, 사건 종류에 맞는 `question_set_version`으로 한 건을 만든다. 늦은 OK(`ESCALATED`), HELP, 중복 응답, 보호자 반응에서는 만들지 않는다.
3. GET은 같은 시니어·`visitKey`에 이미 배정된 카드가 있으면 그 카드만 조회하고, 제출·만료된 카드면 null을 반환한다. 없으면 만료 전 `ISSUED AND visit_key IS NULL` 중 가장 오래된 카드의 ID를 후보로 삼아 `id`·`senior_id`·`state=ISSUED`·`visit_key IS NULL`·`expires_at_ms>now`를 모두 확인하는 조건부 UPDATE로 배정한다. UPDATE가 1건이면 방문 키로 재조회한 현재 상태가 유효할 때만 반환한다. 0건이면 경합에서 진 것이므로 같은 방문 키로 이미 배정된 카드가 있는지 한 번만 재조회하고, 없거나 제출·만료된 카드면 null을 반환한다. 방문 키 배정에 엔티티 더티 체킹을 쓰지 않는다. 따라서 한 방문에는 최대 한 카드만 노출된다. 이번 판본은 한 카드의 앱 재방문 재노출을 하지 않는다. 발급된 카드는 12시간 동안 제출할 수 있으며 방문에서 못 본 카드는 발급 시각 기준으로 만료된다.
4. 만료 스케줄러는 OFF면 후보 조회·상태 변경 없이 반환한다. ON이면 키셋 배치로 `state=ISSUED AND expires_at_ms<=serverNow`를 읽고 조건부 UPDATE로 `EXPIRED_NO_RESPONSE`로 바꾼다. 응답과 경합하면 먼저 확정된 전이만 반영하며 원답을 삭제하지 않는다. 만료는 음성 라벨이 아니다.

### 5.2 첫 제출·거절

1. POST는 플래그, 인증 시니어와 카드의 `senior_id`, 발급 판본과 허용 응답을 검증한다. 다른 사람의 카드와 없는 카드는 같은 404다.
2. 답변은 서버 접수 시각을 찍고 카드 `ISSUED→ANSWERED` 조건부 전이와 `FollowupAnswer` 삽입을 한 트랜잭션으로 묶는다. 전체 거절은 `ISSUED→DECLINED` 전이와 Q1~Q3가 NULL인 제출 행 삽입을 같은 트랜잭션으로 묶어 두 시각을 남긴다. 만료 전 제출 근거가 있는 첫 요청은 3번 규칙에 따라 `EXPIRED_NO_RESPONSE`에서도 전이한다. 답변·거절 경합의 패자는 409이고 첫 값은 유지한다.
3. 서버가 만료 전 받은 요청은 수락한다. 서버가 만료 뒤 받은 오프라인 첫 답변·전체 거절도 `deviceSubmittedAtMs < expiresAtMs`이면 수락한다(A §5 확정). 스케줄러가 먼저 `EXPIRED_NO_RESPONSE`로 바꿨더라도 제출 행이 없으면 요청에 따라 `ANSWERED` 또는 `DECLINED`로 전이시킨다. 단말 시각은 원값으로, 서버 접수 시각은 별도로 보존한다. 단말 시각이 없거나 만료 이후면 410이다. 단말 시각만으로 W15 보상 적격을 결정하지 않는다.
4. 원답에서 `event_occurrence`·`help_need`를 자동 채우지 않고 포인트도 적립하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | HTTP·코드 / 효과 |
| --- | --- |
| 인증 없음 | 기존 401; 저장 없음 |
| 플래그 OFF, 카드 없음·타인 카드·시니어 외 호출 | 404 `FOLLOWUP_4040`; 존재 노출 없음 |
| `visitKey` 파라미터 누락, 본문 JSON 파싱 실패, `q1`/`q2`/`q3` 허용값 밖 리터럴 | 기존 요청 역직렬화·검증 오류 400 `REQ_4000`; 저장 없음 |
| `visitKey` 값이 비었거나 UUID 형식이 아님, 문항 판본 불일치, `q1`/`q2` null, `q3` 조합 규칙 위반, `deviceSubmittedAtMs` 누락·0 이하 | 서비스 검증 오류 400 `FOLLOWUP_4000`; 저장 없음 |
| 이미 답변·거절한 카드 | 409 `FOLLOWUP_4090`; 첫 원답·시각 유지 |
| 만료 이후 단말 제출 또는 만료 전 제출 근거 없음 | 410 `FOLLOWUP_4100`; 원답 저장 없음 |
| DB 실패 | 해당 트랜잭션 전체 롤백. 결합된 발급은 사건 응답도 롤백 |

## 7. 인수조건 (Acceptance Criteria)

- [ ] AC1. 기본 `sensor.followup.enabled=false`에서 OK 종료여도 카드·답변 0건이다. 세 API는 404, 만료 스케줄러는 조회·UPDATE 0회다.
- [ ] AC2. ON에서 기한 안 OK가 `OK_CLOSED`가 되면 같은 트랜잭션에서 카드 한 건이 발급되고 `expires_at_ms-issued_at_ms=43_200_000`이다. 중복·늦은 OK·HELP는 0건이다.
- [ ] AC3. 같은 시니어·방문 키의 반복·동시 GET은 한 카드만 반환한다. 그 방문에서 답한 뒤 다음 카드를 반환하지 않고, 다른 방문 키에는 다른 미노출 카드만 배정한다. 타인은 접근할 수 없다.
- [ ] AC4. 만료 시각부터 미제출 카드는 `EXPIRED_NO_RESPONSE`가 된다. OFF는 조용히 0건이며 만료를 원답·라벨로 바꾸지 않는다.
- [ ] AC5. Q1/Q2의 모름·거절을 포함한 원값, 선택 Q3 최대 2개, 발급·제출 문항 판본, 단말 제출 시각과 서버 접수 시각을 저장한다. 건너뛰기는 `DECLINED`와 두 제출 시각을 남기고 앱 종료만 한 경우는 미제출이다.
- [ ] AC6. 첫 제출만 저장하고 재제출·동시 요청의 패자는 409다. 만료 전 단말 제출이 만료 뒤 도착한 첫 답변은 수락하고 만료 이후 제출은 410이다.
- [ ] AC7. Swagger에 세 API·값 집합·주요 오류를 반영한다. DDL·ERD를 갱신하고 `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과한다. H2 통과를 MySQL ENUM 검증으로 보지 않는다.

## 8. 영향 범위 / 마이그레이션

신규 테이블 2개만 추가한다. `incident`, FCM 테이블과 레거시 `IncidentOutcome`은 변경하지 않는다. `scripts/mysql/create_followup_card.sql`은 W11b DDL(`alter_incident_ok_notice_grouping.sql`) 뒤·새 앱 배포 전 적용한다. 플래그는 기존 `SensorProperties.followup().enabled()` 한 곳에서만 읽는다. `application-sensor.yml`의 `sensor.followup.enabled` 기본값과 record boolean 기본값은 `false`다. 카드 발급·세 API·만료 스케줄러는 OFF면 DB 접근 없이 반환한다(API는 404). 방문 키 배정은 조건부 UPDATE 뒤 재조회하며 엔티티 더티 체킹을 금지한다. H2 영속성 테스트에서 후보 조회 뒤 제출을 먼저 커밋해 배정 0건과 `ANSWERED` 유지 여부를 확인한다. FE의 카드·방문 키·오프라인 시각 지원과 연구계획·IRB 기재 확인 뒤 활성화한다. 포인트는 W15의 별도 플래그다.

## 9. 미결정 사항 (Open Questions)

오프라인 단말 시각의 신뢰도와 10p 고지 시점·IRB 기재 확인은 A §5의 후속 결정이다. 이 LLD는 두 시각을 보존하고 늦은 첫 원답을 받되 보상 적격을 판단하지 않는다. `RESOLVED` 발급 정책은 이번에 결정하지 않고 카드도 발급하지 않는다.

## 10. 참고

- A-LABEL-QUALITY-SPEC-v0.1 §1·§2·§4.1·§5, RECONCILIATION §2·§6 P1/P2, 코드 변경점 표 A1·A2·A6.
- [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md) §5.1, [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0054](LLD-0054-incident.md).
