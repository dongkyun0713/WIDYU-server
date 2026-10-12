# LLD-0085: 본인확인 화면 수신 완료 기준 마감

> Low-Level Design. 이 문서는 이슈 #760 구현과 PR 검수의 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-13) |
| Issue | #760 |
| 관련 ADR | [ADR-0039](../adr/ADR-0039-two-stage-guardian-alert-and-safe-zone-notice.md) 결정 10(이 문서와 함께 추가), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) 결정 2 |
| 선행 | [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0077](LLD-0077-initial-alert-leader-only.md), [LLD-0082](LLD-0082-response-attempts-and-incident-status.md) |
| 작성자 | Claude / feature-760 |
| 작성일 | 2026-10-13 |

## 1. 목적 / 배경

심박 위급 본인확인은 사건을 연 시각부터 60초 안에 시니어 `취소`가 없으면 무응답으로 보고 방장에게 1차 알림(①)을 보낸다. 이 마감은 두 상황을 구분하지 못한다.

- 휴대폰이 꺼졌거나 푸시가 늦게 도착해 시니어가 확인 화면을 보지 못했다.
- 화면은 떴지만 실제로 위급해 `취소`를 누르지 못했다.

푸시가 늦게 도착하면 시니어가 쓸 수 있는 시간도 그만큼 줄어든다. 사용자 결정(2026-10-13)에 따라 앱이 확인 화면을 띄운 사실을 서버에 알리고, 서버는 그 시각부터 60초를 센다. 화면이 끝내 뜨지 않으면 신호가 없다는 사실 자체를 위험으로 보고 정해진 시간 뒤 ①을 보낸다.

## 2. 범위

### In scope

- `widyu-domain`: `Incident`에 `prompt_displayed_at_ms`(BIGINT NULL)·`prompt_displayed_via`(VARCHAR(16) NULL, `ResponseVia`) 추가.
- `widyu-api`: 시니어 API `POST /api/v1/incidents/{incidentId}/prompt-displayed`, 심박 사건의 첫 마감 변경, 설정 `sensor.incident.prompt-wait-sec`, `IncidentResponse.promptDisplayedAtMs`, Swagger, 테스트.
- 운영 DDL `scripts/mysql/alter_incident_prompt_displayed.sql`, ERD-0001.

### Out of scope

- 낙상(`FALL_SUSPECTED`)·워치 도움 요청(`SELF_HELP_REQUEST`)의 마감. 낙상은 정책상 현재 동작을 유지하고, 도움 요청은 본인확인이 없다.
- 보호자에게 「화면을 못 봤다/봤다」를 다르게 안내하는 문구·필드. 기록만 남기고 표시는 FE·기획 협의 뒤 정한다.
- 푸시 수신 확인(전송 성공 판정)은 바꾸지 않는다. 이 API는 「확인 화면을 띄웠다」는 사용자 기기 사실이다.

## 3. 인터페이스 / API

### 3.1 시니어 확인 화면 수신 완료

`POST /api/v1/incidents/{incidentId}/prompt-displayed` (시니어 본인, `{incidentId}`는 `incident_ref`)

요청

```json
{ "via": "PHONE" }
```

- `via`: `PHONE` | `WATCH` 필수. 확인 화면을 띄운 기기.

응답 200 `INCIDENT_2009`

```json
{
  "incidentId": "inc-…",
  "state": "CHECKING",
  "promptDisplayedAtMs": 1760320000000,
  "respondByMs": 1760320060000,
  "deadlineExtended": true
}
```

- `deadlineExtended=true`: 이번 요청으로 마감이 `수신 시각 + 60초`로 바뀌었다. 앱은 `respondByMs`로 남은 시간을 그린다.
- `false`: 이미 수신이 기록됐거나(두 번째 기기·재전송), 마감이 지났거나, 응답·종결됐거나, 심박 사건이 아니다. 기록은 바꾸지 않고 현재 값을 돌려준다. 같은 요청을 다시 보내도 안전하다.
- 본인 사건이 아니거나 없으면 404 `INCIDENT_4040`(존재를 드러내지 않음, 기존 응답 API와 같음).

### 3.2 마감 규칙 (심박 사건)

| 상황 | `respond_by_ms` |
|---|---|
| 사건을 열 때 | `opened_at_ms + prompt-wait-sec`(기본 90초) |
| 마감 전에 첫 수신 완료 | `수신 시각 + self-check-sec`(기본 60초) |
| 수신 완료 없음 | 그대로. 90초가 지난 첫 스케줄러 폴링에서 무응답 처리와 ① |

- 수신 완료가 마감 직전에 오면 최대 대기는 `prompt-wait-sec + self-check-sec`(150초)다.
- 기존 무응답 스케줄러(`respondByMs < now`), ① 게이트, 지각 판정(`respondedAtMs >= respondByMs`)은 바뀐 `respond_by_ms`를 그대로 쓴다. 새 스케줄러·조건은 없다.

## 4. 데이터 모델 / 변경 설계

- `Incident.promptDisplayedAtMs`(Long), `Incident.promptDisplayedVia`(`ResponseVia`, `@Enumerated(STRING)` + `@JdbcTypeCode(VARCHAR)`, length 16).
- `SensorProperties.Incident`에 `promptWaitSec` 추가, `application-sensor.yml` `prompt-wait-sec: ${SENSOR_INCIDENT_PROMPT_WAIT_SEC:90}`.
- `IncidentService.attachOrOpen`(심박 전용)의 첫 마감을 `promptWaitSec`로 바꾼다. 낙상 `openForAlert(decision, FALL_SUSPECTED)`는 `selfCheckSec` 그대로.
- `IncidentRepository.markPromptDisplayed`: 조건부 UPDATE 한 문장.

