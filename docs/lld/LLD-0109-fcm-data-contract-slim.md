# LLD-0109: FCM data 타입 키를 notificationType 하나로 정리

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #900 |
| 관련 ADR | - |
| 작성자 | Claude / feature-900 |
| 작성일 | 2026-10-27 |

## 1. 목적 / 배경

서버는 typed FCM `data`에 `type`과 `notificationType`을 함께 싣고 `priority`·`foregroundPresentation`도 보낸다. `type`은 일부 타입에서 구 앱 호환 값(`MEDICATION_SCHEDULE_CHANGED`, `EMERGENCY_CONFIRM_REQUEST`)이라 같은 알림을 두 이름으로 부른다. 정책서(FE-HANDOFF-v0.4)는 `priority`·`foregroundPresentation`을 FCM 최소 키로 적었지만, FE는 앱 사용 중 표시를 타입 하나로 정하기로 합의했다. 기본은 배너이고 위급 알림과 복약 정시 알림만 따로 처리한다.

사용자 결정(2026-10-27): FCM data의 타입 키는 **`notificationType` 하나**이고 값은 `NotificationType` enum 이름이다. `type` 키는 보내지 않는다. 레지스트리 타입이 없는 레거시 발신자도 같은 키를 쓴다. 안전 알림 2차의 `incidentRef`는 `safetyEventId`와 같은 값이라 뺀다.

전달 우선순위는 FCM 문서의 플랫폼별 옵션 표기(`android.priority`: `HIGH`/`NORMAL`, `apns-priority`)로 맞춘다. 지금은 Android 값을 소문자로 보내고 iOS는 표시 알림이면 모두 `10`이라, 급한 알림과 일반 알림이 iOS에서 구분되지 않는다. 시니어 복약 리마인더(+10·+20분)도 급한 알림으로 올린다(사용자 결정 2026-10-27).

복약 정시 푸시([LLD-0080](LLD-0080-medication-on-time-push-switch.md))는 레지스트리 타입이 없고 지금은 일정 변경 신호와 같은 `MEDICATION_SCHEDULE_CHANGED`를 보낸다. 앱이 정시 알림을 구별하도록 `notificationType=MEDICATION_ON_TIME`을 쓰고, 알람 재동기화는 타입 대신 `revision` 비교로 판단한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `FcmSendDto.dataForEnqueue`·`FcmHttpTransport.addTypeData`·`MedicationAlarmPayload.of(revision, type)`는 `notificationType=<enum 이름>`만 넣고 `type`·`priority`·`foregroundPresentation`을 넣지 않는다.
- 타입 없는 발신자도 `notificationType`을 쓴다: 마음 메시지(`HeartMessageService`) `HEART_MESSAGE_RECEIVED`, 응원(`FcmService.sendNotificationToMember`) `CHEER_MESSAGE_RECEIVED`, 낙상 본인확인(`IncidentService.selfCheckMessage`) `SAFETY_SELF_CHECK`, 정시 복약(`MedicationAlarmPayload.of(revision)`) `MEDICATION_ON_TIME`.
- `FcmOutboxService`는 `fcm_outbox.data_type`에 data의 `notificationType`을 저장한다(실패 로그의 타입 이름 대체값). `data_payload`가 없는 옛 행은 `data_type`을 `notificationType`으로 복원한다.
- `FcmHttpTransport`는 배포 전에 저장된 행의 `type` 키를 `notificationType`으로 옮겨 보낸다. 타입 있는 행은 enum 이름으로 덮어쓴다.
- `NotificationType.legacyDataType`과 `dataTypeValue()`를 지운다. 다른 사용처가 없다.
- `IncidentEscalation.notificationFor`의 `SECOND_ALERT` 전용 `incidentRef`를 뺀다.
- `FcmHttpTransport`의 `android.priority`를 `HIGH`/`NORMAL`(FCM v1 `AndroidMessagePriority` 표기)로 보낸다. `apns-priority`는 `CRITICAL`·`TIME_SENSITIVE`만 `10`, 나머지 표시 알림은 `5`로 보낸다. data만 신호는 기존처럼 `5`다.
- `MEDICATION_REMINDER_10`·`_20`(M02·M03)의 우선순위를 `INTERACTION`에서 `TIME_SENSITIVE`로 올린다. Android `HIGH`, iOS `apns-priority 10`·`interruption-level time-sensitive`가 된다.

### Out of scope
- `channel_id`와 `interruption-level` 규칙(우선순위 등급에서 정함). 그대로 둔다.
- `android.notification.notification_priority`(표시 우선순위). 넣지 않는다. Android 8 이상 표시는 앱 채널 중요도가 정한다.
- 알림센터 v2 목록 응답(`NotificationEnvelope`)의 `priority`·`foregroundPresentation`. 그대로 둔다.
- `fcm_outbox`·`fcm_notification` 컬럼 정의. 서버 내부 타입 복원은 `notification_type` 컬럼을 쓴다. `data_type`은 길이 100 VARCHAR라 새 값이 그대로 들어간다.
- 안전 알림의 `entityId`. FE 확인 전이라 유지한다.
- `NotificationType`에 정시 상수 추가. 정시 푸시는 레지스트리 밖 레거시 메시지로 남긴다(LLD-0080 §3). DB 변경이 없다.

