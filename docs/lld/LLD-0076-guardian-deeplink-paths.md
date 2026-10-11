# LLD-0076: 위듀케어 알림 딥링크 앱 내부 경로 적용

> Low-Level Design. 이 문서는 이슈 #730 구현과 PR 검수의 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-04 구현·검증 완료, 코디네이터 검수 대기) |
| Issue | #730, #746(§11 위듀 앱 경로 개정) |
| 관련 ADR | [ADR-0037](../adr/ADR-0037-notification-center-model.md) |
| 선행 | LLD-0060·0064·0066·0068·0069·0071·0072 |
| 작성자 | Codex / feature-730 |
| 작성일 | 2026-10-04 |

## 1. 목적 / 배경

현재 보호자 알림의 딥링크는 서버가 정한 임시 경로라 위듀케어 FE의 2026-10-03 앱 내부 경로 계약과 맞지 않는다. 보호자 수신 알림의 목적지만 FE 경로로 맞추고, 같은 `deepLink`를 FCM data와 알림센터 목록 응답에 싣는다. `type`은 기존 값과 변경 수신 트리거 역할을 유지하며 화면 이동은 `deepLink`가 결정한다.

## 2. 범위

### In scope

- 변경 모듈은 `widyu-domain`의 `NotificationType` 템플릿과 `widyu-api`의 알림 리스너·서비스·DTO·Swagger 예시, 관련 테스트다.
- 보호자 전용 타입의 템플릿, 역할에 따라 갈리는 앨범·목표 알림의 보호자 링크, 보호자 수신 비타입 하트·응원 메시지의 data를 §3 계약으로 맞춘다.
- 제품에 없는 보호자 본인 건강일정 H01-C-SELF 발행을 중단한다. 문구표 정본인 `NotificationCopy`의 문구는 보존하고 **서버 미사용**으로 표기한다.

### Out of scope

- 위듀(시니어) 수신 알림의 경로 교체, 낙상 본인확인 비타입 경로, 댓글 ID의 별도 센터 컬럼 저장, 전원 소진 `FINAL_ESCALATION` 구현.
- H01-C-SELF 외 알림 생성 대상·전달 방식·문구·푸시 설정·안전 사건 상태기계·센터 저장 모델·HTTP API 변경.

## 3. 인터페이스 / API

새 HTTP API나 응답 필드는 없다. `deepLink`는 앱 내부 경로 문자열이다. 서버가 `{seniorId}`·`{albumId}`·`{commentId}`를 실제 ID로 채워 FCM data와 알림센터 응답의 `deepLink`에 같은 값을 전달한다. `type`은 기존 알림 트리거 값이며 화면 이동 경로로 사용하지 않는다. A01처럼 센터에 저장하지 않는 타입은 FCM data에만 경로가 있다.

