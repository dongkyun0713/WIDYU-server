# LLD-0062: 수신자×이벤트 단일 알림센터 행

> Low-Level Design. 이 문서는 이슈 #696 구현과 PR 본문의 기준이다. W2(`feature/692`, LLD-0060)의 `NotificationType`, `FcmSendDto.eventId`, `fcm_outbox.notification_type/data_payload`를 전제로 한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #696 |
| 관련 ADR | ADR-0037 결정 2·3·4·6, ADR-0038, ADR-0028(개정) |
| 작성자 | Codex / dongkyun0713/feature-696 |
| 작성일 | 2026-10-01 |

## 1. 목적 / 배경

현재 FCM 전송 성공 토큰마다 `fcm_notification` 행이 생겨 같은 제품 이벤트가 기기 수만큼 중복된다. 토큰이 없거나 푸시 설정을 끄면 센터에도 기록되지 않는다. 센터 행을 enqueue 시점에 수신자와 이벤트 키로 한 번 저장하고, 기기별 outbox는 그 행을 참조하게 한다.

## 2. 범위

### In scope

- `widyu-domain`: `FcmNotification`에 `eventId VARCHAR(40)`, `type VARCHAR(48)`, `deepLink`, `entityId`, `seniorId`, `actorDisplayName`, `expiresAt`, `retentionPolicyVersion`, `pushEligible`, `readAt` 추가. `memberFcmToken` FK를 nullable로 바꾸고 `UK(recipient_member_id,event_id)`를 둔다. `FcmOutbox`에 nullable `notificationId` FK 추가. enum 컬럼에는 `@JdbcTypeCode(SqlTypes.VARCHAR)` 사용.
- `widyu-api`: `FcmOutboxService.enqueue`의 토큰 루프 **앞**에서 센터 행 생성·재사용. `PUSH_AND_CENTER`는 센터+토큰별 outbox, `CENTER_ONLY`는 센터만, `PUSH_ONLY`는 outbox만, `DATA_ONLY`는 data 메시지용 outbox만 생성. `FcmOutboxTransactions.finish`의 센터 `save` 제거, `markDecisionDelivered` 유지.
- `widyu-api`: `FcmDelivery`가 센터 `notificationId`를 복원하고 `FcmHttpTransport`가 `data.notificationId`에 **센터 행 ID**를 사용. 센터 미저장 타입에서는 키 생략. `FcmEligibility`는 claim/preflight에서만 푸시 설정을 판정하며 `DATA_ONLY`는 설정 판정을 건너뜀. 센터 저장은 설정 판정과 분리. `FcmNotificationResponse.from`의 `scheme`을 저장된 `deepLink`에서 채움; type 없는 기존 행은 기존 응답값 유지.
- `FcmNotificationRepository` 멱등 조회와 기존 읽음 갱신, `FcmOutboxTransactionsTest` 등 테스트, `scripts/mysql/alter_fcm_notification_center.sql`, `docs/erd/ERD-0001-initial-domain.md`. 변경점 N1·N2·N3b·N6·N8·N14.

### Out of scope

- W4 목록 API v2(`GET/PATCH /api/v1/notifications`), 필터·커서·만료 제외 쿼리·새 응답 필드 전체. 기존 `/api/v1/fcm` 계약 유지.
- W5 푸시 설정 4분류와 `type.settingGroup()` 판정 전환. 이번 단계에서는 현행 설정 판정이 claim/preflight에 남는다.
- W6 이후 발행자별 타입 전환, W11 사건 생성·`incident_ref` 연결과 `INITIAL_ALERT/FINAL_ESCALATION` 같은 행 갱신. `group_key`·`target_unavailable_at`은 소비자가 생길 때 추가.

## 3. 인터페이스 / API

새 HTTP endpoint는 없다. 기존 `/api/v1/fcm`의 `ApiResponse` 형태와 읽기·읽음 경로를 유지한다. 새 행의 구 목록 응답에서 `scheme`은 `deep_link`다. type 없는 기존 행의 `scheme`은 기존 빈 문자열이며 다른 필드와 조회 가능성은 그대로다. W4 응답의 `priority`, `retentionClass`, `foregroundPresentation`, `centerStored`는 `NotificationType`에서 계산한다.

