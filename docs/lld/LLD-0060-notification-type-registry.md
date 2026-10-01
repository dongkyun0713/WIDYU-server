# LLD-0060: 알림 타입 레지스트리·outbox type·긴급 표현

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> LLD 하나 = PR 하나가 원칙. "하나의 PR에 넣기엔 diff가 너무 많다(파일 15개 이상)"면 LLD를 분리한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #692 |
| 관련 ADR | ADR-0037, ADR-0038 |
| 작성자 | Codex / feature-692 |
| 작성일 | 2026-10-01 |

## 1. 목적 / 배경

현재 outbox는 `FcmCategory`와 일부 data만 저장한다. `FcmDelivery.from(row)`이 전송 직전에 DTO를 재조립하므로 행에 없는 type·딥링크·data는 사라지고, type별 채널·priority 매핑도 불가능하다. 승인판 이벤트를 `NotificationType`에 등록하고 outbox에서 타입과 data를 복원해 FCM 메시지에 싣는다.

## 2. 범위

### In scope

- `widyu-domain`: `NotificationType`, `DeliveryMode`(`PUSH_AND_CENTER/CENTER_ONLY/PUSH_ONLY/DATA_ONLY`), `RetentionClass`(`ROUTINE_90D/HEART_EMERGENCY_180D/SAFE_ZONE_90D`), `NotificationPriority`(`critical/timeSensitive/interaction/passive`), `PushSettingGroup`(`SAFETY/SAFE_ZONE/MEDICATION_CHECK/GENERAL/NONE`) enum. `FcmOutbox.notificationType`과 `dataPayload` 컬럼. type은 저장용 `FcmCategory`, 센터 필터, 전달 방식, 우선순위, 보존등급, 설정 그룹, 앱 사용 중 표시, 딥링크 템플릿, 문구 코드를 갖는다. `FcmCategory`에 값은 추가하지 않는다.
- `widyu-api`: `FcmSendDto`에 nullable type과 이벤트 메타데이터 추가. `FcmOutboxService.enqueue`는 호출자 `eventId`를 보존하거나 비어 있을 때 UUID를 생성해 type·data 전체를 저장한다. `FcmDelivery.from`은 이를 복원한다. `FcmMessageDto`는 Android notification/channel/priority와 APNs headers/payload를 표현한다. `FcmHttpTransport`는 data 키와 플랫폼별 긴급 표현을 조립한다. `NotificationCopy`는 문구표 v0.4의 type·역할·상황·OS/앱 내부 변형을 만든다.
- `scripts/mysql/alter_fcm_outbox_notification_type.sql`에 두 컬럼 DDL을 둔다. 구현 시 `docs/erd/ERD-0001-initial-domain.md`의 outbox 변경 이력과 `FcmHttpTransportTest`·`FcmOutboxTransactionsTest`를 갱신한다.

### Out of scope

- W3: 센터 행 생성·저장 단위, 토큰 없는 수신자 저장, `finish()` 변경, `notificationId`를 센터 행 ID로 전환, 센터 만료와 푸시 설정 독립성.
- W4: 알림센터 목록·읽음 API. W5: 푸시 설정 4분류의 저장·API·운영 마이그레이션.
- W6 이후: 개별 발행자 전환, 복약 정시 푸시 제거, 시니어 본인확인 순서, 자동전화. W2에서 `DeliveryMode`는 정책을 기술하되 기존 호출자의 outbox·센터 생성 동작은 바꾸지 않는다.

## 3. 인터페이스 / API

HTTP endpoint와 `ApiResponse`는 바뀌지 않는다. 변경 계약은 FCM HTTP v1 `message` JSON이다. data 값은 모두 문자열이다. 예시의 채널·interruption-level·딥링크 문자열은 FE 합의 전 **제안값**이다.

보호자 안전 알림(`HEART_RATE_EMERGENCY`, S04):

```json
{
  "message": {
    "token": "<device-token>",
    "notification": {"title": "홍길동 님의 심박 상태를 확인해주세요.", "body": "평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요."},
    "data": {
      "eventId": "8e7313ab-2c55-40f9-9b22-282d96846bdb",
      "type": "HEART_RATE_EMERGENCY", "priority": "critical",
      "notificationId": "4072", "deepLink": "widyu-care://seniors/17/location",
      "foregroundPresentation": "BANNER", "seniorId": "17"
    },
    "android": {"ttl": "300s", "priority": "high", "notification": {"channel_id": "widyu_safety"}},
    "apns": {"headers": {"apns-expiration": "1790806700", "apns-priority": "10", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "time-sensitive"}}}
  }
}
```

