# LLD-0054: 인시던트 — 본인확인·무응답 판정·사후 판정

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #662 |
| 관련 ADR | [ADR-0035](../adr/ADR-0035-alert-decision-and-incident.md) 결정 4~7 |
| 작성자 | Claude |
| 작성일 | 2026-09-21 |
| 선행 | #661(LLD-0053). 이 브랜치는 feature/661 위에 쌓는다 |

## 1. 목적 / 배경

`ALERT` 판정 뒤 「본인이 뭐라고 답했는지, 45초 안에 답했는지, 보호자가 사후에 뭐라고 판정했는지, 119에 언제 신고했는지」를 사건 하나로 남긴다. 사후 판정 `outcome`이 실증의 지도학습 라벨이다(형식서 §3.7). 판정 기록(`decision_record`)과는 분리한다(정책서 1.8.1).

## 2. 범위

### In scope
- widyu-api / widyu-domain
- `incident` 테이블·엔티티, 상태기계, 무응답 스케줄러
- `ALERT` 판정 → 인시던트 열기 + 시니어 본인확인 FCM
- API 4개(응답·사후 판정·보호자 조회·시니어 조회)
- 내보내기 `streams/incidents.jsonl`, `streams_absent` 인시던트 신고 변경
- 설정 `sensor.incident.*`

### Out of scope
- 보호자 알림 순서 변경(본인 OK 시 보류 등), 119 자동 신고, 관리자 화면, 앱 화면

## 3. 인터페이스 / API

| 메서드·경로 | 호출자 | 요청 | 응답 |
| --- | --- | --- | --- |
| `POST /api/v1/incidents/{incidentId}/response` | 시니어 본인 | `{"response":"OK\|HELP","via":"WATCH\|PHONE"}` | `IncidentResponse` |
| `POST /api/v1/incidents/{incidentId}/outcome` | 보호자(같은 가족) | `{"outcome":"TRUE_EMERGENCY\|FALSE_ALARM\|UNKNOWN","emergencyCalledAtMs":null}` | `IncidentResponse` |
| `GET /api/v1/incidents?seniorId=&state=` | 보호자(같은 가족) | `state` 선택 | `List<IncidentResponse>` 최근순 50건 |
| `GET /api/v1/incidents/mine/pending` | 시니어 본인 | — | `List<IncidentResponse>` (응답 없는 건, 최근순) |

`IncidentResponse`: `incidentId, decisionId, runId, kind, level, openedAtMs, respondByMs, response, respondedAtMs, responseVia, state, outcome, resolvedBy, resolvedAtMs, emergencyCalledAtMs`. 심박 값·사유·좌표는 넣지 않는다(그건 판정 기록이고 관리자 범위).

보호자 권한: 기존 `@ValidateFamilyAccess`(seniorId 파라미터)로 조회를 막고, `outcome`은 서비스에서 인시던트의 `member_id`를 seniorId로 삼아 같은 검증기(`FamilyAccessAspect`가 쓰는 `FamilyMembershipRepository` 조회)를 호출한다. 시니어 API는 토큰 회원 = 인시던트 `member_id`가 아니면 404(존재를 드러내지 않는다).

본인확인 FCM: 시니어 회원에게 `FcmSendDto`(기존 카테고리 중 심박 위급과 같은 것, `emergency=true`, `scheme`에 `widyu://incident/{incidentId}` 형태로 incidentId 전달. 카테고리 enum에 맞는 값이 없으면 `INCIDENT_SELF_CHECK` 추가). 제목·본문은 「괜찮으세요?」류 고정 문구, 건강값 없음.

## 4. 데이터 모델

`incident` (엔티티 `com.widyu.incident.Incident`, widyu-domain, `BaseTimeEntity`):

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `incident_id` (PK) | BIGINT | | |
| `incident_ref` | VARCHAR(40) | NOT NULL UK | `inc-` + UUID hex. 외부 식별자 |
| `member_id` | BIGINT | NOT NULL | 시니어 |
| `run_id` | VARCHAR(64) | NULL | 판정의 run_id 복사 |
| `decision_id` | VARCHAR(40) | NOT NULL UK | 판정 1 = 인시던트 1 |
| `kind` | VARCHAR(32) | NOT NULL | `HR_ANOMALY` / `FALL_SUSPECTED` (형식서 §3.7 집합) |
| `level` | VARCHAR(20) | NULL | 판정의 `severity` 복사 |
| `opened_at_ms` | BIGINT | NOT NULL | |
| `respond_by_ms` | BIGINT | NOT NULL | `opened_at_ms + self-check-sec×1000` |
| `response` | VARCHAR(8) | NULL | `OK` / `HELP` |
| `responded_at_ms` | BIGINT | NULL | 서버 시각 |
| `response_via` | VARCHAR(16) | NULL | `WATCH` / `PHONE` |
| `state` | VARCHAR(16) | NOT NULL | `OPEN` / `CHECKING` / `OK_CLOSED` / `ESCALATED` / `RESOLVED` |
| `outcome` | VARCHAR(20) | NULL | `TRUE_EMERGENCY` / `FALSE_ALARM` / `UNKNOWN` |
| `resolved_by` | BIGINT | NULL | 보호자 member_id |
| `resolved_at_ms` | BIGINT | NULL | |
| `emergency_called_at_ms` | BIGINT | NULL | 보호자가 입력한 119 신고 시각 |