서비스 입력은 `enqueue(recipientId, FcmSendDto)`이고 호출자는 W2의 `FcmSendDto.eventId`로 키를 지정할 수 있다. 타입 있는 호출자에서 키가 비면 **W2가 만든 수신자별 UUID를 그대로** 센터 `event_id`와 `data.eventId`에 쓴다. W3에서 별도 키를 만들지 않는다. W11 안전 이벤트는 `incident_ref`(40자 이내)를 지정할 수 있어야 한다. `INITIAL_ALERT`와 `FINAL_ESCALATION`은 같은 키, S08/S09는 `incident_ref:OK`를 사용한다(W11 구현 범위).

센터 저장 타입의 FCM data 예시(모든 값은 문자열):

```json
{
  "eventId": "inc-0123456789abcdef0123456789abcdef",
  "type": "HEART_RATE_EMERGENCY",
  "priority": "critical",
  "notificationId": "4072",
  "deepLink": "widyu-care://seniors/17/location",
  "foregroundPresentation": "BANNER",
  "seniorId": "17"
}
```

`notificationId=4072`는 `fcm_notification.id`이며 같은 수신자의 여러 토큰·재시도에서 같다. 센터 미저장 타입은 `notificationId`를 생략하고 `eventId`는 유지한다. W2 `data_payload`의 동적 키(`revision`, `effectiveFromDate`, `actorDisplayName` 등)는 보존한다. 전송 시 센터 FK가 있으면 `notificationId`를 덮어 써 과거 outbox ID가 남지 않게 한다. W3 이전 outbox 중 센터 저장 타입·type-null 행은 FK가 NULL이므로 outbox ID 폴백을 유지한다.

## 4. 데이터 모델

`FcmNotification`·`FcmOutbox`는 `widyu-domain/com.widyu.fcm`, 응답 DTO·repository는 `widyu-api/com.widyu.fcm`에 둔다. 추가·변경 컬럼만 기록한다.

| 테이블·컬럼 | 타입 / nullable | 새 행과 legacy 행 의미 |
| --- | --- | --- |
| `fcm_notification.event_id` | `VARCHAR(40) NULL` | 새 센터 행 필수. 기존 행은 NULL이며 UK에서 서로 충돌하지 않는다. W2 eventId 또는 호출자 지정 키. |
| `fcm_notification.type` | `VARCHAR(48) NULL` | 새 타입 행은 `NotificationType` 이름. 기존·type 없는 호출자 행은 NULL, 기존 `FcmCategory`로 응답. |
| `fcm_notification.deep_link` | `VARCHAR(255) NULL` | W2가 해석한 목적지. 기존 행 NULL은 `scheme=""` 응답. |
| `fcm_notification.entity_id` | `VARCHAR(255) NULL` | 대상 식별자. 해당 없는 이벤트·기존 행은 NULL. |
| `fcm_notification.senior_id` | `BIGINT NULL` | 대상 시니어 ID. 해당 없는 이벤트·기존 행은 NULL. |
| `fcm_notification.actor_display_name` | `VARCHAR(255) NULL` | 보호자 변경 알림의 이름. 해당 없는 이벤트·기존 행은 NULL. |
| `fcm_notification.expires_at` | `DATETIME(6) NULL` | 새 타입 행은 enqueue의 `now(≈createdAt) + type.retentionClass().duration()`. 기존 NULL 행은 만료 필터에서 계속 보인다. |
| `fcm_notification.retention_policy_version` | `VARCHAR(32) NULL` | 새 타입 행은 상수 `v1`. 정책 변경 시 기존 만료를 소급 변경하지 않는다. 기존 행 NULL. |
| `fcm_notification.push_eligible` | `BOOLEAN NULL` | enqueue 시점 타입 정책의 push 가능 여부(`PUSH_AND_CENTER=true`, `CENTER_ONLY=false`). 개인 설정·OS 권한·전송 결과 **적용 전** 스냅샷. 기존 행 NULL. |
| `fcm_notification.read_at` | `DATETIME(6) NULL` | 새로 읽을 때 채운다. 기존 `is_read=true, read_at=NULL`은 읽힌 상태로 유지. |
| `fcm_notification.memberFcmToken_id` | `BIGINT NULL` | 새 센터 행은 NULL. 과거 토큰별 이력 FK는 보존. |
| `fcm_outbox.notification_id` | `BIGINT NULL`, 센터 FK | 센터 저장 타입은 같은 센터 행 참조. 기존 outbox와 센터 미저장 타입은 NULL. |