일반 알림(`MEDICATION_SCHEDULE_CHANGED`, M05):

```json
{
  "message": {
    "token": "<device-token>",
    "notification": {"title": "김보호 님이 약 알람을 변경했어요.", "body": "바뀐 약 알람은 내일부터 적용돼요."},
    "data": {
      "eventId": "940c15b9-79dc-4fa7-ab5d-27fca2cfa6cf",
      "type": "MEDICATION_SCHEDULE_CHANGED", "priority": "interaction",
      "notificationId": "4073", "deepLink": "widyu://medication/schedules",
      "foregroundPresentation": "BANNER", "revision": "42",
      "effectiveFromDate": "2026-10-02", "actorDisplayName": "김보호"
    },
    "android": {"ttl": "86400s", "priority": "normal", "notification": {"channel_id": "widyu_general"}},
    "apns": {"headers": {"apns-expiration": "1790892800", "apns-priority": "10", "apns-push-type": "alert"}, "payload": {"aps": {"interruption-level": "active"}}}
  }
}
```

FCM data 최소 키는 `eventId`, `type`, `priority`, `notificationId`, `deepLink`, `foregroundPresentation`이다. `revision`, `effectiveFromDate`, `actorDisplayName`은 해당 이벤트에만 추가하며, 목적지에 따라 `entityId`·`seniorId`도 실을 수 있다. `deepLink`가 없으면 빈 문자열을 보내고 FE router가 폴백한다. **W2의 `notificationId`는 아직 outbox 행 ID**다. W3가 센터 저장 타입은 센터 행 ID로 교체하고 센터 미저장 타입은 키를 생략한다. W2에서 한 enqueue 호출의 여러 기기는 같은 `eventId`를 공유하며, 호출자가 명시한 eventId가 있으면 우선한다. `DATA_ONLY`는 top-level `notification` 없이 보내고 APNs는 `apns-priority=5`, `apns-push-type=background`, `aps.content-available=1`을 쓴다.

## 4. 데이터 모델

`FcmOutbox`(`widyu-domain/com.widyu.fcm`)에 `notification_type VARCHAR(48) NULL`과 `data_payload TEXT NULL`을 더한다. enum 필드는 `@Enumerated(EnumType.STRING)`과 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 써 native ENUM 생성을 피한다. `data_payload`는 enqueue 시점의 data 맵 전체를 저장한 JSON 객체 문자열이다. 키·값은 문자열이며 빈 맵은 `{}`다. 기존 `data_type`·`data_revision` 컬럼은 레거시 복원을 위해 유지한다. 센터 테이블은 W3에서 바꾼다.

`FcmSendDto`(`widyu-api/fcm/dto`)의 기존 생성 경로와 type-null 기본값을 유지한다. 새 호출자는 type, 선택적 eventId·deepLink·entityId·seniorId·actorDisplayName·effectiveFromDate·groupKey를 준다. 동적 값은 data payload에 들어가 claim 뒤에도 유지된다. W2의 `PushSettingGroup`은 기존 `NotificationSettingGroup`(GOAL/ALBUM/HOME/ETC)과 구분하며 W5 전까지 판정에 쓰지 않는다. `NotificationCopy`(`widyu-api/fcm`)는 `from()`/`of()` 팩토리를 쓰고 H01·X01·X02의 OS/앱 내부 문구를 분리한다. 이름이 없으면 `가족`을 넣는다. 잠금화면에는 약 이름·복용량·일정명·병원 이름·주소·심박 수치·정확한 위치·사용자 메시지 원문을 넣지 않는다.

### `NotificationType` 전체 상수 표

괄호 안 센터 필터는 푸시 전용 타입의 목적지 분류이며 실제 센터 행이 없음을 뜻한다. `GENERAL` 등 설정 그룹은 W5부터 판정에 쓴다. 딥링크 문자열 전체는 FE 합의 전 제안이다. 표의 전달 방식은 최종 정책이며 W2에서 기존 발행자의 센터 생성 흐름을 바꾸지 않는다.