```sql
UPDATE incident
   SET prompt_displayed_at_ms = :now,
       prompt_displayed_via   = :via,
       respond_by_ms = :now + :selfCheckMs
 WHERE incident_ref = :ref AND member_id = :memberId
   AND kind = 'HR_ANOMALY'
   AND prompt_displayed_at_ms IS NULL
   AND response IS NULL
   AND state = 'CHECKING'
   AND respond_by_ms > :now
```

- 조건을 한 문장에 두는 이유: 읽고 고쳐 저장하면 그 사이 스케줄러가 올린 `ESCALATED`나 먼저 도착한 응답을 놓치고 끝난 사건의 마감을 늘릴 수 있다. 폰·워치가 동시에 보내도 `prompt_displayed_at_ms IS NULL`로 첫 요청만 반영된다.
- 마감이 지난 뒤 온 수신 완료는 기록하지 않는다. 그 사건은 이미 「화면을 못 본 채 마감」으로 처리됐고, 늦은 수신으로 마감을 되살리지 않는다.
- 플래그 `self-check-first` OFF에서도 같은 규칙이다. OFF는 ①을 즉시 보내지만 사건은 `CHECKING`이라 마감·지각·S08 판정에 같은 `respond_by_ms`가 쓰인다.

## 5. 처리 흐름

1. 심박 판정 → `attachOrOpen`이 사건을 열고 `respond_by_ms = opened + 90초`, 시니어에게 S01(`type=EMERGENCY_CONFIRM_REQUEST`).
2. 앱이 확인 화면을 띄우는 즉시 `prompt-displayed` 호출 → 마감이 `수신 + 60초`로 바뀌고, 응답의 `respondByMs`로 카운트다운.
3. 시니어 `취소`(OK) → 기존 응답 API, 마감 안이면 `OK_CLOSED`.
4. 마감까지 응답 없음 → 기존 스케줄러가 `ESCALATED`, ① 방장 알림(플래그 ON), 3분 뒤 ②.
5. 화면이 안 뜸(휴대폰 꺼짐 등) → 수신 완료가 없어 90초 마감 → 4와 같음.

## 6. 예외 / 에러 처리

| 경우 | 결과 |
|---|---|
| 본인 사건 아님·없음 | 404 `INCIDENT_4040` |
| `via` 누락·잘못된 값 | 400 (기존 바인딩 규칙) |
| 두 번째 수신·마감 뒤·응답 뒤·종결·심박 외 사건 | 200, `deadlineExtended=false`, 값 변경 없음 |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 심박 사건을 열면 `respond_by_ms - opened_at_ms = 90000`이다. 낙상 사건은 60000 그대로다.
- [x] AC2. 마감 전 첫 수신 완료는 `prompt_displayed_at_ms`·`via`를 기록하고 `respond_by_ms`를 `수신 시각 + 60000`으로 바꾼다.
- [x] AC3. 두 번째 수신 완료(다른 기기 포함)는 값을 바꾸지 않고 200 `deadlineExtended=false`를 반환한다.
- [x] AC4. 마감이 지난 사건, 응답한 사건, `CHECKING`이 아닌 사건, 심박이 아닌 사건의 수신 완료는 아무것도 바꾸지 않는다.
- [x] AC5. 다른 시니어의 사건이면 404다.
- [x] AC6. 기존 무응답 스케줄러가 바뀐 마감으로 `ESCALATED`와 ①을 처리한다(마감 전에는 처리하지 않는다).
- [x] AC7. `IncidentResponse`에 `promptDisplayedAtMs`가 실린다.
- [x] AC8. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- 운영 DDL(배포 전): `ALTER TABLE incident ADD COLUMN prompt_displayed_at_ms BIGINT NULL, ADD COLUMN prompt_displayed_via VARCHAR(16) NULL;` (`ddl-auto=validate`).
- 배포 시점의 열린 심박 사건은 기존 60초 마감 그대로 끝난다. 새 사건부터 90초가 적용된다.
- 앱이 이 API를 보내기 전에 배포하면 모든 심박 사건이 「화면 미수신」으로 처리되어 ①이 90초에 나간다(지금보다 30초 늦음). **앱 배포와 맞추거나, 앱 배포 전에는 `SENSOR_INCIDENT_PROMPT_WAIT_SEC=60`으로 두면 지금 동작과 같다.**
- 테스트 픽스처의 `SensorProperties.Incident` 생성자에 인자 하나가 늘어난다.

## 9. 미결정 사항 (Open Questions)

| 항목 | 현재 계약 | 확인 |
|---|---|---|
| 90초·60초 값 | 사용자 결정(2026-10-13). 설정으로 조정 가능 | 추가본 A1 「60초」 기준점 변경을 FE·기획에 공유 |
| 보호자 안내 구분 | 기록만(`promptDisplayedAtMs`). 문구·상태 조회 필드 없음 | 「화면이 전달되지 않았어요/응답이 없었어요」 구분 여부 |
| 워치 화면 | `via=WATCH` 수신도 같은 규칙 | 워치에 확인 화면을 띄우는 경로(B7) |
| 단말 표시 시각 | 서버 수신 시각만 기준 | 연구 분석에 단말 시각이 필요한지 |

## 10. 참고

- 추가본 `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A1(저장소 밖).
- [LLD-0070](LLD-0070-self-check-first-heart.md) §5(무응답 스케줄러·① 게이트), [LLD-0082](LLD-0082-response-attempts-and-incident-status.md)(응답 시도 지각 판정).
