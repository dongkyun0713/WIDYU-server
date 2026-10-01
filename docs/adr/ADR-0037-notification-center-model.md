# ADR-0037: 알림센터 모델 — 이벤트 종류가 전달을 결정하고, 센터 행은 수신자×이벤트 하나다

> Architecture Decision Record. 하나의 중요한 의사결정과 그 이유를 기록한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted (2026-10-01 사용자 승인) |
| 날짜 | 2026-10-01 |
| 관련 | #690(로드맵), #691, ADR-0028·LLD-0036(저장 단위·설정 판정 개정), LLD-0002, 알림센터 55차 승인판(`FE-HANDOFF-v0.4` §2·§3·§4·§6, `FE-DELIVERY-PACKAGE-v1.2` §7·§8·§12, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §11·§12, `HEMLO-REVIEW-v0.5` §8~§10), 코드 변경점 표 N1~N14 |

## 맥락 (Context)

지금 알림은 기기 단위로 산다. `FcmOutboxService.enqueue`가 수신자의 활성 토큰마다 outbox 행을 만들고, `FcmOutboxTransactions.finish`가 FCM 전송에 성공한 토큰마다 센터 행(`fcm_notification`)을 하나씩 만든다. 그래서 기기가 둘이면 알림센터에 같은 알림이 두 번 보이고, 토큰이 없거나 푸시 설정을 끈 수신자는 알림센터에도 아무것도 남지 않는다. 전송 재시도 식별자(`data.notificationId`=outbox id)가 제품 이벤트 식별자 노릇을 하고, 좋아요처럼 「센터에만」 남겨야 할 알림과 업로드 완료처럼 「푸시만」 보내야 할 알림을 구분할 자리가 없다. 푸시 설정은 `FcmCategory` 단위 4묶음(GOAL/ALBUM/HOME/ETC)이라 심박 위급은 끌 수조차 없고(누락), 명세의 4분류(안전 알림/안심구역/복약 확인/일반 알림)와 맞지 않는다. 목록 API는 잘못된 카테고리를 전체 조회로 조용히 바꾼다.

2026-09-29 승인된 알림센터 새 판은 다음을 요구한다. 수신자×제품 이벤트마다 센터 항목 하나(N1), 재시도·다중 기기에서도 같은 `eventId`(N2), type별 딥링크·우선순위·보존 등급(N3·N6), 전달 방식 4종(N7), FCM data 최소 키(N8), 안전 알림의 긴급 표현(N9), 푸시 설정 4분류와 방장 안전 알림 필수(N10·N11), 푸시를 꺼도 센터 저장(N14), 필터·커서·안읽음 수·서버 시각을 한 응답에 주는 목록 API(N4).

## 결정 (Decision)

1. **이벤트 종류가 전달을 결정한다.** `widyu-domain`에 `NotificationType` enum 하나를 두고, 상수마다 저장 카테고리(`FcmCategory`), 센터 필터(`ALBUM/GOAL/MESSAGE/LOCATION`), 전달 방식(`PUSH_AND_CENTER/CENTER_ONLY/PUSH_ONLY/DATA_ONLY`), 우선순위(`critical/timeSensitive/interaction/passive`), 보존 등급(`ROUTINE_90D/HEART_EMERGENCY_180D/SAFE_ZONE_90D`), 설정 그룹, 앱 사용 중 표시(`NONE/BANNER`), 딥링크 경로 템플릿을 적는다. type에서 파생되는 값은 **컬럼으로 저장하지 않고** 응답을 만들 때 enum에서 읽는다. 문구는 문구표 v0.4의 코드(A01…S10)를 type+변형(-S/-C, -OS/-INAPP)으로 만드는 `NotificationCopy`가 맡는다.
2. **센터 행은 enqueue 시점에 수신자당 하나 만든다.** 토큰 루프 앞에서 만들므로 활성 토큰이 없어도 남는다. `fcm_notification`에 `event_id`·`type`·`deep_link`·`entity_id`·`senior_id`·`actor_display_name`·`expires_at`·`retention_policy_version`·`push_eligible`·`read_at`을 더하고 `UK(recipient_member_id, event_id)`로 멱등을 보장한다. outbox 행은 `notification_id`로 센터 행을 가리키고, FCM data의 `notificationId`는 센터 행 id다. `finish()`는 더 이상 센터 행을 만들지 않는다. `CENTER_ONLY`는 outbox 없이 센터 행만, `PUSH_ONLY`는 센터 행 없이 outbox만(data에 `eventId`), `DATA_ONLY`는 알림 없는 data 메시지다.
3. **푸시 설정은 push 자격만 결정한다.** 센터 행은 설정과 무관하게 생긴다. `FcmEligibility`는 지금처럼 claim/preflight에서만 돌되 판정 기준을 `type.settingGroup()`로 바꾼다. `push_eligible`은 enqueue 시점의 정책값 스냅샷이다.
4. **`eventId`는 수신자·이벤트 한 쌍에 UUID 하나다.** 안전 이벤트는 `incident_ref`를 쓰고, 최초 알림(`INITIAL_ALERT`)과 최종 알림(`FINAL_ESCALATION`)은 같은 행을 갱신하며, 괜찮다는 안내(`OK_NOTICE`)는 `incident_ref:OK`로 새 행이다.
5. **설정은 4그룹으로 재편한다.** `NotificationSettingGroup` = `SAFETY`(심박 위급) / `SAFE_ZONE` / `MEDICATION_CHECK`(M04) / `GENERAL`(그 밖의 모든 푸시, S08/S09·보호자 G01 포함). `member_notification_setting.category` 컬럼을 그룹 키로 **재사용**한다(`VARCHAR(32)`, `@JdbcTypeCode(VARCHAR)`, UK 유지). 기존 행은 `ALBUM·TARGET·HEALTH_SCHEDULE·WALK·ETC → GENERAL(enabled = MAX)`, `MEDICINE_SCHEDULE → MEDICATION_CHECK`, `SAFE_ZONE 행 삭제`로 접는다. 필수 그룹(`SAFETY`·`SAFE_ZONE`)은 행을 두지 않고 enum 속성으로 항상 켜져 있으며, 방장의 끄기 요청은 409로 거부한다. type이 없는 레거시 호출자를 위해 `NotificationSettingGroup.of(FcmCategory)` 폴백을 전환기 동안 유지한다.
6. **`FcmCategory`에 값을 더하지 않는다.** 운영 `fcm_notification.fcm_category`·`member_notification_setting.category`는 `init.sql`에 정의가 없어 Hibernate가 만든 native ENUM일 가능성이 높다. 새 분류는 `type` 컬럼이 담고, 센터 필터는 Java에서 `NotificationType`→IN 목록으로 풀며 레거시 행은 `type IS NULL AND fcm_category IN (…)`으로 함께 보여 준다.
7. **목록 API는 새 경로로 만든다.** `GET /api/v1/notifications?filter=&cursor=`와 `PATCH /api/v1/notifications/{id}/read`. 필터 허용값은 호출자 `MemberType`로 정하고 허용 밖은 400(`FCM_4002`)이다. 만료 행(`expires_at <= now`)은 내지 않고, `items`·`unreadCounts`는 같은 읽기 트랜잭션에서 뽑아 `snapshotRevision`(epoch ms)·`serverTime`과 함께 준다. 기존 `/api/v1/fcm`은 앱이 옮길 때까지 유지한다.
8. **안전 알림의 긴급 표현**은 `android.priority=high` + `android.notification.channel_id`, `apns-priority=10` + `aps.interruption-level`로 보낸다. 값(`widyu_safety`/`time-sensitive`)은 제안이며 FE와 고정한다. `critical`은 Apple 권한이 필요해 쓰지 않는다.