`legacyDataType`은 M08/M05/M09와 `MEDICATION_SCHEDULE_SYNC`만 `MEDICATION_SCHEDULE_CHANGED`이며 나머지는 null이다. 모든 typed FCM의 `data.notificationType`은 논리 타입 이름이고 `data.type`은 `legacyDataType`이 있으면 그 값, 없으면 논리 타입 이름이다.

| # | `NotificationType` | `FcmCategory` | 센터 필터 | `DeliveryMode` | priority 제안 | `RetentionClass` | settingGroup | foreground | 딥링크 템플릿 제안 | 문구 코드 |
| ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `ALBUM_UPLOAD_COMPLETE` | ALBUM | (ALBUM) | PUSH_ONLY | passive | ROUTINE_90D | GENERAL | NONE | `widyu://albums/{entityId}` | A01 |
| 2 | `ALBUM_CREATED` | ALBUM | ALBUM | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://albums/{entityId}` | A02-S/C |
| 3a | `ALBUM_COMMENTED` | ALBUM | ALBUM | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://albums/{entityId}/comments/{commentId}` | A03 |
| 3b | `ALBUM_REPLIED` | ALBUM | ALBUM | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://albums/{entityId}/comments/{commentId}` | A04 |
| 4 | `ALBUM_LIKED` | ALBUM | ALBUM | CENTER_ONLY | passive | ROUTINE_90D | NONE | NONE | `widyu://albums/{entityId}` | A05 |
| 5 | `ALBUM_UNLOCKED` | ALBUM | ALBUM | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu-care://albums/{entityId}` | A06-L/Z |
| 6 | `ALBUM_ALL_VIEWED` | ALBUM | ALBUM | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu-care://albums` | A07 |
| 8 | `MEDICATION_REMINDER_10` | MEDICINE_SCHEDULE | (GOAL) | PUSH_ONLY | timeSensitive | ROUTINE_90D | GENERAL | BANNER | `widyu://medication/proof/{entityId}` | M02 |
| 9 | `MEDICATION_REMINDER_20` | MEDICINE_SCHEDULE | (GOAL) | PUSH_ONLY | timeSensitive | ROUTINE_90D | GENERAL | BANNER | `widyu://medication/proof/{entityId}` | M03 |
| 10 | `MEDICATION_PROOF_MISSING` | MEDICINE_SCHEDULE | GOAL | PUSH_AND_CENTER | timeSensitive | ROUTINE_90D | MEDICATION_CHECK | BANNER | `widyu-care://seniors/{seniorId}/medication` | M04 |
| 11 | `MEDICATION_SCHEDULE_CHANGED` | MEDICINE_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://medication/schedules` | M05 |
| 12 | `HEALTH_SCHEDULE_UPCOMING` | HEALTH_SCHEDULE | GOAL | PUSH_AND_CENTER | timeSensitive | ROUTINE_90D | GENERAL | BANNER | `widyu://health/schedules/{entityId}` | H01-S/C-SELF/C-SENIOR-OS/INAPP |
| 13 | `WALK_GOAL_UNMET` | WALK | (GOAL) | PUSH_ONLY | passive | ROUTINE_90D | GENERAL | BANNER | `widyu://walk/goal` | W01 |
| 14a | `GOAL_ACHIEVED` | TARGET | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://goals/{entityId}` | G01-S/C |
| 14b | `GOALS_ACHIEVED_GROUPED` | TARGET | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://goals` | G02-S/C |
| 15a | `POINT_EARNED` | TARGET | — | CENTER_ONLY | passive | ROUTINE_90D | NONE | NONE | `widyu://points` | P01 |
| 15b | `POINT_USED` | TARGET | — | CENTER_ONLY | passive | ROUTINE_90D | NONE | NONE | `widyu://points` | P02 |
| 16 | `HEART_MESSAGE_RECEIVED` | HEART_MESSAGE | MESSAGE | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://messages/{entityId}` | X01-OS/INAPP |
| 17 | `CHEER_MESSAGE_RECEIVED` | HEART_MESSAGE | MESSAGE | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://messages/{entityId}` | X02-OS/INAPP |
| 17a | `SAFETY_SELF_CHECK` | INCIDENT_SELF_CHECK | — (푸시 전용) | PUSH_ONLY | critical | ROUTINE_90D | NONE | BANNER | `widyu://incident/{entityId}` (`entityId=incident_ref`) | S01/S02 |
| 18 | `HEART_RATE_EMERGENCY` | HEART_MESSAGE | LOCATION | PUSH_AND_CENTER | critical | HEART_EMERGENCY_180D | SAFETY | BANNER | `widyu-care://seniors/{seniorId}/location` | S01/S03/S04/S06 |
| 19 | `SAFE_ZONE_EXITED` | SAFE_ZONE | LOCATION | PUSH_AND_CENTER | critical | SAFE_ZONE_90D | SAFE_ZONE | BANNER | `widyu-care://seniors/{seniorId}/location` | S02/S03/S05/S07 |
| 20a | `SAFETY_SENIOR_OK_NOTICE_HEART` | HEART_MESSAGE | LOCATION | PUSH_AND_CENTER | interaction | HEART_EMERGENCY_180D | GENERAL | BANNER | `widyu-care://seniors/{seniorId}/location` | S08 |
| 20b | `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE` | SAFE_ZONE | LOCATION | PUSH_AND_CENTER | interaction | SAFE_ZONE_90D | GENERAL | BANNER | `widyu-care://seniors/{seniorId}/location` | S09 |
| 21 | `FAMILY_LEADER_CHANGED` | ETC | — (ALL·UNREAD만) | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu-care://family/manage` | R01 |
| 22 | `MEDICATION_SCHEDULE_CREATED` | MEDICINE_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://medication/schedules` | M08 |
| 23 | `MEDICATION_SCHEDULE_DELETED` | MEDICINE_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://medication/schedules` | M09 |
| 24 | `MEDICATION_SCHEDULE_SYNC` | MEDICINE_SCHEDULE | — | DATA_ONLY | passive | ROUTINE_90D | NONE | NONE | — | 없음 |
| 25 | `HEALTH_SCHEDULE_CREATED` | HEALTH_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://health/schedules/{entityId}` | H02 |
| 26 | `HEALTH_SCHEDULE_UPDATED` | HEALTH_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://health/schedules/{entityId}` | H03 |
| 27 | `HEALTH_SCHEDULE_DELETED` | HEALTH_SCHEDULE | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://goals` | H04 |
| 28 | `WALK_GOAL_CHANGED` | WALK | GOAL | PUSH_AND_CENTER | interaction | ROUTINE_90D | GENERAL | BANNER | `widyu://walk/goal` | W02 |

