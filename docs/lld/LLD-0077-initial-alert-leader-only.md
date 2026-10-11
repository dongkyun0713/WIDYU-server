# LLD-0077: 보호자 최초 긴급 알림을 현재 방장 한 명에게 발송

> Low-Level Design. 이 문서는 이슈 #735(W17)의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (코디네이터 게이트 승인 2026-10-11, W17 구현·검증 완료) |
| Issue | #735 |
| 관련 ADR | [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md), ADR-0039(작성 중) |
| 선행 | [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0076](LLD-0076-guardian-deeplink-paths.md) |
| 작성자 | Codex / feature-735 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

현재 `IncidentEscalation.enqueueInitialAlert`는 가족의 활성 보호자 전원에게 최초 긴급 알림을 예약한다. 2026-10-05 추가본 A1·A3은 60초 무응답, HELP, 본인확인 선행 플래그 OFF의 즉시 발송 모두 **현재 가족 방장 한 명**에게 ① `INITIAL_ALERT`를 보내도록 요구한다. 이 변경은 같은 사건의 알림센터 항목을 방장에게만 만들고, FCM data에 단계와 상황 식별자를 넣어 앱의 모달 표시를 지원한다.

## 2. 범위

### In scope

- `widyu-api`: `IncidentEscalation`의 공통 `enqueueInitialAlert`에서 **심박 `HR_ANOMALY`의 S04** 수신자를 `FamilyMembership.isLeader`와 활성 보호자 상태로 고른다. `sendImmediately`와 `escalateIfDue`가 이 경로를 공유한다. S04 `data` 맵에 `deliveryStage`, `safetyEventId`를 더하고 기존 `decisionId`를 보존한다.
- `widyu-api`: 신규 사건 생성 시 기존 `incident.policy_revision`을 채운다. `IncidentService`의 `attachOrOpen`과 낙상용 생성 경로를 확인한다.
- `widyu-domain`: `NotificationType.HEART_RATE_EMERGENCY.foregroundPresentation`을 `MODAL`로 변경하는 안. M02·M03·H01의 priority를 `INTERACTION`으로, M04는 `TIME_SENSITIVE`로 두는 안. 둘 다 추가본 §4의 미확정 추천안이므로 9절에 남긴다.
- 테스트: 수신자·게이트·센터/outbox·data·표시와 우선순위 값을 검증하고 기존 전원 발송 기대값을 개정한다.

### Out of scope

- ② 3분 단계·멈춤 이력·② 취소(W18), 안심구역 비사건화와 Z01/Z02(W19), 응답 시도 이력·상태 조회(W20), 워치 도움 요청(W21).
- 보호자 앱의 모달 UI·전체 화면 권한·실기기 검증. 서버는 표시 힌트만 전송한다.
- 새 HTTP API, `FcmCategory` 값 추가, 스키마 변경, 기존 사건의 정책판 백필, FCM exactly-once 전달.

## 3. 인터페이스 / API

HTTP 요청·응답과 `POST /api/v1/incidents/{incidentId}/response`의 `HELP` 계약은 유지한다. 변경되는 wire 계약은 S04 `HEART_RATE_EMERGENCY` 최초 알림의 FCM `data`다. 기존 `eventId`, `type`, `priority`, `notificationId`, `deepLink`, `foregroundPresentation`, `seniorId`, 배치 사건의 `decisionId`는 보존한다.

```json
{
  "data": {
    "eventId": "inc-0123456789abcdef0123456789abcdef",
    "safetyEventId": "inc-0123456789abcdef0123456789abcdef",
    "deliveryStage": "INITIAL_ALERT",
    "type": "HEART_RATE_EMERGENCY",
    "priority": "critical",
    "notificationId": "4072",
    "deepLink": "/location?seniorId=17",
    "foregroundPresentation": "MODAL",
    "seniorId": "17"
  }
}
```