## 고려한 대안 (Considered Options)

1. **정책을 테이블로 관리** — 운영 중 바꿀 수 있지만 바꿀 사람도 수요도 없다. enum은 코드 리뷰로 변경이 드러난다. 기각.
2. **센터 행을 새 테이블(`notification_center_item`)에** — 레거시 `fcm_notification`을 건드리지 않지만 목록 API가 두 테이블을 합쳐야 하고 읽음·안읽음 수가 두 벌이 된다. 기각.
3. **센터 행 생성과 「푸시 OFF여도 저장」을 두 PR로** — 같은 코드 경로라 나누면 중간 상태(센터 행은 enqueue에서 만들지만 설정 OFF면 또 안 만드는)가 생긴다. 기각.
4. **`FcmCategory`에 `HEART_RATE_EMERGENCY` 추가** — 레거시 위급 행이 「메시지」 필터에 남는 것을 막지만 운영 ENUM ALTER가 두 테이블에 필요하다. 레거시 행의 필터 오분류를 감수하고 기각.
5. **type별 설정** — 명세가 4분류로 확정했고 화면도 4개다. 기각.
6. **기존 `/api/v1/fcm` 확장** — 잘못된 카테고리를 전체로 돌리는 폴백과 응답 형태가 달라 두 계약이 섞인다. 기각.

## 결과 (Consequences)

### 긍정
- 다중 기기여도 센터 1건, 토큰 없는 수신자도 센터에 남는다. 푸시 OFF와 센터 저장이 분리된다.
- 새 이벤트를 추가할 때 enum 상수 하나로 전달 방식·보존·설정·딥링크가 정해지고, 호출자는 type만 고른다.
- 레거시 행과 새 행이 한 테이블·한 목록에 공존한다. 앱 전환 중에도 두 API가 같은 데이터를 본다.

### 부정 / 트레이드오프
- `fcm_notification`의 토큰 FK를 NULL 허용으로 바꾸고 컬럼을 더하는 운영 DDL이 필요하며 되돌리기 어렵다. 운영 컬럼이 native ENUM이면 `MODIFY COLUMN`을 포함해야 하고 `SHOW COLUMNS`로 먼저 확인해야 한다.
- 설정 행을 접으면 「목표만 껐던」 사용자가 앨범 푸시를 계속 받는다(현재 읽기 의미와 같아 체감 변화는 없다).
- 레거시 심박 위급 행은 `HEART_MESSAGE`라 「메시지」 필터에 남는다.
- `push_eligible`은 enqueue 시점 값이라 그 뒤 설정을 바꿔도 행은 그대로다. 실제 발송 여부는 outbox 상태가 답한다.

## 후속 / 미결정
- 이벤트별 `priority`, Android 채널 id, iOS 표시 수준, 딥링크 문자열은 명세에 없다. LLD(W2)에 제안표로 싣고 착수 회의에서 FE와 고정한다.
- 시니어(위듀)의 푸시 설정 항목은 회신 대기다. 가정: `GENERAL` 하나.
- 복약 동기화 신호 형식은 회신 대기다. 가정: M08/M05/M09마다 `DATA_ONLY` `MEDICATION_SCHEDULE_SYNC`(revision)를 항상 함께 보내고, 구 앱 호환으로 data `type=MEDICATION_SCHEDULE_CHANGED`를 유지한다.
- `group_key`·`target_unavailable_at`은 쓰는 쪽(동시 목표 달성 묶음, 대상 삭제 훅)이 생길 때 더한다.
- `FcmCategory`의 쓰이지 않게 되는 값 정리는 앱 전환 뒤 별도 작업이다.