| 알림 | 타입 | 생성 위치 | 보호자 deepLink |
|---|---|---|---|
| 심박 위급 최초 S04 | `HEART_RATE_EMERGENCY` | `IncidentEscalation`(enum 템플릿) | `/location?seniorId={seniorId}` (`type=HEART_RATE_EMERGENCY` 유지, FE 확인 요청) |
| 안심구역 이탈 최초 S05 | `SAFE_ZONE_EXITED` | 〃 | `/location?seniorId={seniorId}` |
| 괜찮다 안내 S08/S09 | `SAFETY_SENIOR_OK_NOTICE_*` | `IncidentService.enqueueOkNotice` | `/location?seniorId={seniorId}` |
| 업로드 완료 A01(보호자 업로더) | `ALBUM_UPLOAD_COMPLETE` | `AlbumNotificationListener` | `/post?postId={albumId}` |
| 새 게시물 A02-C | `ALBUM_CREATED` | 〃 | `/post?postId={albumId}` |
| 댓글·답글 A03/A04(보호자 작성자) | `ALBUM_COMMENTED/REPLIED` | 〃 | `/post?postId={albumId}&commentId={commentId}` |
| 좋아요 A05(보호자 작성자) | `ALBUM_LIKED` | 〃 | `/post?postId={albumId}` |
| 잠금 해제 A06 | `ALBUM_UNLOCKED` | 〃 | `/post?postId={albumId}` |
| 모두 읽음 A07 | `ALBUM_ALL_VIEWED` | 〃 | `/album` |
| 복약 +30분 미확인 M04 | `MEDICATION_PROOF_MISSING` | `MedicineScheduleNotificationListener`(enum 템플릿) | `/goal/medicine?seniorId={seniorId}` |
| 시니어 목표 달성 G01-C | `GOAL_ACHIEVED` | `GoalPointNotificationListener` | `/goal/medicine?seniorId={seniorId}` (사용자 지정; 걷기·건강일정 달성도 같은 화면 — FE 확인 요청) |
| 하트·응원 메시지(보호자 수신) | 비타입(기존 `HeartMessageService`, `/api/v1/fcm/send`) | data에 직접 | `/notification` |
| 방장 변경 R01 | `FAMILY_LEADER_CHANGED` | `FamilyLeaderChangedNotificationListener`(enum 템플릿) | `/family-manage` |
| 보호자 본인 건강일정 임박 H01-C-SELF | — | `HealthScheduleNotificationListener` | **분기 제거**(보호자 소유 일정은 알림 없음) |
| 전원 소진 최종 알림 | — | W12 미구현 | 문서에만 기록 |

위듀(시니어) 수신 알림의 `deepLink`(A01/A02-S/A03~A05 시니어 작성자, M02/M03/M05/M08/M09, H01-S/H02~H04, W01/W02, G01-S, P01/P02, S01/S02 본인확인)는 현재 `widyu://…` 템플릿 그대로 둔다.

## 4. 데이터 모델 / 변경 설계

- 보호자 전용 8타입인 `MEDICATION_PROOF_MISSING`, `ALBUM_UNLOCKED`, `ALBUM_ALL_VIEWED`, `HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXITED`, `SAFETY_SENIOR_OK_NOTICE_HEART`, `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE`, `FAMILY_LEADER_CHANGED`는 `NotificationType`의 딥링크 템플릿 문자열만 §3 경로로 교체한다. A06 템플릿에는 `/post?postId={entityId}`, M04·안전 타입에는 `?seniorId={seniorId}`를 사용한다. **템플릿의 placeholder 이름을 바꾸지 않는다.** `FcmSendDto.resolvedDeepLink()`가 기존 `{entityId}`·`{seniorId}`·`{commentId}`를 치환한다.
- 역할에 따라 갈리는 A01·A02·A03/A04·A05·G01의 보호자 경로는 `widyu-api`의 `fcm/dto/GuardianDeepLinks` final 클래스로 모은다. private 생성자와 `post(albumId)`, `postComment(albumId, commentId)`, `album()`, `medicineGoal(seniorId)`, `notification()` 정적 메서드를 둔다. 조건 분기는 삼항 연산자 없이 작성한다. `AlbumNotificationListener.GUARDIAN_ALBUM_DETAIL/LIST`, `HealthScheduleNotificationListener.GUARDIAN_HEALTH_SCHEDULE_DETAIL` 상수와 `GoalPointNotificationListener`의 G01-C 인라인 경로를 삭제한다.
- A06·A07은 현재 리스너가 명시적 `deepLink`를 전달해 enum 템플릿보다 우선한다. 그 명시값을 제거하고 `FcmSendDto`가 각각의 새 템플릿을 해석하게 한다. A01·A02·A03/A04·A05의 보호자 분기는 `GuardianDeepLinks`를 명시값으로 전달한다. 시니어 분기는 기존처럼 `NotificationType`의 템플릿을 쓴다.
- `HealthScheduleNotificationListener`는 수신자 유형이 `GUARDIAN`이면 H01 알림을 만들지 않고 조기 반환한다. `NotificationCopy`의 H01-C-SELF-OS/INAPP 문구는 정본표와의 대응을 위해 남기며 **서버 미사용**이다.
- `HeartMessageService`와 `FcmService.sendNotificationToMember`는 수신자가 `GUARDIAN`일 때만 data에 `deepLink=GuardianDeepLinks.notification()`을 넣는다. 두 발송은 비타입이므로 `FcmSendDto.dataForEnqueue()`가 data를 그대로 사용하고, 센터 행은 `data.get("deepLink")`를 저장한다. 시니어 수신 data와 링크는 변경하지 않는다.
- 스키마·DDL·ERD·환경변수와 `FcmSendDto.resolvedDeepLink()`·센터 저장·응답 경로는 변경하지 않는다. Android 채널, APNs 표시 수준, `NotificationType` 상수·FCM `type`, 센터 필터, 설정 그룹도 그대로 둔다.