## 3. 인터페이스 / API

HTTP API는 바뀌지 않는다. 바뀌는 계약은 FCM HTTP v1 `message.data`다.

| 키 | 변경 전 | 변경 후 |
| --- | --- | --- |
| `type` | 레거시 값 또는 타입 이름 | **없음** |
| `notificationType` | 타입 있는 알림만, enum 이름 | 모든 알림, enum 이름(아래 값 변경 참고) |
| `priority` | `critical`/`timeSensitive`/`interaction`/`passive` | 없음 |
| `foregroundPresentation` | `MODAL`/`BANNER`/`NONE` | 없음 |
| `incidentRef`(심박·도움 요청 `SECOND_ALERT`) | `safetyEventId`와 같은 값 | 없음 |
| `android.priority` | `high`/`normal` | `HIGH`/`NORMAL` |
| `apns-priority`(표시 알림) | 모두 `10` | 급한 알림 `10`, 나머지 `5` |
| M02·M03 우선순위 | `INTERACTION`(NORMAL·5·active) | `TIME_SENSITIVE`(HIGH·10·time-sensitive) |

`eventId`, `notificationId`, `deepLink`, `entityId`, `seniorId`, `revision`, `effectiveFromDate`, `actorDisplayName`, `safetyEventId`, `deliveryStage`, `decisionId` 등 나머지 키는 그대로다.

앱이 받던 `type` 값이 바뀌는 알림:

| 알림 | 변경 전 `type` | 변경 후 `notificationType` |
| --- | --- | --- |
| 복약 일정 등록(M08) | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_SCHEDULE_CREATED` |
| 복약 일정 변경(M05) | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_SCHEDULE_CHANGED` |
| 복약 일정 삭제(M09) | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_SCHEDULE_DELETED` |
| 복약 일정 동기화(data만) | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_SCHEDULE_SYNC` |
| 심박·낙상 본인확인(S01) | `EMERGENCY_CONFIRM_REQUEST` | `SAFETY_SELF_CHECK` |
| 정시 복약(타입 없음) | `MEDICATION_SCHEDULE_CHANGED` | `MEDICATION_ON_TIME` |
| 마음 메시지·응원(타입 없음) | `HEART_MESSAGE_RECEIVED`·`CHEER_MESSAGE_RECEIVED` | 같은 값 |

나머지 타입은 예전 `type`과 같은 enum 이름이 `notificationType`으로 온다. `MEDICATION_ON_TIME`은 `NotificationType` 상수가 아니라 data 문자열이다.

보호자 안전 알림 2차 예시:

```json
{
  "data": {
    "eventId": "inc-0123456789abcdef0123456789abcdef",
    "entityId": "inc-0123456789abcdef0123456789abcdef",
    "safetyEventId": "inc-0123456789abcdef0123456789abcdef",
    "deliveryStage": "SECOND_ALERT",
    "notificationType": "HEART_RATE_EMERGENCY",
    "notificationId": "4072", "deepLink": "/location?seniorId=17", "seniorId": "17"
  },
  "android": {"priority": "HIGH", "notification": {"channel_id": "widyu_safety"}},
  "apns": {"headers": {"apns-priority": "10", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "time-sensitive"}}}
}
```

정시 복약 예시(`eventId`·`notificationId`는 타입 없는 메시지의 공통 규칙을 따른다):

```json
{"notification": {"title": "약 복용 시간이에요!", "body": "지금 약을 복용하고 인증해주세요."},
 "data": {"notificationType": "MEDICATION_ON_TIME", "revision": "42"}}
```

일반 알림 예시(안심구역 이탈, `INTERACTION`):

```json
{"android": {"priority": "NORMAL", "notification": {"channel_id": "widyu_general"}},
 "apns": {"headers": {"apns-priority": "5", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "active"}}}}
```

## 4. 데이터 모델

DB 변경 없음. `NotificationType`(widyu-domain)에서 `legacyDataType` 필드·생성자 인자·`dataTypeValue()`를 지운다. 상수와 다른 메타데이터는 그대로다.

## 5. 처리 흐름