매트릭스 7번 `MEDICATION_DUE`(M01)는 시니어 기기 자체 알람이라 서버에 등록하지 않는다. S10은 취소 뒤 시니어 화면 상태 문구이며 서버 알림이 아니다. S03도 보호자 발송 개시 뒤 화면 문구다. `SAFETY_SELF_CHECK`의 `ROUTINE_90D`는 센터 행을 만들지 않는 PUSH_ONLY 타입의 필수 메타데이터 기본값이며 센터 보존 정책에는 쓰지 않는다. S08/S09는 원문의 단일 제안명 `SAFETY_SENIOR_OK_NOTICE`를 보존등급·문구별 두 상수로 나눈다. 심박 안전 타입의 `FcmCategory.HEART_MESSAGE`는 운영 컬럼을 바꾸지 않기 위한 값이고 센터 필터는 LOCATION이다.

## 5. 처리 흐름

1. 기존 호출자는 type 없이 현재 `FcmSendDto`를 만든다. 새 호출자는 type과 동적 data를 준다. `NotificationCopy`가 문구표 변형을 골라 OS용 문구와 앱 내부/센터용 문구를 구분한다.
2. 기존 `FcmOutboxService.enqueue` 트랜잭션에서 type이 있고 `eventId`가 null·빈 문자열·공백이면 수신자별 UUID를 한 번 생성한다. 호출자 값이 있으면 UUID 형식을 강제하지 않고 공백만인 값이 아니며 40자 이하인지 확인해 그대로 보존한다. 안전 이벤트의 `incident_ref`(예: `inc-…`)도 허용한다. type명은 typed 행에만 저장하고, 최종 data JSON은 type 유무와 관계없이 모든 새 토큰 행에 저장한다. `dataType`·`dataRevision`도 전환기 호환을 위해 계속 채운다. 커밋 후 dispatcher 제출 시점은 그대로다.
3. 기존 claim 트랜잭션에서 lease를 얻은 뒤 `FcmDelivery.from(row)`은 행의 `notificationType`·`dataPayload`로 DTO를 복원한다. 복원이 `IllegalArgumentException`으로 실패하면 `failed(false, Duration.ZERO, now, maxRetries)`로 EXHAUSTED 처리하고 전송하지 않는다. WARN에는 outbox ID·type명만 기록한다. 지금은 `dataType`·`dataRevision` 중 하나라도 NULL이면 data 전체가 `Map.of()`로 사라진다. `dataPayload=NULL`인 옛 행은 두 키 중 존재하는 것만 각각 복원해 이 유실을 고친다.
4. `FcmHttpTransport.execute`는 DB 트랜잭션 밖에서 data와 플랫폼 헤더를 조립한다. 안전 세 타입(본인확인 포함)은 Android high·안전 채널, APNs priority 10·time-sensitive를 쓴다. S08/S09는 일반 표시다. `DATA_ONLY`는 top-level `notification` 없이 data만 싣는다. 레거시 `emergency` TTL·preflight·fence는 유지한다.
5. 동일 outbox 행의 재시도는 저장된 eventId·data를 그대로 쓴다. W3가 이 eventId를 센터 행에 저장하고 notificationId 의미를 전환한다. W3 전에는 `finish()`의 기존 센터 행 생성이 유지된다. 새 동기 `@EventListener`나 `@Async` 경로는 만들지 않는다.