인덱스 `(member_id, opened_at_ms)`, `(run_id, opened_at_ms)`, `(state, respond_by_ms)`.

enum: `IncidentKind`, `IncidentState`, `IncidentResponseValue(OK, HELP)`, `IncidentOutcome`, `ResponseVia`. 모두 `@Enumerated(STRING)`.

설정 `sensor.incident`: `self-check-sec`(`${SENSOR_INCIDENT_SELF_CHECK_SEC:45}`), `timeout-poll-ms`(`${SENSOR_INCIDENT_TIMEOUT_POLL_MS:5000}`). `SensorProperties.Incident` 레코드.

## 5. 처리 흐름

### 5.1 열기 — `IncidentService.openForAlert(DecisionRecord decision, IncidentKind kind)` (`@Transactional`)
호출 지점: `HeartRateBatchService`가 `ALERT` 판정 행을 만든 직후(LLD-0053 5.1 1번), `FallAssessmentService.save`가 `ALERT`를 저장한 직후. 둘 다 `try/catch(Exception)`로 감싸 실패해도 심박 저장·보호자 알림을 막지 않는다(WARN 예외 클래스명).
1. `decision_id`로 이미 있으면 반환(멱등).
2. `state=OPEN`, `opened_at_ms=now`, `respond_by_ms=now+self-check-sec×1000`, `kind`, `level=decision.severity`, `run_id`, `member_id` 저장.
3. 시니어에게 본인확인 FCM enqueue(`FcmOutboxService.enqueue`, afterCommit 전송). enqueue 뒤 `state=CHECKING`.

### 5.2 본인 응답 — `respond(memberId, incidentRef, request)`
1. 인시던트 조회, `member_id != memberId` → `INCIDENT_NOT_FOUND`.
2. `response != null` 또는 `state == RESOLVED` → `INCIDENT_ALREADY_ANSWERED`(409).
3. `response`·`responded_at_ms=now`·`response_via` 저장. `OK` → `state=OK_CLOSED`(단, 이미 `ESCALATED`면 그대로 둔다: 마감 뒤 늦은 응답), `HELP` → `state=ESCALATED`.

### 5.3 무응답 — `IncidentTimeoutScheduler` (`@Scheduled(fixedDelayString="${sensor.incident.timeout-poll-ms}")`)
`update incident set state='ESCALATED' where state in ('OPEN','CHECKING') and respond_by_ms < :now` 벌크 1문(`@Modifying`, `@Transactional`). 갱신 건수만 로그. 추가 알림 없음(보호자는 판정 시점에 이미 받았다, ADR-0035 결정 7).

### 5.4 사후 판정 — `resolve(guardianId, incidentRef, request)`
1. 인시던트 조회 → `member_id`를 seniorId로 가족 접근 검증(실패 시 기존 가족 접근 오류).
2. `state == RESOLVED` → `INCIDENT_ALREADY_RESOLVED`(409). 라벨은 덮지 않는다.
3. `outcome`, `resolved_by=guardianId`, `resolved_at_ms=now`, `emergency_called_at_ms`(요청값, null 허용) 저장, `state=RESOLVED`.

### 5.5 내보내기 — `RunExportAssembler`
`incident` where `run_id` 정렬 `opened_at_ms` → `streams/incidents.jsonl`, 한 줄에 형식서 §3.7 필드: `incident_id`(=`incident_ref`), `run_id`, `study_id`, `participation_id`(회차에서), `kind`, `level`, `opened_at_ms`, `respond_by_ms`, `response`, `responded_at_ms`, `response_via`, `state`, `outcome`, `resolved_by`, `resolved_at_ms` + 추가 `decision_id`, `emergency_called_at_ms`. `device_id`·`_server{}` 없음. 0건이면 파일을 만들지 않고 `streams_absent`에 `reason = absent-reason-no-data`. 1건 이상이면 `streams_absent`에서 인시던트를 빼고 `manifest.files[]`에 형식서 §5 규칙대로 싣는다(형식서를 읽고 결정. 검사기 L2·I 항목이 기준).

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `INCIDENT_NOT_FOUND` (`INCIDENT_4040`) | 404 | 없음 또는 본인 아님 |
| `INCIDENT_ALREADY_ANSWERED` (`INCIDENT_4090`) | 409 | 응답 중복 |
| `INCIDENT_ALREADY_RESOLVED` (`INCIDENT_4091`) | 409 | 사후 판정 중복 |
| `INCIDENT_REQUEST_INVALID` (`INCIDENT_4000`) | 400 | 값 집합 밖 |

