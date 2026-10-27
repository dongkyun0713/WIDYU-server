# LLD-0109: FCM data에서 쓰지 않는 키 제거와 복약 정시 푸시 type

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #900 |
| 관련 ADR | - |
| 작성자 | Claude / feature-900 |
| 작성일 | 2026-10-27 |

## 1. 목적 / 배경

서버는 typed FCM `data`에 `type`과 함께 `notificationType`·`priority`·`foregroundPresentation`을 싣는다. 정책서(FE-HANDOFF-v0.4)는 `priority`·`foregroundPresentation`을 FCM 최소 키로 적었지만, FE는 앱 사용 중 표시를 `type` 하나로 정하기로 합의했다. 기본은 배너이고 위급 알림과 복약 정시 알림만 따로 처리한다. `notificationType`은 `type`과 같은 논리 이름을 한 번 더 보내고, 안전 알림 2차의 `incidentRef`는 `safetyEventId`와 같은 값이다.

복약 정시 푸시([LLD-0080](LLD-0080-medication-on-time-push-switch.md))는 레지스트리 타입이 없고 `data.type`이 일정 변경 신호 `MEDICATION_SCHEDULE_CHANGED`라서 앱이 정시 알림을 구별하지 못한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `FcmSendDto.dataForEnqueue`와 `FcmHttpTransport.addTypeData`에서 `notificationType`·`priority`·`foregroundPresentation`을 넣지 않는다. `MedicationAlarmPayload.of(revision, type)`도 `notificationType`을 넣지 않는다.
- `IncidentEscalation.notificationFor`의 `SECOND_ALERT` 전용 `incidentRef`를 뺀다.
- 복약 정시 푸시 data를 `MedicationAlarmPayload.onTime(revision)`으로 만들고 `type=MEDICATION_ON_TIME`을 싣는다.

### Out of scope
- `NotificationType.priority()`로 정하는 Android·APNs 전달 설정(`android.priority`, `channel_id`, `apns-priority`, `interruption-level`). 그대로 둔다.
- 알림센터 v2 목록 응답(`NotificationEnvelope`)의 `priority`·`foregroundPresentation`. 그대로 둔다.
- `fcm_outbox.notification_type`·`data_type`·`data_payload` 등 영속 모델. 서버 내부 복원은 `notification_type` 컬럼을 쓰므로 data 키와 무관하다.
- 안전 알림의 `entityId`. FE 확인 전이라 유지한다.
- `NotificationType`에 정시 상수 추가. 정시 푸시는 레지스트리 밖 레거시 메시지로 남긴다(LLD-0080 §3). DB 변경이 없다.

## 3. 인터페이스 / API

HTTP API는 바뀌지 않는다. 바뀌는 계약은 FCM HTTP v1 `message.data`다.

| 키 | 변경 전 | 변경 후 |
| --- | --- | --- |
| `type` | 레거시 값 또는 논리 이름 | 그대로 |
| `notificationType` | 논리 이름(`type`과 중복) | 없음 |
| `priority` | `critical`/`timeSensitive`/`interaction`/`passive` | 없음 |
| `foregroundPresentation` | `MODAL`/`BANNER`/`NONE` | 없음 |
| `incidentRef`(심박·도움 요청 `SECOND_ALERT`) | `safetyEventId`와 같은 값 | 없음 |
| 정시 복약 푸시 `type` | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_ON_TIME` |

`eventId`, `notificationId`, `deepLink`, `entityId`, `seniorId`, `revision`, `effectiveFromDate`, `actorDisplayName`, `safetyEventId`, `deliveryStage`, `decisionId` 등 나머지 키는 그대로다.

보호자 안전 알림 2차 예시:

```json
{
  "data": {
    "eventId": "inc-0123456789abcdef0123456789abcdef",
    "entityId": "inc-0123456789abcdef0123456789abcdef",
    "safetyEventId": "inc-0123456789abcdef0123456789abcdef",
    "deliveryStage": "SECOND_ALERT",
    "type": "HEART_RATE_EMERGENCY",
    "notificationId": "4072", "deepLink": "/location?seniorId=17", "seniorId": "17"
  },
  "android": {"priority": "high", "notification": {"channel_id": "widyu_safety"}},
  "apns": {"headers": {"apns-priority": "10", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "time-sensitive"}}}
}
```

복약 정시 푸시 예시(타입 없는 레거시 메시지라 `eventId`·`notificationId`는 기존 공통 경로 규칙을 따른다):

```json
{"notification": {"title": "약 복용 시간이에요!", "body": "지금 약을 복용하고 인증해주세요."},
 "data": {"type": "MEDICATION_ON_TIME", "revision": "42"}}