## 6. 예외 / 에러 처리

- type null인 레거시 호출자와 이전 outbox 행은 기존 제목·본문·TTL·센터 저장 및 유효한 FCM data 동작을 유지한다. 다만 부분 data(`dataType`·`dataRevision` 중 하나만 존재)는 유실 버그를 고쳐 존재하는 키를 전송한다. 새 안전 채널을 `emergency` boolean만으로 추정하지 않는다.
- 손상된 `data_payload` 때문에 `FcmDelivery.from`에서 `IllegalArgumentException`이 나면 claim 트랜잭션에서 EXHAUSTED로 마감한다. WARN에는 outbox ID·type 문자열만 남기며 data 내용은 남기지 않는다. DB의 미등록 `notification_type`은 JPA 엔티티 로드 단계에서 실패해 claim까지 오지 못하고 lease 회수마다 재시도된다. 구버전 앱으로 롤백하기 전 8절의 미등록 타입 행 취소 절차를 적용한다.
- type 있는 호출자의 필수 data는 enqueue 전에 검증한다. 딥링크 대상이 없으면 `deepLink=""`로 전송하고 FE router가 폴백한다. 새 HTTP 오류 코드는 없다. 기존 429/5xx·timeout·영구 토큰·만료 정책은 유지한다.
- H2는 MySQL native ENUM을 재현하지 못하므로 운영 DDL은 MySQL에서 별도 확인한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] N3: 32개 서버 `NotificationType` 상수의 카테고리·필터·딥링크가 4절 표와 일치한다. `SAFETY_SELF_CHECK`는 `INCIDENT_SELF_CHECK`·센터 필터 null·`PUSH_ONLY`·`CRITICAL`·`NONE`·`BANNER`·`widyu://incident/{entityId}`·S01/S02를 사용한다. `FAMILY_LEADER_CHANGED`의 센터 필터는 null이다. M01·S10은 서버 FCM 타입으로 발송하지 않고 `FcmCategory` 상수는 늘지 않는다.
- [x] N7: 네 `DeliveryMode`를 타입별로 조회할 수 있으며 4절 전달 정책과 일치한다. W2 구현은 기존 호출자의 센터 저장 단위를 바꾸지 않는다.
- [x] N8: type 있는 enqueue는 eventId가 비어 있을 때 수신자별 UUID를 생성한다. 명시값이 있으면 UUID 형식과 무관하게 40자 이하의 공백 아닌 값(예: `inc-…`)을 보존하며, 40자 초과는 행을 저장하지 않는다. 같은 수신자의 여러 토큰 행에 동일한 값을 기록한다. FCM data의 `eventId/type/priority/notificationId/deepLink/foregroundPresentation`과 해당 이벤트의 `revision/effectiveFromDate/actorDisplayName`이 문자열로 보존된다. W2 notificationId는 outbox ID다.
- [x] N8: `FcmDelivery.from`은 `data_payload` 전체를 복원한다. 같은 행의 재시도 2회 뒤에도 key/value가 같고, 이전 행은 `dataType`·`dataRevision` 중 존재하는 키를 각각 복원한다.
- [x] 복원 시 `IllegalArgumentException`이 발생하면 claim 행은 EXHAUSTED가 되고 전송하지 않는다. WARN에는 outbox ID·type 문자열만 남긴다.
- [x] N9: 안전 세 타입(`SAFETY_SELF_CHECK` 포함)은 Android high·안전 채널과 APNs priority 10·time-sensitive를 포함한다. S08/S09는 일반 우선순위·일반 채널이다.
- [x] `NotificationCopy`는 문구표 v0.4 변형과 이름 부재 시 `가족`을 적용하며 OS 문구에 민감 정보·메시지 원문을 넣지 않는다.
- [x] type 없는 호출자의 title/body/유효한 data, TTL, `emergency`, notificationId, 센터 저장 동작은 불변이다. 레거시 부분 data는 존재하는 키를 전송하는 버그 수정만 허용한다.
- [x] `FcmHttpTransportTest`는 본인확인을 포함한 안전·일반·data-only JSON을, `FcmOutboxTransactionsTest`와 복원 테스트는 type·data의 claim/재시도·손상 payload EXHAUSTED·레거시 폴백을 검증한다. JUnit 5·BDDMockito `given/willReturn`, 한글 언더스코어 메서드, 행위형 `@DisplayName`, 상태 검증을 우선한다.
- [x] 엔티티 변경 뒤 `./gradlew compileJava`와 Domain+API 테스트를 실행하고 `bash scripts/harness/verify.sh`가 통과한다. ERD outbox 컬럼·DDL 이력을 갱신한다.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/alter_fcm_outbox_notification_type.sql`에는 다음 DDL을 둔다. 기존 행의 두 컬럼은 NULL이고 Java 폴백으로 읽는다. `FcmCategory` 컬럼을 ALTER하거나 값을 추가하지 않으며 `data_type`·`data_revision`도 유지한다.

```sql
ALTER TABLE fcm_outbox
    ADD COLUMN notification_type VARCHAR(48) NULL,
    ADD COLUMN data_payload TEXT NULL;
