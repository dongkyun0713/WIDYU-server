# LLD-0067: 알림센터 목록 API v2

> Low-Level Design. 이 문서는 이슈 #703 구현과 PR 검수의 기준이다. LLD-0060(타입 레지스트리)과 LLD-0062(수신자×이벤트 센터 행)를 전제로 한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #703 |
| 관련 ADR | ADR-0037 결정 6·7, ADR-0038 |
| 작성자 | Codex / feature/703 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

기존 `/api/v1/fcm` 목록은 잘못된 카테고리를 전체 목록으로 바꾸고, 안 읽은 수를 별도 요청으로 반환한다. 앱별 허용 필터, 만료 제외, 커서 페이지, 안 읽은 수를 한 응답에서 제공하는 알림센터 전용 API를 추가한다. 기존 API는 앱 전환 기간 동안 유지한다.

## 2. 범위

### In scope

- `widyu-api`: 새 `com.widyu.notification` 패키지에 `controller/NotificationCenterController`, `controller/docs/NotificationCenterDocs`, `application/NotificationCenterService`, 필터와 `dto/response/NotificationEnvelope`·목록 응답을 둔다. 조회 쿼리는 기존 `com.widyu.fcm.repository.FcmNotificationRepository`에 추가한다.
- `widyu-domain`: `ErrorCode`에 필터 오류 `FCM_4002`와 커서 형식 오류 `FCM_4003`(둘 다 400)을 추가한다. 엔티티와 스키마는 바꾸지 않는다.
- 코디네이터가 승인한 W2 정정: `NotificationType.POINT_EARNED/POINT_USED.centerFilter`를 null로 고치고 LLD-0060의 두 행을 맞춘다. 그 외 W2 코드는 변경하지 않는다.
- 변경점 N4·N5: 역할별 필터, 같은 읽기 트랜잭션의 목록·안 읽은 수, 단건 읽음 멱등성.

### Out of scope

- 기존 `GET/PATCH /api/v1/fcm` 및 `/categories` 변경·삭제.
- 모두 읽기, 읽지 않음으로 되돌리기, 항목 삭제, `기타` 필터, 푸시 설정 개편.
- 센터 행 생성·보존기간 산정·FCM 발송 정책 변경.

## 3. 인터페이스 / API

```http
GET   /api/v1/notifications?filter=ALL&cursor=<opaque>
PATCH /api/v1/notifications/{id}/read
```

`filter` 생략 시 `ALL`이다. 시니어는 `ALL|UNREAD|ALBUM|GOAL|MESSAGE`, 보호자는 여기에 `LOCATION`을 더 허용한다. 단일 필터만 받으며 `UNREAD`와 카테고리를 조합하지 않는다. 첫 페이지의 `cursor`는 생략한다. 페이지 크기는 기존 목록과 같은 10건이고 11건을 읽어 다음 페이지를 판정한다. 커서는 FE가 해석하지 않는 문자열이며 이번 구현의 값은 경계 행 ID의 10진수 문자열이다. 서버 정렬은 `id DESC`다. `occurredAt`은 저장된 `createdAt`을 그대로 반환한다.

성공 응답은 실제 `ApiResponseTemplate`의 `data` 필드에 다음 구조를 싣는다. `notificationId`는 JSON 숫자이고, `nextCursor`는 문자열 또는 null이다. 시각은 ISO-8601 문자열이다.

```json
{
  "code": "200",
  "message": "OK",
  "data": {
    "items": [{
      "notificationId": 4072,
      "eventId": "7f771f4d-6a39-4e58-96ba-e87e11d4fe76",
      "type": "ALBUM_CREATED",
      "category": "ALBUM",
      "priority": "interaction",
      "title": "새 사진이 도착했어요",
      "body": "사진을 확인해보세요.",
      "imageUrl": null,
      "occurredAt": "2026-10-02T09:30:00",
      "readAt": null,
      "deepLink": "widyu://albums/91",
      "entityId": "91",
      "seniorId": null,
      "actorDisplayName": null,
      "expiresAt": "2026-12-31T09:30:00",
      "retentionClass": "ROUTINE_90D",
      "retentionPolicyVersion": "v1",
      "centerStored": true,
      "pushEligible": true,
      "foregroundPresentation": "BANNER"
    }],
    "nextCursor": "4072",
    "unreadCounts": {"UNREAD": 3, "ALBUM": 1, "GOAL": 2, "MESSAGE": 0},
    "snapshotRevision": "1790901000000",
    "serverTime": "2026-10-02T09:30:00"
  },
  "traceId": null
}
```