## 5. 처리 흐름

1. 기존 리스너·서비스가 수신자 역할을 판단하고, 보호자 전용 템플릿 또는 `GuardianDeepLinks`가 만든 경로를 `FcmSendDto`에 전달한다. A03/A04는 저장된 댓글·답글 ID를 `commentId`에 사용한다.
2. typed 알림은 기존 `dataForEnqueue()`와 `resolvedDeepLink()`가 명시값 우선, 다음으로 템플릿 치환을 수행한다. 비타입 하트·응원 메시지는 수신자에 따른 data를 그대로 전달한다.
3. 기존 `FcmOutboxService.enqueue()`가 `data.get("deepLink")`를 센터 행에 저장하고 같은 data를 outbox에 보존한다. 센터 응답은 저장값을 노출한다. 현재 트랜잭션·이벤트·재시도 경계는 유지한다.

## 6. 예외 / 에러 처리

새 HTTP 오류 코드나 폴백 정책은 없다. 필수 ID가 없어 템플릿 치환이 끝나지 않으면 기존 `resolvedDeepLink()`가 빈 문자열을 반환하는 동작을 유지한다. 삭제·접근 불가 게시물 등 대상 화면의 후속 라우팅은 FE의 기존 처리에 따른다. H01 보호자 소유 일정은 오류가 아니라 발행 대상에서 제외한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 보호자 전용 8타입의 `NotificationType` 템플릿이 §3의 앱 내부 경로와 일치한다. 기존 placeholder가 실제 ID로 치환된다.
- [x] AC2. 앨범 보호자 작성자·업로더 대상 A01/A02/A03/A04/A05 링크는 `GuardianDeepLinks` 값과 일치하고, A06/A07도 §3 경로를 사용한다.
- [x] AC3. A03/A04 보호자 작성자의 H2 알림센터 행 `deep_link`는 `/post?postId=<id>&commentId=<cid>`다.
- [x] AC4. M04의 H2 알림센터 행 `deep_link`는 `/goal/medicine?seniorId=<id>`다.
- [x] AC5. G01-C FCM `data.deepLink`는 `/goal/medicine?seniorId=<id>`다.
- [x] AC6. 보호자 소유 건강일정은 H01 센터 행·outbox를 0건 만든다. H01-C-SELF 문구는 `NotificationCopy`에 남는다.
- [x] AC7. 보호자 수신 하트·응원 메시지는 센터 행 `deep_link`와 FCM `data.deepLink`가 모두 `/notification`이다. 시니어 수신 값은 종전과 같다. (#746에서 시니어도 `/notification`으로 개정, §11)
- [x] AC8. 변경된 main·test와 이 LLD에 보호자 앱 커스텀 스킴 문자열이 0건이다.
- [x] AC9. 위듀(시니어) 수신 타입의 `deepLink` 값은 변경 전과 동일하다. (#746에서 §11로 대체)
- [x] AC10. `bash scripts/harness/verify.sh`가 통과한다. H2 통합 검증은 AC3·AC4·AC7의 센터 저장값을 확인한다.

## 8. 영향 범위 / 마이그레이션

구현 대상 main 파일은 `NotificationType.java`, 신규 `fcm/dto/GuardianDeepLinks.java`, `AlbumNotificationListener`, `HealthScheduleNotificationListener`, `GoalPointNotificationListener`, `HeartMessageService`, `FcmService`, `NotificationCenterDocs`, `FcmDocs`다. Swagger 예시의 보호자 앨범 링크는 `/post?postId=91`로 고치고 `FcmDocs`의 오래된 예시 스킴은 빈 문자열로 고친다.

테스트 영향은 `AlbumNotificationListenerTest`, `HealthScheduleNotificationListenerTest`, `GoalPointNotificationListenerTest`, `MedicineScheduleNotificationListenerTest`, `FamilyLeaderChangedNotificationListenerTest`, `IncidentEscalationTest`, `FcmHttpTransportTest`, `FcmOutboxIntegrationTest`, `NotificationCenterServiceIntegrationTest`, `HeartMessageServiceTest`, `FcmServiceTest`다. H2 센터 행과 FCM data의 링크 일치를 확인한다. 기존 LLD-0060·0064·0066·0068·0069·0071·0072의 링크 설명과 ADR-0037의 FE 확정 후속 기록을 갱신했다. Git 제외 FE 전달 문서는 코디네이터가 처리한다. 운영 마이그레이션은 없다.

## 9. 미결정 사항 (Open Questions)

| 항목 | 현재 구현 계약 | 후속 확인 |
| --- | --- | --- |
| 위듀 앱 경로 | ~~시니어 수신 링크는 현재 값 유지~~ → §11에서 교체(#746) | 해소 |
| G01-C 목적 화면 | 걷기·건강일정 달성도 `/goal/medicine?seniorId=<id>` 사용 | FE가 목표 종류별 목적 화면을 확인 |
| 심박 위급 최초 알림 `type` | 서버는 `HEART_RATE_EMERGENCY` 유지 | FE가 경고 다이얼로그 트리거 문자열 확인 |
| 전원 소진 최종 알림 | W12 미구현 | W12에서 `FINAL_ESCALATION` 경로와 발송 계약 처리 |
| 낙상 본인확인·댓글 ID | 낙상 본인확인은 비타입이고 댓글 ID는 센터 별도 컬럼에 저장하지 않음 | 위듀 앱 경로 작업에서 함께 처리. 현재 댓글 이동은 `deepLink`의 `commentId`로 충족 |

## 10. 참고

- Git 제외 FE 정본 `apiDocs/notification/FE-DEEPLINKS-SHARE-2026-10-03.md` §1·§2, 보조 `FE-HANDOFF-v0.4.md` §4·§6과 `NOTIFICATION-COPY-CATALOG-v0.4.md`, 전체 계획 `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §10.2~§10.6.
- [ADR-0037](../adr/ADR-0037-notification-center-model.md), [LLD-0060](LLD-0060-notification-type-registry.md) §4, [LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md), [LLD-0068](LLD-0068-medication-notification-alignment.md), [LLD-0069](LLD-0069-goal-achieved-and-point-center.md), [LLD-0071](LLD-0071-safe-zone-incident.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0064](LLD-0064-leader-change-and-guardian-order.md).

## 11. 개정 — 위듀(시니어) 앱 경로 (#746, 2026-10-11)

위듀 FE가 시니어 앱의 알림별 이동 경로를 앱 내부 경로로 확정했다. 시니어가 받는 알림의 `deepLink`를 아래 값으로 바꾸고, 본인확인 요청의 `data.type`을 FE 화면 트리거 값으로 바꾼다. 보호자 경로(§3)는 그대로다.

| 알림 | 타입 | 시니어 deepLink | `data.type` |
|---|---|---|---|
| 본인확인 요청 S01/S02 | `SAFETY_SELF_CHECK` | 없음(빈 문자열, 화면 트리거 전용) | `EMERGENCY_CONFIRM_REQUEST` |
| 낙상 본인확인(비타입) | — | 기존 `scheme` 유지 | `EMERGENCY_CONFIRM_REQUEST`(data에 직접) |
| 업로드 완료 A01·새 게시물 A02-S·좋아요 A05 | `ALBUM_UPLOAD_COMPLETE`·`ALBUM_CREATED`·`ALBUM_LIKED` | `/album/post?postId={albumId}` | 타입 이름 |
| 댓글·답글 A03/A04 | `ALBUM_COMMENTED`·`ALBUM_REPLIED` | `/album/post?postId={albumId}&commentId={commentId}` | 타입 이름 |
| 복약 +10·+20분 M02/M03 | `MEDICATION_REMINDER_10/20` | `/goal` | 타입 이름 |
| 복약 일정 등록·변경·삭제 M08/M05/M09 | `MEDICATION_SCHEDULE_CREATED/CHANGED/DELETED` | `/goal` | `MEDICATION_SCHEDULE_CHANGED`(구 앱 호환 유지) |
| 건강일정 임박·등록·변경·삭제 H01-S/H02~H04 | `HEALTH_SCHEDULE_*` | `/goal` | 타입 이름 |
| 걷기 목표 미달·변경 W01/W02 | `WALK_GOAL_UNMET`·`WALK_GOAL_CHANGED` | `/goal` | 타입 이름 |
| 목표 달성 G01-S(G02 미발행) | `GOAL_ACHIEVED`·`GOALS_ACHIEVED_GROUPED` | `/goal` | 타입 이름 |
| 포인트 획득·사용 P01/P02 | `POINT_EARNED`·`POINT_USED` | `/my/point` | 타입 이름 |
| 하트·응원 메시지(비타입, 두 역할) | — | `/notification` | `HEART_MESSAGE_RECEIVED`·`CHEER_MESSAGE_RECEIVED`(data에 직접) |

- 경로는 모두 `NotificationType` 템플릿 한 곳에 둔다. 포인트 리스너가 넘기던 명시 링크 `widyu://points`를 지워 템플릿이 적용되게 한다. 심박·안심구역 본인확인은 `scheme`을 넘기지 않아 `deepLink`가 빈 문자열이다. 사건은 `data.entityId=incident_ref`로 찾는다.
- 비타입 하트·응원 메시지는 수신자 역할과 관계없이 같은 data(`deepLink`·`type`)를 싣는다. 값은 `HEART_MESSAGE_RECEIVED`·`CHEER_MESSAGE_RECEIVED` 상수의 템플릿과 이름이다. 두 메시지는 여전히 비타입 발송이라 전달 방식·센터 저장은 바뀌지 않는다.
- 유지: 복약 동기화 신호 `MEDICATION_SCHEDULE_SYNC`는 계속 보낸다. FE는 M08/M05/M09와 병합해 쓰지만, 일반 푸시를 끈 시니어 기기에도 알람 변경을 알리려면 data 전용 신호가 필요하다(LLD-0068 §5, 사용자 결정 2026-10-11). 정각 복약 푸시는 #743의 설정 스위치를 따른다.
- 이미 저장된 알림센터 행의 `deep_link`는 백필하지 않는다. 체인 미배포 상태라 운영 행이 없다.

### 인수조건

- [x] AC11. 시니어가 받는 위 타입의 FCM `data.deepLink`와 센터 행 `deep_link`가 표의 경로이고, `NotificationType`과 main 코드에 `widyu://`는 낙상 비타입 `scheme` 상수 하나만 남는다.
- [x] AC12. 심박·안심구역 본인확인 FCM의 `data.type`은 `EMERGENCY_CONFIRM_REQUEST`, `data.notificationType`은 `SAFETY_SELF_CHECK`, `data.deepLink`는 빈 문자열이다. 낙상 본인확인 data의 `type`도 `EMERGENCY_CONFIRM_REQUEST`다.
- [x] AC13. 하트·응원 메시지는 수신자 역할과 관계없이 `data.deepLink=/notification`이고 `data.type`이 각 타입 이름이다.
- [x] AC14. 보호자 수신 경로(§3)와 M08/M05/M09의 `data.type=MEDICATION_SCHEDULE_CHANGED`는 바뀌지 않는다.
- [x] AC15. `bash scripts/harness/verify.sh`가 통과한다.