`safetyEventId=eventId=incident_ref`다. `deliveryStage`는 W17에서 `INITIAL_ALERT`만 사용한다. 배치 사건은 기존 `decisionId`를 함께 보내고 판정 없는 단건은 생략한다. 안전 전용 분기를 공통 `FcmSendDto.dataForEnqueue`에 넣지 않고 `IncidentEscalation`이 `data` 맵에 두 키를 넣는다. 기존 공통 enqueue가 `data_payload`에 보존하고 재시도 시 복원한다. Android high·`widyu_safety`, APNs priority 10·time-sensitive, S04 문구와 딥링크는 유지한다.

## 4. 데이터 모델

새 테이블·열·인덱스는 없다. `incident.policy_revision`은 LLD-0070과 `scripts/mysql/alter_incident_self_check_first.sql`에 이미 `BIGINT NULL`로 있고 엔티티 타입은 `Long`이다. 신규 사건에는 2026-10-05 정책판을 기존 숫자 열에 표현한 상수 `20261005L`을 생성 시 저장하는 안이다. 이 값은 사건 정책 스냅샷이며 회원의 `notification_policy_revision`과 무관하다. 기존 NULL 사건은 이번 변경에서 수정하지 않는다.

`FamilyMembership.isLeader`가 방장 판정 근거다. 사건을 열 때 수신자를 고정하지 않고 **① enqueue 시점**의 가족 연결과 보호자 활성 상태로 조회한다. 기존 `FcmOutboxService.enqueue`는 수신자×`eventId`마다 센터 행 하나를 만들며, outbox는 활성 기기 토큰마다 만든다. 따라서 “outbox 1건” 검증은 방장 활성 토큰 1개 조건을 사용한다. enum 저장 열과 `FcmCategory`는 바꾸지 않는다.

## 5. 처리 흐름

1. 심박 위급은 기존처럼 사건을 열고 시니어 S01을 먼저 enqueue한다. 플래그 OFF는 사건 트랜잭션에서 `sendImmediately`가 공통 `enqueueInitialAlert`를 호출한 뒤 `initial_alert_sent_at_ms`를 기록한다. 플래그 ON의 60초 무응답 또는 HELP는 기존 5초 폴링이 `claimInitialAlertIfDue`에 성공할 때 `REQUIRES_NEW` 트랜잭션에서 같은 메서드를 호출한다.
2. 공통 메서드의 `HR_ANOMALY` 분기는 시니어의 현재 가족에서 `isLeader=true`이고 guardian 상태가 `ACTIVE`인 방장 한 명만 골라 `FcmOutboxService.enqueue`를 한 번 호출한다. `isRepresentative`만 참인 보호자와 일반 보호자는 심박 ① 수신자가 아니다. 기존 `findAllByFamilyIdWithGuardian` 결과를 필터링한다. 활성 방장이 둘 이상이면 `FamilyMembership.id`가 가장 작은 한 명에게만 보내고 incident ref·가족 ID만 WARN으로 남긴다.
3. 가족이 없거나 활성 방장이 없으면 enqueue하지 않고 incident ref와 수신자 0건만 WARN으로 남긴다. 플래그 OFF는 `markInitialAlertSent(nowMs)`, 플래그 ON은 이미 성공한 `claimInitialAlertIfDue`를 유지한다. 둘 다 게이트를 닫아 다음 폴링의 중복 ①을 막는다. 사건과 S01은 보존한다.
4. `IncidentEscalation`이 S04 `data`에 `deliveryStage=INITIAL_ALERT`, `safetyEventId=incident_ref`, 존재할 때 `decisionId`를 담는다. DTO의 `eventId`에도 같은 `incident_ref`를 넣는다. 공통 DTO가 나머지 메타데이터를 병합하며, 재시도는 저장된 data를 쓴다.
5. 새 사건의 정책판 상수를 저장하고, 같은 사건에 새 감지를 붙일 때 덮지 않는다. `NotificationType`의 지정된 표시·우선순위 메타데이터만 바꾸고 전달 방식·설정 그룹·센터 필터·보존 기간·딥링크·문구는 유지한다.