`unreadCounts`는 호출자에게 허용된 `UNREAD`와 카테고리 키를 모두 반환한다(보호자에게만 `LOCATION` 추가). `UNREAD`는 전체 안 읽은 수다. `ALL`은 숫자를 표시하지 않으므로 맵에서 제외한다. 0도 숫자로 반환하며 화면의 숨김·`99+` 표현은 FE가 맡는다. `snapshotRevision`은 이 응답의 기준 시각인 epoch milliseconds를 문자열로, `serverTime`은 같은 시각을 ISO-8601로 반환한다. 이 값은 다음 페이지까지 고정하는 스냅샷 토큰은 아니다.

타입 있는 행의 `category`, `priority`, `retentionClass`, `foregroundPresentation`, `centerStored`는 `NotificationType`에서 파생한다. 포인트 알림의 `centerFilter`는 null이므로 응답 `category`도 null이다. `pushEligible`은 저장된 enqueue 시점 정책값이며 개인 설정·FCM 전송 성공을 뜻하지 않는다. `title/body`는 저장된 문구를 그대로 반환한다. 미열람 `readAt`은 null이고, 기존 `isRead=true/readAt=null` 행은 읽힌 상태로 취급한다.

레거시 `type IS NULL` 행은 응답에서만 `eventId="legacy:<id>"`, `type="LEGACY"`로 채운다. `LEGACY`를 `NotificationType` enum에 추가하지 않는다. `category`는 아래 레거시 매핑으로, 매핑되지 않은 값은 null로 반환한다. `priority="interaction"`·`foregroundPresentation="BANNER"`는 DTO 팩토리의 호환 기본값으로 채운다. 센터에 저장된 행이므로 `centerStored=true`다. `expiresAt=null`은 만료 없음이다. 없는 보존 정책값은 추정하지 않고 null, `pushEligible`은 저장값이 없으면 null로 둔다. 저장된 `deepLink`가 비거나 null이면 응답은 null이다. 이 예외는 FE 모델의 nullable 수용 합의가 필요하다(§9).

단건 읽음은 본문이 없다. 현재 수신자의 행을 처음 읽으면 `isRead=true`와 `readAt`을 저장하고 200을 반환한다. 재요청도 200이며 최초 `readAt`은 유지한다. 성공 응답 `data`는 `notificationId`와 `readAt`을 담는 DTO다. 타인의 행과 없는 ID는 같은 404다.

## 4. 데이터 모델

새 테이블·컬럼·운영 마이그레이션은 없다. `widyu-domain/com.widyu.fcm.FcmNotification`의 `recipientMember`, `type`, `fcmCategory`, `expiresAt`, `isRead`, `readAt`, `createdAt` 및 W3 메타데이터를 읽는다. 새 DTO는 `widyu-api/com.widyu.notification.dto.response`에서 `from()/of()` 팩토리로 만든다. `FcmCategory`에 값을 더하지 않으며 운영 native ENUM도 바꾸지 않는다.

| 필터 | 타입 있는 행 | `type IS NULL` 레거시 행 |
| --- | --- | --- |
| `ALL` | 모든 센터 행 | 모든 행 |
| `UNREAD` | `isRead=false` | `isRead=false` |
| `ALBUM` | `centerFilter()==ALBUM` | `ALBUM` |
| `GOAL` | `centerFilter()==GOAL` | `TARGET, HEALTH_SCHEDULE, WALK, MEDICINE_SCHEDULE` |
| `MESSAGE` | `centerFilter()==MESSAGE` | `HEART_MESSAGE, ETC` |
| `LOCATION` | `centerFilter()==LOCATION` | `SAFE_ZONE, LOCATION_NOTICE` |

카테고리 필터는 `(type IN (:types) OR (type IS NULL AND fcm_category IN (:legacyCats)))`를 사용한다. 타입 있는 행을 과거 `FcmCategory`로 재분류하지 않는다. 레거시 심박 위급 행이 `HEART_MESSAGE`라 `MESSAGE`에 남는 것은 ADR-0037 결정 6의 전환기 한계다. `POINT_EARNED/POINT_USED`는 별도 필터 없이 시니어 `ALL`·`UNREAD`에 포함한다. 모든 목록·안 읽은 수의 공통 조건은 현재 `recipient_member_id`와 `(expires_at IS NULL OR expires_at > :now)`이다. `recipient_member_id IS NULL` 이력은 토큰 소유자로 추정하지 않는다.

## 5. 처리 흐름

