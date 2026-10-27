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

전달 우선순위는 FCM 문서의 플랫폼별 옵션 표기(`android.priority`: `HIGH`/`NORMAL`, `apns-priority`)로 맞춘다. 지금은 Android 값을 소문자로 보내고 iOS는 표시 알림이면 모두 `10`이라, 급한 알림과 일반 알림이 iOS에서 구분되지 않는다. 시니어 복약 리마인더(+10·+20분)도 급한 알림으로 올린다(사용자 결정 2026-10-27).

복약 정시 푸시([LLD-0080](LLD-0080-medication-on-time-push-switch.md))는 레지스트리 타입이 없고 `data.type`이 일정 변경 신호와 같은 `MEDICATION_SCHEDULE_CHANGED`다. 앱이 이 값을 알람 재동기화 트리거로 쓰므로 그대로 둔다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `FcmSendDto.dataForEnqueue`와 `FcmHttpTransport.addTypeData`에서 `notificationType`·`priority`·`foregroundPresentation`을 넣지 않는다. `MedicationAlarmPayload.of(revision, type)`도 `notificationType`을 넣지 않는다.
- `IncidentEscalation.notificationFor`의 `SECOND_ALERT` 전용 `incidentRef`를 뺀다.
- `FcmHttpTransport`의 `android.priority`를 `HIGH`/`NORMAL`(FCM v1 `AndroidMessagePriority` 표기)로 보낸다. `apns-priority`는 `CRITICAL`·`TIME_SENSITIVE`만 `10`, 나머지 표시 알림은 `5`로 보낸다. data만 신호는 기존처럼 `5`다.
- `MEDICATION_REMINDER_10`·`_20`(M02·M03)의 우선순위를 `INTERACTION`에서 `TIME_SENSITIVE`로 올린다. Android `HIGH`, iOS `apns-priority 10`·`interruption-level time-sensitive`가 된다.

### Out of scope
- `channel_id`와 `interruption-level` 규칙(우선순위 등급에서 정함). 그대로 둔다.
- `android.notification.notification_priority`(표시 우선순위). 넣지 않는다. Android 8 이상 표시는 앱 채널 중요도가 정한다.
- 복약 정시 푸시 data. `type=MEDICATION_SCHEDULE_CHANGED`, `revision`을 그대로 둔다.
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
| `android.priority` | `high`/`normal` | `HIGH`/`NORMAL` |
| `apns-priority`(표시 알림) | 모두 `10` | 급한 알림 `10`, 나머지 `5` |
| M02·M03 우선순위 | `INTERACTION`(NORMAL·5·active) | `TIME_SENSITIVE`(HIGH·10·time-sensitive) |

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
  "android": {"priority": "HIGH", "notification": {"channel_id": "widyu_safety"}},
  "apns": {"headers": {"apns-priority": "10", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "time-sensitive"}}}
}
```

일반 알림 예시(안심구역 이탈, `INTERACTION`):

```json
{"android": {"priority": "NORMAL", "notification": {"channel_id": "widyu_general"}},
 "apns": {"headers": {"apns-priority": "5", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "active"}}}}
```

## 4. 데이터 모델

변경 없음.

## 5. 처리 흐름

1. 발행자가 `FcmSendDto`를 만들고 `FcmOutboxService.enqueue`가 `dataForEnqueue` 결과를 `data_payload`에 저장한다. 이 맵에 세 키가 없다.
2. 전송 시 `FcmDelivery.from(row)`이 `notification_type` 컬럼으로 타입을 복원한다. data 키에 의존하지 않는다.
3. `FcmHttpTransport.addTypeData`는 `type`과 빈 `deepLink`만 보충한다. Android·APNs 설정은 기존처럼 타입의 priority·deliveryMode로 정한다.
4. 배포 전에 저장된 PENDING 행의 `data_payload`에는 세 키가 남아 있을 수 있다. 이 행은 키를 그대로 보낸다. 앱은 모르는 키를 무시하고 행은 TTL 안에 소진되므로 별도 정리를 하지 않는다.

## 6. 예외 / 에러 처리

새 오류 없음.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. typed FCM 요청 body의 `message.data`에 `notificationType`·`priority`·`foregroundPresentation`이 없다. `type`은 레거시 값(`MEDICATION_SCHEDULE_CHANGED`, `EMERGENCY_CONFIRM_REQUEST`)을 포함해 그대로다.
- [x] AC2. `android.priority`는 `CRITICAL`·`TIME_SENSITIVE`가 `HIGH`, 나머지가 `NORMAL`이다. `apns-priority`는 같은 기준으로 `10`/`5`이고, `channel_id`·`interruption-level` 규칙은 그대로다.
- [x] AC3. 심박 위급 `SECOND_ALERT` data에 `incidentRef`가 없고 `safetyEventId`·`entityId`·`decisionId`는 남는다.
- [x] AC4. M02·M03은 `HIGH`·`apns-priority 10`·`time-sensitive`로 나간다. 정시 복약 푸시 data는 `{"type":"MEDICATION_SCHEDULE_CHANGED","revision":"<수신 시니어 revision>"}` 그대로다.
- [x] AC5. 저하 경로·outbox 복원 data에도 세 키가 없다.
- [x] AC6. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB·HTTP 변경 없음.
- iOS 일반 알림의 `apns-priority`가 `10`에서 `5`로 내려간다. 기기 절전 상태에 따라 수 분 늦게 도착할 수 있다. 위급·복약 알림은 `10`이다.
- M02·M03은 iOS 집중 모드를 뚫는다(`time-sensitive`). #744의 "M02·M03 `INTERACTION`(가정)"을 대체한다.
- 이 문서가 [LLD-0060](LLD-0060-notification-type-registry.md) §3의 FCM data 최소 키, [LLD-0068](LLD-0068-medication-notification-alignment.md)의 `data.notificationType` 이중 키, [LLD-0076](LLD-0076-guardian-deeplink-paths.md) AC12, [LLD-0068](LLD-0068-medication-notification-alignment.md)·#744의 M02·M03 우선순위, [LLD-0081](LLD-0081-second-alert-and-stop-records.md)의 `incidentRef`를 대체한다.

## 9. 미결정 사항 (Open Questions)

- [ ] 안전 알림의 `entityId`(= `safetyEventId`) 제거 여부. FE 확인 전까지 유지한다.
- [ ] 정시 복약 푸시를 앱 사용 중에 따로 표시하려면 구별값이 필요한지. 지금은 일정 변경 신호와 같은 `type`이라 구별되지 않는다. 필요하면 별도 키를 더한다(`type`은 바꾸지 않음).

## 10. 참고

- 정책서 FE-HANDOFF-v0.4 FCM 최소 키 항목(본 저장소 밖 문서)
- [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0080](LLD-0080-medication-on-time-push-switch.md), [LLD-0081](LLD-0081-second-alert-and-stop-records.md)