현재 브랜치에는 W19 이전의 `SAFE_ZONE_EXIT` 사건도 공통 `enqueueInitialAlert`를 사용한다. 이 경로의 기존 S05 수신자·data는 W17에서 바꾸지 않는다. 안심구역의 최종 비사건화는 W19 범위다. 낙상 `FALL_SUSPECTED`의 기존 알림 동작도 유지한다.

## 6. 예외 / 에러 처리

| 조건 | 처리 |
| --- | --- |
| 가족 없음·방장 미지정·방장 비활성 | ① 센터/outbox 0건, 예외 없음, incident ref만 WARN, `initial_alert_sent_at_ms` 게이트 기록 |
| 활성 방장 둘 이상 | `FamilyMembership.id`가 가장 작은 방장 한 명에게만 enqueue하고 incident ref·가족 ID만 WARN. 예외나 발송 중단 없이 게이트 기록 |
| `claimInitialAlertIfDue` 0건 | 추가 발송 0건. 플래그 OFF 사건의 60초 무응답 상태 전환은 유지 |
| outbox enqueue 실패 | 기존 트랜잭션 경계대로 게이트도 롤백하고 다음 폴링에서 재시도. 실제 FCM 재시도는 outbox 정책을 따른다 |
| 토큰 없음·푸시 설정 OFF | 센터 행은 방장에게 1개 저장. 토큰 없으면 outbox 0건, 푸시 자격은 기존 claim/preflight에서 판정 |

새 HTTP 오류 코드는 없다. 로그에 심박 수치·사유·좌표·토큰·FCM 본문을 남기지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] AC1. 플래그 ON 심박 사건은 60초 안 OK이면 긴급 센터/outbox 0건이다. 60초 무응답으로 게이트가 성공하면 현재 활성 방장(활성 토큰 1개)에게 S04 센터 1건·outbox 1건, 비방장에게 0건이며 `initial_alert_sent_at_ms`가 남는다. 반복·동시 폴링에도 ① enqueue는 사건당 한 번이다.
- [ ] AC2. 플래그 OFF의 `sendImmediately`도 현재 방장에게만 S04 센터 1건·outbox 1건을 만들고 게이트를 기록한다. 이후 60초 무응답 상태 전환은 하되 ① 추가 발송은 0건이다.
- [ ] AC3. 가족 없음·방장 미지정·방장 비활성은 예외 없이 센터/outbox 0건, WARN과 게이트 기록을 남긴다. 사건·시니어 본인확인 S01은 보존한다.
- [ ] AC3a. 활성 방장이 둘 이상이면 `FamilyMembership.id`가 가장 작은 방장에게만 센터/outbox를 만들고 incident ref·가족 ID만 WARN에 남긴다. 예외 없이 게이트를 기록하고 다른 방장에게는 0건이다.
- [ ] AC4. S04 FCM data에는 `deliveryStage=INITIAL_ALERT`, `safetyEventId=eventId=incident_ref`가 있고 재시도 뒤에도 같다. 배치 `decisionId`와 기존 data·딥링크는 보존하며 단건은 `decisionId` 없이 발송한다.
- [ ] AC5. `HEART_RATE_EMERGENCY.foregroundPresentation=MODAL`이다. M02·M03·H01은 `INTERACTION`, M04는 `TIME_SENSITIVE`라는 **9절 가정**으로 검증한다. 이 타입들의 다른 메타데이터와 긴급 플랫폼 헤더는 변하지 않는다.
- [ ] AC6. HELP는 기존 다음 폴링(설정 5초)에서 같은 게이트로 현재 방장에게만 ①을 한 번 enqueue한다. 이미 보낸 사건은 추가 0건이다.
- [ ] AC7. 기존 `IncidentEscalationTest`의 **심박 S04** “활성 보호자 전원” 기대를 방장 1명으로 개정한다. 심박 플래그 ON/OFF·HELP를 검증하고, 과도기 안심구역 S05의 기존 수신자 기대는 유지한다. 상태 검증을 우선하고 외부 enqueue에는 `verify`를 사용한다. JUnit 5·BDDMockito `given/willReturn`, 한글 언더스코어 테스트명과 행위형 `@DisplayName`을 따른다.
- [ ] AC8. 새 사건의 `policy_revision=20261005`가 저장되고 재사용 시 바뀌지 않는다. `FcmCategory` 값·스키마·HTTP 계약을 늘리지 않는다.
- [ ] AC9. `bash scripts/harness/verify.sh`가 통과한다. 엔티티 필드·매핑을 바꾸게 되면 `./gradlew compileJava`와 Domain+API 테스트도 수행한다.