```

운영 순서: ① `SHOW COLUMNS FROM fcm_outbox`로 기존 컬럼·DDL 중복 확인 및 백업 ② 쓰기 트래픽과 DDL 잠금 영향 확인 뒤 스크립트 적용 ③ `ddl-auto=validate`인 새 앱 배포 ④ typed data·플랫폼 헤더와 레거시 행 재시도 관찰. 구버전 앱으로 롤백하기 전에는 구버전 enum에 없는 타입의 대기·선점 행을 먼저 취소한다: `UPDATE fcm_outbox SET state='CANCELLED' WHERE state IN ('PENDING','CLAIMED') AND notification_type NOT IN (<구버전 enum 목록>);`. enum 목록은 배포 대상 구버전에서 확인해 SQL 문자열 리터럴로 치환한다. 그 뒤 앱을 되돌리고 nullable 컬럼은 보존한다. W3의 센터 행 스키마·notificationId 전환은 별도 배포 순서로 관리한다.

## 9. 미결정 사항 (Open Questions)

2026-10-01 구현은 아래 제안값을 적용했다. FE 합의가 끝나면 채널·딥링크·wire type 계약을 갱신한다. 계획 §5의 작업 가정은 그대로 유지한다.

- [ ] **FE 합의 필요:** Android 채널 ID `widyu_safety`·`widyu_general`, iOS `time-sensitive`·`active`·`passive` interruption-level, priority 매핑은 계획 §5의 제안값이다. 구현 제안은 `critical/timeSensitive→time-sensitive`, `interaction→active`, `passive→passive`이며 Android 전달 priority는 앞의 두 등급에 high, 나머지에 normal이다. iOS `critical` 권한은 사용하지 않는다.
- [ ] **FE 합의 필요:** 4절의 딥링크 문자열과 `{entityId}`·`{seniorId}` 경로 변수는 제안값이다. 역할별 화면 계약을 FE와 고정한다.
- [ ] **FE 합의 필요:** R01 `FAMILY_LEADER_CHANGED`의 센터 필터는 null로 두어 ALL·UNREAD에만 노출한다. FE와 노출 필터 계약을 고정한다.
- [ ] **FE 합의 필요:** `SAFETY_SELF_CHECK`의 딥링크 `widyu://incident/{entityId}`에서 `entityId=incident_ref`로 사용한다. IncidentService의 현재 scheme과 같으며 FE router 계약을 고정한다.
- [ ] **FE 합의 필요:** 원 제안명 `SAFETY_SENIOR_OK_NOTICE`를 보존등급·문구별 `SAFETY_SENIOR_OK_NOTICE_HEART`(S08)·`SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE`(S09) wire type으로 분리했다. 앱 수신 파서·라우터와 합의한다.