1. 발행자가 `FcmSendDto`를 만들고 `FcmOutboxService.enqueue`가 `dataForEnqueue` 결과를 `data_payload`에, 그 `notificationType`을 `data_type`에 저장한다.
2. 전송 시 `FcmDelivery.from(row)`이 `notification_type` 컬럼으로 타입을 복원한다. `data_payload`가 없는 옛 행은 `data_type`을 `notificationType` 키로 복원한다.
3. `FcmHttpTransport`는 data의 `type` 키를 꺼내 `notificationType`이 없을 때만 그 값으로 채운다. 이어서 `addTypeData`가 타입 있는 메시지에 `notificationType=<enum 이름>`과 빈 `deepLink`를 넣는다. Android·APNs 설정은 타입의 priority·deliveryMode로 정한다.
4. 배포 전에 저장된 PENDING 행의 `data_payload`에 남은 `priority`·`foregroundPresentation`은 그대로 나갈 수 있다. 앱은 모르는 키를 무시하고 행은 TTL 안에 소진되므로 별도 정리를 하지 않는다. `type`은 3에서 항상 빠진다.

## 6. 예외 / 에러 처리

새 오류 없음.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. FCM 요청 body의 `message.data`에 `type`·`priority`·`foregroundPresentation`이 없다. 타입 있는 알림의 `notificationType`은 enum 이름이다(M08 `MEDICATION_SCHEDULE_CREATED`, SYNC `MEDICATION_SCHEDULE_SYNC`, S01 `SAFETY_SELF_CHECK` 포함). 배포 전 행의 `type`은 `notificationType`으로 옮겨진다.
- [x] AC2. `android.priority`는 `CRITICAL`·`TIME_SENSITIVE`가 `HIGH`, 나머지가 `NORMAL`이다. `apns-priority`는 같은 기준으로 `10`/`5`이고, `channel_id`·`interruption-level` 규칙은 그대로다.
- [x] AC3. 심박 위급 `SECOND_ALERT` data에 `incidentRef`가 없고 `safetyEventId`·`entityId`·`decisionId`는 남는다.
- [x] AC4. M02·M03은 `HIGH`·`apns-priority 10`·`time-sensitive`로 나간다. 정시 복약 푸시 data는 `{"notificationType":"MEDICATION_ON_TIME","revision":"<수신 시니어 revision>"}`이고 `FcmSendDto.notificationType`은 null이다.
- [x] AC5. 저하 경로·outbox 복원 data에도 `type`·`priority`·`foregroundPresentation`이 없고 `notificationType`이 있다. 마음 메시지·응원·낙상 본인확인 data도 `notificationType`을 쓴다.
- [x] AC6. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB·HTTP 변경 없음. 배포 후 `fcm_outbox.data_type`에는 enum 이름(정시는 `MEDICATION_ON_TIME`)이 쌓인다.
- **FE 변경 필요**: ① 타입 분기를 `data.type`에서 `data.notificationType`으로 바꾼다. ② 본인확인 화면 트리거를 `EMERGENCY_CONFIRM_REQUEST`에서 `SAFETY_SELF_CHECK`로 바꾼다. ③ 알람 재동기화(alarm-sync)는 타입 대신 data에 `revision`이 있고 앱 값과 다를 때 호출한다. 복약 일정 등록·변경·삭제·동기화가 서로 다른 이름으로 온다. ④ 정시 복약은 `MEDICATION_ON_TIME`으로 구별한다. 구 앱은 `type`을 못 받아 본인확인 화면·재동기화가 동작하지 않으므로 앱 배포와 맞춰 내보내야 한다.
- iOS 일반 알림의 `apns-priority`가 `10`에서 `5`로 내려간다. 기기 절전 상태에 따라 수 분 늦게 도착할 수 있다. 위급·복약 알림은 `10`이다.
- M02·M03은 iOS 집중 모드를 뚫는다(`time-sensitive`). #744의 "M02·M03 `INTERACTION`(가정)"을 대체한다.
- 이 문서가 [LLD-0060](LLD-0060-notification-type-registry.md) §3의 FCM data 최소 키와 `legacyDataType`, [LLD-0068](LLD-0068-medication-notification-alignment.md)의 `type`·`notificationType` 이중 키, [LLD-0080](LLD-0080-medication-on-time-push-switch.md) §3의 정시 data, [LLD-0076](LLD-0076-guardian-deeplink-paths.md) AC12, [LLD-0068](LLD-0068-medication-notification-alignment.md)·#744의 M02·M03 우선순위, [LLD-0081](LLD-0081-second-alert-and-stop-records.md)의 `incidentRef`를 대체한다.

## 9. 미결정 사항 (Open Questions)

- [ ] 안전 알림의 `entityId`(= `safetyEventId`) 제거 여부. FE 확인 전까지 유지한다.
- [ ] 정시 복약 값 이름 `MEDICATION_ON_TIME`의 FE 확정.
- [ ] 구 앱(`type` 의존) 사용자가 남아 있는 동안의 배포 시점. 서버는 호환 키를 남기지 않는다.

## 10. 참고

- 정책서 FE-HANDOFF-v0.4 FCM 최소 키 항목(본 저장소 밖 문서)
- [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0080](LLD-0080-medication-on-time-push-switch.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md)