## 8. 영향 범위 / 마이그레이션

LLD-0070 §5.3·§7의 전원 발송 기대와 LLD-0060의 타입 표 중 바뀌는 부분을 이 문서의 W17 기준으로 대체한다. `FcmOutboxService`의 센터 1행, 안전 설정 판정, outbox 재시도·TTL·알림센터 읽음 계약은 유지한다. `NotificationType`의 위 표시/우선순위 외 다른 열은 바꾸지 않는다.

스키마 변경은 없다. `policy_revision BIGINT NULL`은 운영 DDL과 ERD에 이미 있다. 과거 NULL 사건을 새 정책으로 오인하지 않도록 백필하지 않는다. 배포 뒤 새 사건만 revision이 채워지는지 확인한다. 앱은 `MODAL`과 새 data 키를 인식해야 하며 서버 변경만으로 실제 모달 표시를 보장하지 않는다.

## 9. 미결정 사항 (Open Questions)

추가본 §4는 확정 정책이 아니다. 코디네이터는 2026-10-11 아래 추천안을 W17 **구현 가정**으로 승인했다. FE·제품 회신이 달라지면 후속 결정 게이트에서 조정한다.

| 질문 | W17 가정 | 확인이 필요한 이유 |
| --- | --- | --- |
| B2: ① 보낸 시각과 ① 전송이 계속 실패할 때 후속 기산점은? | 기존 `initial_alert_sent_at_ms`의 enqueue 시각을 ① 기준으로 유지한다. provider 첫 성공 시각으로 덮지 않는다. | W18의 3분 마감·`DELIVERY_FAILED` 해석에 영향. W17은 ②를 만들지 않는다. |
| 추가본 §4 「3」: 앱 사용 중 심박 위급을 어떤 값으로 표시할까? | 서버 `foregroundPresentation=MODAL`로 변경한다. | FE의 S11 화면 연결·실기기 확인이 필요하다. |
| 추가본 §4 「4」: 복약·건강일정의 시간 민감 범위는? | M02·M03·H01은 `INTERACTION`, M04만 `TIME_SENSITIVE`. | 집중 모드 노출 정책에 대한 FE·제품 회신이 필요하다. |
| 방장이 사건 진행 중 이전되면 ① 수신자는? | 사건 생성 당시가 아닌 **enqueue 시점의 현재 방장**. | 방장 이전과 스케줄러 경합 시 운영 기대를 확인한다. |
| 정책판 `2026-10-05`를 기존 BIGINT에 어떻게 기록할까? | DDL 없이 `20261005L`로 인코딩해 신규 사건에만 저장한다. | 문서의 문자열 예시와 기존 `Long` 열 사이의 표현을 확인한다. |

2026-10-11 코디네이터 게이트 결정: 가족당 방장은 원칙적으로 한 명이다. 활성 방장 후보가 둘 이상이면 `FamilyMembership.id`가 가장 작은 한 명에게 발송하고 incident ref·가족 ID만 WARN으로 남긴다. 예외나 발송 중단 없이 기존 게이트를 기록한다.

## 10. 참고

- 정본: 저장소 밖 `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1 A1·A3·A7·A14, §4, §5 B2.
- 분석: 저장소 밖 `apiDocs/notification/BE-GAP-2026-10-05.md` §3 #1·#9·#10·#14·#15. 전체 계획: `apiDocs/notification/BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11 W17.
- [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) 결정 1·3·7·10, ADR-0039(작성 중), [LLD-0070](LLD-0070-self-check-first-heart.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0060](LLD-0060-notification-type-registry.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