1. `NotificationCenterController`는 `NotificationCenterService`만 호출한다. 서비스는 `MemberUtil`의 현재 회원 `MemberType`으로 필터를 검증하고 미지원값·시니어 `LOCATION`에 `FCM_4002`를 반환한다. 커서가 숫자가 아니거나 0 이하이거나 Long 범위를 넘으면 `FCM_4003`을 반환한다. 경계 ID의 소유자·존재 여부는 조회하지 않는다.
2. 목록 메서드는 `@Transactional(readOnly = true)`의 같은 읽기 트랜잭션(DB 기본 RR)에서 `now`를 한 번 잡는다. 같은 `now`를 만료 조건과 응답 시각에 사용하고 items와 unreadCounts를 그 트랜잭션에서 조회한다. H2만으로 MySQL 격리 의미를 증명하지 않는다.
3. `occurredAt`은 현재 저장 모델의 `createdAt`이다. 목록은 `id DESC`로 정렬하고 커서가 있으면 `id < :cursor`를 적용한다. 경계 행을 조회하지 않는다. 반환한 마지막 행 ID를 10진수 문자열로 만들어 `nextCursor`에 넣는다. 페이지를 잇는 동안 같은 행을 다시 반환하거나 건너뛰지 않아야 한다.
4. `UNREAD` 목록은 `isRead=false`만 조회한다. 카테고리 목록은 읽은 행도 포함한다. 안 읽은 수는 커서와 선택 필터에 영향받지 않고 모든 허용 필터에 같은 만료 조건을 적용한다.
5. 읽음 메서드는 쓰기 트랜잭션에서 `(id, recipient_member_id)` 행을 조회한다. 미열람일 때만 `markAsRead()`를 호출하고, 이미 읽은 행은 `readAt`을 바꾸지 않는다. 목록 노출로 읽음 상태를 변경하지 않는다.

새 이벤트 리스너·`@Async`·Facade는 없다. 기존 FCM 전송 경로에도 개입하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | HTTP / 코드 | 처리 |
| --- | --- | --- |
| 미지원 필터, 시니어 `LOCATION` | 400 / `FCM_4002` | `ErrorCode.INVALID_NOTIFICATION_CENTER_FILTER` 신설. `ALL` 폴백 금지. |
| 숫자가 아님·0 이하·범위 초과 커서 | 400 / `FCM_4003` | `ErrorCode.INVALID_NOTIFICATION_CURSOR` 신설. 첫 페이지 폴백 금지. |
| 없는 ID 또는 타인의 행 읽음 | 404 / `FCM_4041` | 존재 여부를 구분하지 않는다. |
| 이미 읽은 행 재요청 | 200 | 최초 `readAt` 유지. |
| `expiresAt <= now` | 목록·카운터에서 제외 | null 레거시 만료 시각은 허용. |
| 유효한 필터의 결과 없음 | 200 | 빈 items, `nextCursor=null`, 필터별 안 읽은 수 반환. |

다른 회원의 ID나 이미 삭제된 ID라도 유효한 숫자 커서라면 정상 페이지 조회로 처리한다. 조회 자체는 항상 현재 수신자 조건으로 제한하므로 타인 행은 반환하지 않는다.