```

## 4. 데이터 모델

변경 없음. 정시 푸시 outbox 행의 `data_type`은 `MEDICATION_ON_TIME`(18자, 컬럼 길이 100)으로 저장된다.

## 5. 처리 흐름

1. 발행자가 `FcmSendDto`를 만들고 `FcmOutboxService.enqueue`가 `dataForEnqueue` 결과를 `data_payload`에 저장한다. 이 맵에 세 키가 없다.
2. 전송 시 `FcmDelivery.from(row)`이 `notification_type` 컬럼으로 타입을 복원한다. data 키에 의존하지 않는다.
3. `FcmHttpTransport.addTypeData`는 `type`과 빈 `deepLink`만 보충한다. Android·APNs 설정은 기존처럼 타입의 priority·deliveryMode로 정한다.
4. 배포 전에 저장된 PENDING 행의 `data_payload`에는 세 키가 남아 있을 수 있다. 이 행은 키를 그대로 보낸다. 앱은 모르는 키를 무시하고 행은 TTL 안에 소진되므로 별도 정리를 하지 않는다.

## 6. 예외 / 에러 처리

새 오류 없음.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. typed FCM 요청 body의 `message.data`에 `notificationType`·`priority`·`foregroundPresentation`이 없다. `type`은 레거시 값(`MEDICATION_SCHEDULE_CHANGED`, `EMERGENCY_CONFIRM_REQUEST`)을 포함해 그대로다.
- [x] AC2. 같은 타입의 `android.priority`·`channel_id`·`apns-priority`·`interruption-level`은 변경 전과 같다.
- [x] AC3. 심박 위급 `SECOND_ALERT` data에 `incidentRef`가 없고 `safetyEventId`·`entityId`·`decisionId`는 남는다.
- [x] AC4. 정시 복약 푸시 data는 `{"type":"MEDICATION_ON_TIME","revision":"<수신 시니어 revision>"}`이고 `notificationType`은 null이다.
- [x] AC5. 저하 경로·outbox 복원 data에도 세 키가 없다.
- [x] AC6. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB·HTTP 변경 없음.
- 위듀 앱이 정시 푸시의 `MEDICATION_SCHEDULE_CHANGED`로 alarm-sync를 다시 호출했다면, 이제 `MEDICATION_ON_TIME`의 `revision`을 보고 호출해야 한다. 일정 변경 푸시(M05·M08·M09·SYNC)의 `MEDICATION_SCHEDULE_CHANGED`는 그대로다.
- 이 문서가 [LLD-0060](LLD-0060-notification-type-registry.md) §3의 FCM data 최소 키, [LLD-0068](LLD-0068-medication-notification-alignment.md)의 `data.notificationType` 이중 키, [LLD-0076](LLD-0076-guardian-deeplink-paths.md) AC12, [LLD-0080](LLD-0080-medication-on-time-push-switch.md) §3의 정시 data, [LLD-0081](LLD-0081-second-alert-and-stop-records.md)의 `incidentRef`를 대체한다.

## 9. 미결정 사항 (Open Questions)

- [ ] 안전 알림의 `entityId`(= `safetyEventId`) 제거 여부. FE 확인 전까지 유지한다.
- [ ] 정시 푸시 `type` 값 이름 `MEDICATION_ON_TIME`의 FE 확정, 그리고 정시 푸시 수신 시 revision 비교로 alarm-sync를 호출할지.

## 10. 참고

- 정책서 FE-HANDOFF-v0.4 FCM 최소 키 항목(본 저장소 밖 문서)
- [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0080](LLD-0080-medication-on-time-push-switch.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md)