## 7. 인수조건 (Acceptance Criteria)

- [ ] `ALERT` 판정이 저장되면 인시던트 1건이 생기고 `respond_by_ms − opened_at_ms = 45000`, `state=CHECKING`, 시니어에게 FCM 1건이 enqueue된다. 같은 판정으로 두 번 열면 1건이다.
- [ ] 인시던트 열기가 실패해도 심박 저장·보호자 알림은 그대로 된다.
- [ ] `OK` 응답 → `OK_CLOSED`, `HELP` → `ESCALATED`, 응답 시각·경로 저장. 두 번째 응답은 409.
- [ ] 마감을 넘긴 `OPEN/CHECKING`은 스케줄러가 `ESCALATED`로 바꾸고, 그 뒤 온 `OK`는 응답만 저장하고 상태는 `ESCALATED`.
- [ ] 다른 회원이 응답하면 404. 다른 가족 보호자가 사후 판정·조회하면 가족 접근 오류.
- [ ] 사후 판정 → `RESOLVED`, `resolved_by`·`resolved_at_ms`·`emergency_called_at_ms` 저장. 두 번째는 409.
- [ ] 응답 DTO에 bpm·사유·좌표가 없다.
- [ ] 내보내기 통합 테스트에 인시던트 2건 → `streams/incidents.jsonl` 2줄, §3.7 필드, 검사기 L2 PASS·FAIL 0(수동, PR 본문). 0건이면 `streams_absent` 사유가 `NO_DATA_IN_THIS_RUN`.
- [ ] `./gradlew compileJava`, `run-module-tests.sh`, `verify.sh --base` 통과.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/create_incident.sql`. ERD, `backend/CLAUDE.md` `incident` 절. `application-sensor.yml` `incident` 블록. `apiDocs/api/study/export-checker-not-measurable.md`의 L2 행 갱신(PR 뒤 Claude가 한다).

**enum 열은 VARCHAR로 못박는다.** Hibernate 6은 MySQL에서 `@Enumerated(STRING)`을 네이티브 `ENUM`으로 매핑해 운영 DDL(VARCHAR)과 어긋난다. `Incident`의 enum 필드 다섯(`kind`·`response`·`responseVia`·`state`·`outcome`)에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 붙이고 `create_incident.sql`은 VARCHAR로 둔다.

**`FcmCategory`에 `INCIDENT_SELF_CHECK`를 더한다.** `fcm_outbox`는 `create_fcm_outbox.sql`이 `VARCHAR(32)`로 만들지만 `fcm_notification`은 생성 DDL이 저장소에 없어 운영 컬럼이 Hibernate가 만든 네이티브 ENUM일 수 있다. `ddl-auto: update`는 기존 ENUM에 값을 더하지 않으므로 `scripts/mysql/alter_fcm_category_incident_self_check.sql`로 두 테이블을 `MODIFY COLUMN`한다. 적용 전에 `SHOW COLUMNS`로 타입을 보고 VARCHAR면 건너뛴다. #665가 같은 컬럼에 `LOCATION_NOTICE`를 더하므로 두 PR이 모두 머지된 뒤 적용한다면 ENUM 목록에 두 값을 함께 넣는다.

**`sensor.export.absent-reason-not-implemented`를 뺀다.** 인시던트가 유일한 사용처였고 이제 0건일 때 `absent-reason-no-data`를 쓴다. `SensorProperties.Export`·`application-sensor.yml`·LLD-0050 두 줄을 함께 고친다.

## 9. 미결정 사항 (Open Questions)

- 없음. `level` 값 목록 미정은 ADR-0035 후속(값을 검증하지 않고 저장).

## 10. 참고
형식서 §3.7·§5, 작업지시서 B8 인시던트 단락, `HeartRateEmergencyNotificationService`(FCM 조립), `FamilyAccessAspect`·`@ValidateFamilyAccess`(ADR-0002), `RunExportWorker`·`RunExportAssembler`(LLD-0050), `HeartRateCleanupScheduler` 삭제 전 커밋(`@Scheduled` 패턴은 `FcmOutboxDispatcher.poll`)