목록의 실행 계획은 `(recipient_member_id, id)` 순서 조회와 만료 조건을 함께 확인한다. InnoDB 보조 인덱스는 PK를 포함하므로 기존 수신자 FK 인덱스를 우선 활용하고, 구현 단계에서 MySQL `EXPLAIN`으로 정렬·스캔 비용을 확인한다. 이 LLD에는 새 인덱스 DDL을 포함하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] N4 / DELIVERY §4·§8, UX §5·§12.3: 시니어 5개·보호자 6개 필터만 사용한다. 미지원값·시니어 `LOCATION`은 400 `FCM_4002`이며 전체로 폴백하지 않는다.
- [x] N4 / DELIVERY §8, UX §6.4: `expiresAt <= now` 행은 목록과 모든 안 읽은 수에서 제외하고 `expiresAt=null` 레거시 행은 포함한다.
- [x] N4 / ADR-0037 결정 6: 타입 있는 행은 enum 센터 필터, 타입 없는 행은 레거시 카테고리로 분류한다. 레거시 `HEART_MESSAGE`는 `MESSAGE`에 남고 포인트는 시니어 `ALL/UNREAD`에 포함한다.
- [x] N4 / DELIVERY §4·§8: `POINT_EARNED/POINT_USED`는 `GOAL` 목록과 `GOAL` unreadCounts에서 제외하고 `ALL/UNREAD`에는 포함한다. 응답 category는 enum의 null 값을 그대로 사용한다.
- [x] N4 / DELIVERY §7: 레거시 행 한 건은 `legacy:<id>`·`LEGACY`·매핑된 category·`interaction/BANNER`·null `expiresAt`·빈 링크의 null 응답을 반환한다. DB의 eventId/type은 수정하지 않는다. 미매핑 category는 null이다.
- [x] N4 / 커서 페이지: `id DESC` 정렬과 `id < :cursor`로 두 페이지를 이어 붙일 때 중복·누락이 없고 마지막 페이지 `nextCursor=null`이다.
- [x] N4 / 커서 계약: 문자·0·범위 초과 커서는 400 `FCM_4003`이다. 다른 회원 ID 또는 없는 ID를 유효한 숫자 커서로 보내면 200과 본인 행만 반환한다.
- [x] N4 / DELIVERY §4·§8: `unreadCounts.UNREAD`는 전체 유효 미열람 수, 카테고리는 각 필터의 유효 미열람 수다. 커서·선택 필터와 무관하고 items와 같은 읽기 트랜잭션·기준 시각을 사용한다. `snapshotRevision/serverTime`을 반환한다.
- [x] N4 / DELIVERY §7, UX §12.1: 새 타입 행의 envelope 필드와 enum 파생값이 일치하고 저장된 title/body를 반환한다. 푸시 설정 OFF인 센터 행도 숨기지 않는다.
- [x] N5 / DELIVERY §5·§8, UX §6.2: 최초 읽음이 `readAt`을 저장하고 재요청은 200과 같은 `readAt`을 반환한다. 타인·없는 ID는 404다. 목록 조회만으로 읽음이 변하지 않는다. 모두 읽기 endpoint는 없다.
- [x] 새 경로의 성공·400·404를 `NotificationCenterDocs`에 문서화하고 기존 `/api/v1/fcm` 동작은 유지한다.
- [x] JUnit 5·Mockito BDD, 한글 언더스코어 메서드명과 행위형 `@DisplayName`, 상태 검증을 따른다. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

새 경로·저장소 조회·DTO·오류 코드를 추가한다. W2 레지스트리의 포인트 두 타입도 정정한다(PR #699 머지 뒤 stack 정리 시 함께 반영). DDL·ERD 변경은 없고 `FcmCategory` native ENUM에도 값을 더하지 않는다. `type/event_id/expires_at/read_at`이 NULL인 레거시 행을 조회한다. 임시 `LEGACY` 직렬화 규칙은 기존 `/api/v1/fcm` 폐기 시 함께 정리한다. MySQL의 읽기 격리 동작은 구현 검수에서 확인하고, H2는 MySQL ENUM과 격리 의미를 재현하지 못하는 한계를 기록한다.

## 9. 미결정 사항 (Open Questions)

전체 계획 §5의 회신 대기 항목과 구현 가정을 아래에 그대로 옮긴다. W4 밖의 항목도 후속 작업과 같은 전제를 유지하기 위한 기록이다. 회신이 가정을 바꾸면 해당 작업의 결정 게이트에서 처리한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
|---|---|---|---|
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

W4의 레거시 행은 `expiresAt=null`이 만료 없음이다. FE가 null을 허용하도록 합의해야 한다. 포인트 및 필터 없는 타입의 응답 `category=null` 수용도 FE 합의 사항이다. `createdAt+90일`을 응답 시 계산하는 대안은 저장되지 않은 보존 등급을 추정하고 구 API의 노출 범위를 바꾸므로 기각했다.

명세의 `occurredAt DESC, notificationId DESC`와 서버 구현의 `id DESC` 단일 정렬·커서에는 편차가 있다. `created_at`과 ID 순서가 다중 인스턴스 시계 차이로 역전되는 경우 FE에 표시되는 시간 순서가 엄밀히 같지 않을 수 있다. 이 편차는 FE 합의 사항이다.

## 10. 참고

- [ADR-0037](../adr/ADR-0037-notification-center-model.md) 결정 6·7, [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md), [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0062](LLD-0062-notification-center-row-per-event.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
- 저장소 밖 승인판: `FE-DELIVERY-PACKAGE-v1.2` §4·§5·§7·§8, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §5·§6·§12.3, 우선 기준 `HEMLO-REVIEW-v0.5` §9·§10, `FE-HANDOFF-v0.4` §6·§8, 문구 정본 `NOTIFICATION-COPY-CATALOG-v0.4`.
- `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` N4·N5, `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5.