새 센터 행의 `recipient_member_id`는 필수지만 원수신자 불명 기존 행을 위해 DB nullable은 유지한다. `UK(recipient_member_id,event_id)`가 새 행의 단일성을 보장한다. `decision_id`는 센터 행 생성 시 저장하고 연구 철회 연결을 유지한다. `fcm_outbox.expires_at`은 FCM 재시도 기한이고 센터 `expires_at`은 표시 보존 기한이다.

`priority`, `retentionClass`, `foregroundPresentation`, `centerStored`는 컬럼으로 저장하지 않고 W4 응답 시 type enum에서 읽는다. `pushEligible`은 enqueue 정책 스냅샷이므로 저장한다. S08/S09는 W2의 두 상수(`SAFETY_SENIOR_OK_NOTICE_HEART`, `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE`)를 사용해 각각 180일/90일 보존한다. `FcmCategory` 값은 추가하지 않는다.

## 5. 처리 흐름

1. 업무 쓰기 트랜잭션에서 `enqueue`가 시작된다(`REQUIRED`). 수신자 ACTIVE를 확인한다. 관련 회원이 있고 수신자와 다르면 그 회원의 ACTIVE와 같은 가족 관계를 센터 저장 **전에** 확인하고, 아니면 센터·outbox 모두 만들지 않는다. 가족 ID 스냅샷은 기존 방식대로 계산하고 claim/preflight에서도 가족 관계를 재검증한다. 센터 생성에 토큰·개인 푸시 설정·OS 권한을 요구하지 않는다.
2. 타입이 있으면 W2의 `message.eventId`를 우선하고 비어 있으면 **기존 W2 분기에서 한 번 만든** UUID를 사용한다. 같은 값을 `dataForEnqueue(eventId)`와 센터 `event_id`에 쓴다. type 없는 기존 호출자는 센터 이력 호환을 위해 수신자별 UUID를 한 번 만들되 FCM data 계약은 바꾸지 않는다. 반복 enqueue의 멱등 키가 필요한 호출자는 `eventId`를 명시한다.
3. `PUSH_AND_CENTER`·`CENTER_ONLY` 또는 type-null 센터 호출자는 토큰 조회 전에 `(recipientId,eventId)`로 행을 조회한다. 있으면 기존 행을 **수정 없이** 재사용한다. 없으면 새 행을 저장한다. `UK(recipient_member_id,event_id)`가 동시 삽입의 단일성을 보장한다. 충돌한 업무 트랜잭션은 실패하고 호출자가 같은 eventId로 재시도하면 기존 행을 재사용한다. 실패한 트랜잭션 안에서 `DataIntegrityViolationException`을 삼키지 않는다. 새 행의 `expiresAt`은 enqueue가 가진 `now + retentionClass 기간`으로 산정한다.
4. `PUSH_AND_CENTER`·`PUSH_ONLY`·`DATA_ONLY`는 푸시 설정을 검사하지 않고 활성 토큰마다 outbox를 저장한다. 센터 저장 타입의 각 outbox는 같은 `notification_id`를 갖는다. `CENTER_ONLY`는 outbox 0건이고, `PUSH_ONLY`·`DATA_ONLY`는 센터 0건·FK NULL이다. `DATA_ONLY`는 화면 알림 본문 없이 data만 보내며 설정 OFF여도 전송한다. type-null 호출자는 센터 행과 토큰별 outbox를 유지한다.
5. 커밋 뒤에만 토큰별 dispatcher를 깨운다. 롤백 시 센터·outbox·wakeup이 함께 취소된다. claim과 preflight는 각각 `REQUIRES_NEW`에서 `FcmEligibility`로 ACTIVE·토큰 owner·가족·현행 푸시 설정을 재검증한다. 설정 OFF면 outbox가 CANCELLED 되지만 센터 행은 남긴다(N14). `notificationType.deliveryMode()==DATA_ONLY`이면 두 판정 지점에서 설정 검사만 건너뛰고 ACTIVE·토큰 owner·가족 검사는 유지한다. W5의 settingGroup 전환 시 이 분기를 흡수한다.
6. `FcmDelivery.from(row)`는 W2 `data_payload` 전체와 센터 FK를 복원한다. `FcmHttpTransport`는 센터 FK가 있는 새 outbox에서 `data.notificationId=notification_id`를 직렬화한다. FK가 NULL이고 type-null이거나 type의 전달 방식이 센터 저장인 **W3 이전 outbox**는 outbox ID 폴백을 쓴다. `PUSH_ONLY`·`DATA_ONLY`는 센터 ID가 없으므로 키를 생략한다. 같은 outbox 재시도는 저장된 eventId·센터 FK를 재사용한다.
7. `finish` 성공은 outbox를 `SENT`로 바꾸고 `markDecisionDelivered`를 실행한다. 센터 `save`는 호출하지 않는다. `markDeliveredIfFirst`의 `alertId`는 계속 **`fcm-` + outbox ID**다. 실패·만료·영구 토큰 처리와 fence 조건은 ADR-0028을 따른다. 읽음 처리 시 `isRead`와 `readAt`을 함께 갱신하며 기존 `isRead=true/readAt=NULL`은 읽힌 상태로 유지한다.