계획 §5의 회신 대기 항목과 작업 가정을 아래에 옮긴다. W2 밖의 항목도 후속 LLD가 같은 기준을 확인할 수 있도록 유지한다. 가정을 바꾸는 회신이 오면 담당 Task의 결정 게이트에서 갱신한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
| --- | --- | --- | --- |
| T1-① 1분 기준 시각·경계 | 기준=서버 사건 생성 시각, 마감=+60s, 정확히 마감=초과(B §3 제안 수용) | 그대로 | — |
| T1-② HELP 값 유지 | 유지 제안(워치 호환, ESCALATED → 스케줄러가 INITIAL_ALERT) | 유지 | — |
| T1-③ FCM 전체 실패 시 동작·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, **INITIAL_ALERT는 그대로 1분에 발송**(시니어 미수신이 보호자 알림을 미루지 않음) | 그대로 | — |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공 시각으로 덮는 후속) | 그대로 | W12 |
| S10 표시 시간 | FE·제품 몫 | — | — |
| T2 Incident kind ↔ 제품 이벤트 | `HR_ANOMALY↔HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXIT↔SAFE_ZONE_EXITED`, `FALL_SUSPECTED`는 범위 밖(AI 입구 꺼짐) | 그대로 | — |
| T2 run 없는 경로 | `decision_id` null 허용, `incidents.jsonl`에는 run 귀속 사건만 | 그대로 | — |
| T3 자동전화 제공자·콜백·푸시 OFF 비방장 | 미정 → 구현 보류 | — | **W12 전면** |
| BE 발송본 5 「같은 사건 묶는 범위」 | 열린 사건 + 마지막 감지 5분 창, 안심구역은 재진입으로 종료 | ADR-0038 S-D6 | — |
| BE 발송본 6 「판단 단위 저장」 | 이미 저장됨(`heart_rate_event.status` 샘플별) | — | — |
| ADR-0035 결정 3 「알림=FCM 성공」 | INITIAL_ALERT 기준으로 좁힘, 60초 안 OK는 `alert_delivered=false`가 정상 | S-D10 | — |
| W02 적용 시작일 | 현행 「다음 날」 유지, 문구에 넣지 않음 | 그대로 | — |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |
| 시니어 푸시 설정 항목 | `GENERAL` 하나 | 그대로 | — |
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |
| G02 동시 달성 묶음 | 단일 트리거가 목표 2개를 동시에 적립하는 경로가 없어 G01만 발생 | G01만 | — |
| A 활성화 조건 | 카드·10p는 플래그 OFF로 머지, IRB 확인 뒤 ON | — | — |

## 10. 참고

- ADR-0037·ADR-0038(이 브랜치에 없어 feature-691에서 열람), 승인판 `HEMLO-REVIEW-v0.5`(충돌 시 우선), `FE-HANDOFF-v0.4` §2·§4·§6, `FE-DELIVERY-PACKAGE-v1.2` §7·§10·§11, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §5·§9·§12, `NOTIFICATION-COPY-CATALOG-v0.4` §1~§9.
- `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§2 W2·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` N3·N7·N8·N9.
- [Firebase FCM HTTP v1 메시지 참조](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages): data-only iOS 메시지는 APNs priority 5를 사용하고 Android 채널은 앱이 생성해야 한다.