새 `@EventListener`·`@Async` 경로는 없다. 실제 FCM HTTP는 DB 트랜잭션 밖에서 수행한다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
| --- | --- |
| 수신자 비활성 | 센터·outbox 없이 종료. claim/preflight에서도 재검증. |
| 관련 회원 비활성·가족 불일치 | enqueue에서 센터·outbox 없이 종료. enqueue 뒤 관계 변화는 claim/preflight에서 다시 차단. |
| 같은 `(recipientId,eventId)` 재enqueue | 기존 센터 ID·내용·읽음·만료 불변. 다른 수신자의 같은 키는 각각 별도 행. 기기별 outbox는 기존 at-least-once 성질 유지. |
| 동시 UK 충돌 | 쓰기 트랜잭션 롤백 후 같은 eventId로 전체 enqueue 재시도하여 기존 행 재사용. |
| 토큰 없음·설정 OFF·FCM 실패 | 센터 저장 타입의 행은 보존. 토큰 없음이면 outbox 0. 설정 OFF여도 토큰별 outbox를 만들고 claim/preflight에서 CANCELLED. FCM 실패는 기존 재시도 정책. |
| 과거 `event_id/type/expires_at` NULL | 기존 수신자 조회 유지. `recipient_member_id=NULL` 이력은 숨기고 토큰 현재 소유자로 추정하지 않음. |
| 잘못된 eventId 또는 저장 실패 | W2의 40자 제한 적용, 트랜잭션 롤백. 새 HTTP 오류 코드 없음. |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 센터 저장 타입의 활성 토큰 0개면 센터 1건·outbox 0건이다. 설정 OFF면 센터 1건이고 생성된 토큰별 outbox는 claim/preflight에서 CANCELLED다.
- [x] AC2: 활성 토큰 2개면 센터 1건·outbox 2건이고 두 outbox의 `notification_id`·`eventId`가 같다.
- [x] AC3: `finish` 성공은 센터 행을 추가하지 않고 outbox를 `SENT`로 만든다. 판정 기록은 `decision_id`와 `fcm-<outbox id>`로 갱신한다. `FcmOutboxTransactionsTest`의 「전송이 성공하면 그 판정에 알림 도달 사실이 채워진다」와 판정 없는 성공 테스트에서 센터 `save` 기대를 제거한다.
- [x] AC4: 동일 수신자·`event_id` 재enqueue·동시 호출에도 센터 1건이며 기존 ID·제목·본문·읽음·만료가 불변이다. 다른 수신자의 같은 키는 각각 1건이다.
- [x] AC5: `PUSH_ONLY` 센터 0·토큰별 outbox, `CENTER_ONLY` 센터 1·outbox 0, `DATA_ONLY` 센터 0·data 전용 outbox이며 설정 OFF여도 전송한다.
- [x] AC6: 새 센터 outbox의 FCM `data.notificationId`는 센터 행 ID로 토큰·재시도 사이에 같다. 센터 미저장 타입은 이 키가 없고 `eventId`는 있다. W3 이전 type-null·센터 저장 타입 outbox는 outbox ID 폴백으로 전송 가능하다.
- [x] AC7: 새 행은 `expiresAt=enqueue now+retentionClass 기간`, `retentionPolicyVersion=v1`, 토큰 FK NULL, enqueue 시 `decision_id` 저장이다. S08은 180일, S09는 90일. `pushEligible`은 개인 설정과 무관한 타입 정책값이다.
- [x] AC8: `type=NULL/expires_at=NULL` 기존 센터 행은 구 목록 API에서 보이고 기존 읽음·category·title·body·image·createdAt 응답을 유지한다. 새 행 `scheme=deepLink`다.
- [x] AC9: JUnit 5·Mockito(BDDMockito `given/willReturn`), 한글 언더스코어 메서드, 행위형 `@DisplayName`, 상태 검증 우선. H2는 MySQL native ENUM 검증으로 간주하지 않는다.
- [x] AC10: 엔티티 변경 뒤 `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과하고 DDL·ERD를 갱신한다.
- [x] AC11: 관련 회원이 비활성이거나 수신자와 가족이 다르면 enqueue가 센터·outbox를 모두 남기지 않는다. 같은 가족이면 센터 1건을 저장하며 claim/preflight는 이후 관계 변화를 계속 차단한다.

## 8. 영향 범위 / 마이그레이션

운영 DDL은 `scripts/mysql/alter_fcm_notification_center.sql`에 둔다. 순서는 ① 운영 `SHOW CREATE TABLE`로 현재 컬럼·FK·인덱스와 중복 확인, 백업 ② `fcm_notification` nullable 컬럼 추가·토큰 FK NULL 허용·`UK(recipient_member_id,event_id)` 추가 ③ `fcm_outbox.notification_id` nullable 컬럼 및 센터 FK 추가 ④ `ddl-auto=validate` 앱 배포 ⑤ 토큰 0/2개·설정 OFF·typed/legacy 관찰이다. 기존 행은 임의로 backfill하지 않는다. 정확한 토큰 컬럼·FK 이름은 운영 스키마에 맞춰 DDL 작성 시 확인한다.

운영 `fcm_notification.fcm_category`·`fcm_outbox.fcm_category`가 native ENUM이어도 **값·컬럼 정의를 건드리지 않는다**. `FcmCategory`도 불변이다. 새 type은 `VARCHAR(48)`이고 Java에는 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 붙인다. H2 테스트는 운영 MySQL DDL의 대체 검증이 아니므로 운영 DB에서 스크립트를 별도 확인한다.

ADR-0028·LLD-0036의 `finish 성공 → 센터 행 저장`과 `data.notificationId=outbox ID` 설명은 이 LLD가 개정한다. lease/fence, 재시도, claim/preflight, 트랜잭션 밖 HTTP, 판정 `alertId=fcm-<outbox ID>`는 유지한다.

## 9. 미결정 사항 (Open Questions)

W3 구현·자체 검수 시점에도 저장 단위의 추가 미결정 사항은 없다. 계획 §5의 회신 대기 항목과 구현 가정은 아래와 같이 유지한다. 바뀌는 회신은 담당 W의 결정 게이트에서 처리한다. 운영 MySQL에 DDL을 적용한 결과는 아직 확인하지 않았다(미결정 정책이 아닌 배포 검증).

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
| --- | --- | --- | --- |
| T1-① 1분 기준 시각·경계 | 기준=서버 사건 생성 시각, 마감=+60s, 정확히 마감=초과(B §3 제안 수용) | 그대로 | — |
| T1-② HELP 값 유지 | 유지 제안(워치 호환, ESCALATED → 스케줄러가 INITIAL_ALERT) | 유지 | — |
| T1-③ FCM 전체 실패 시 동작·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, **INITIAL_ALERT는 그대로 1분에 발송**(시니어 미수신이 보호자 알림을 미루지 않음) | 그대로 | — |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속) | 그대로 | W12 |
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

- ADR-0037 결정 2·3·4·6, ADR-0038(이 worktree에는 없어 `feature-691/docs/adr/`에서 열람), [ADR-0028](../adr/ADR-0028-fcm-durable-delivery.md), [LLD-0036](LLD-0036-fcm-durable-delivery.md), [LLD-0060](LLD-0060-notification-type-registry.md).
- 55차 승인판 `HEMLO-REVIEW-v0.5`(충돌 시 우선), `FE-HANDOFF-v0.4` §3·§6·§8, `FE-DELIVERY-PACKAGE-v1.2` §7·§12, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §9·§12, `NOTIFICATION-COPY-CATALOG-v0.4` S08·S09.
- `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§2 W3·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` N1·N2·N3b·N6·N8·N14.
