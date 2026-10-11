# LLD-0078: 안심구역 이탈·재진입을 사건 아닌 위치 소식으로 전달

> Low-Level Design. 이 문서는 이슈 #736 구현과 PR 본문의 오라클이며, [LLD-0071](LLD-0071-safe-zone-incident.md)의 안심구역 사건 설계를 대체한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11 구현·검증 완료) |
| Issue | #736 (W19, base W17 `feature/735`) |
| 관련 ADR | ADR-0039 결정 5 (코디네이터 작성 중) |
| 작성자 | Codex / feature-736 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

안심구역은 시니어가 사는 집 둘레다. 현재 이탈은 `SAFE_ZONE_EXIT` 사건을 열어 시니어 확인과 긴급 보호자 알림으로 이어지지만, 확정 정책은 외출과 돌아옴을 보호자에게 전하는 일반 위치 소식이다. 이 설계는 사건 경로를 끊고 Z01·Z02 알림과 30분 묶기를 정의한다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-api`의 위치 전이·안심구역 이벤트/리스너·사건·후속 카드·문구·테스트, `widyu-domain`의 `NotificationType`.
- 안심구역 이탈·재진입을 일반 `PUSH_AND_CENTER` 알림으로 발송하고 Redis 30분 중복 차단을 복원한다.
- 안심구역 사건 경로·S02/S05/S07/S09·`SAFE_ZONE_V1` 카드 발급을 제거한다. `IncidentKind.SAFE_ZONE_EXIT`는 기존 행 조회 호환을 위해 남기되 새 사건을 열지 않는다.

### Out of scope

- 위치 반경·체류 정보·WebSocket/HTTP 경로와 응답 형식, 심박/낙상 사건, FCM outbox 재시도·설정 정책 변경.
- 안심구역 안에 있을 때 시니어 위치 공유를 일시중지하는 04 정책, 기존 저장 사건·카드의 삭제/백필, 자동전화·2차 알림.

## 3. 인터페이스 / API

새 HTTP API와 WebSocket 계약은 없다. `/app/location/update`의 `안→밖`·`밖→안` 위치 전이가 아래 제품 알림을 만든다. `eventId`는 전이당 새 UUID 한 개이며 같은 전이의 보호자 수신자는 이를 공유한다. `incidentRef`, `deliveryStage`, `safetyEventId`, `emergency=true`는 싣지 않는다.

| 전이 / 타입 | 제목·본문 | 전달·표시 | 설정·센터·보존·이동 |
| --- | --- | --- | --- |
| 나감 `SAFE_ZONE_EXITED` | Z01 `{시니어 이름} 님이 안심구역을 벗어났어요.` / 본문 `null` | `PUSH_AND_CENTER`, `INTERACTION`, `BANNER` | `FcmCategory.SAFE_ZONE`, `PushSettingGroup.SAFE_ZONE`, 필터 `LOCATION`, `SAFE_ZONE_90D`, `/location?seniorId={seniorId}` |
| 돌아옴 `SAFE_ZONE_ENTERED` (신설) | Z02 `{시니어 이름} 님이 안심구역으로 돌아왔어요.` / 본문 `null` | `PUSH_AND_CENTER`, `INTERACTION`, `BANNER` | 나감과 동일 |

Z02는 spec v0.5의 「들어왔어요」가 아니라 2026-10-05 추가본의 「돌아왔어요」를 쓴다. 수신자는 같은 가족의 활성 보호자 전원이다. 방장은 `SAFE_ZONE` 푸시가 강제되고, 비방장은 설정 ON일 때만 푸시를 받는다. OFF인 보호자도 센터 행은 받는다. **시니어에게는 아무것도 보내지 않는다.** 현재 `FcmOutboxService.enqueue`는 센터 행을 저장한 뒤 토큰별 outbox 행을 만들고 `FcmEligibility`가 claim/preflight에서 푸시 자격을 판정한다. 설정 OFF에도 물리 outbox 행이 잠시 생길 수 있으나 전송 가능/성공 행은 0건이다. 토큰이 없으면 센터 행만 생긴다. 이 기존 판정 경로는 변경하지 않는다.

이탈은 사건을 만들지 않는다. 시니어 S02, 60초 확인, ① 최초 사건 알림, S05, S07, S09는 이 흐름에 없다. Z01·Z02는 `CRITICAL`이 아니므로 Android `widyu_safety` 채널/high 긴급 정책을 쓰지 않는다.

## 4. 데이터 모델과 설계

1. **동기 이벤트/리스너.** `SafeZoneNotificationListener`의 `AFTER_COMMIT`·`REQUIRES_NEW` 사건 열기를 동기 `@EventListener`로 바꾼다. 위치 갱신 트랜잭션 안에서 `FcmOutboxService.enqueue`를 호출해 센터/outbox 저장 실패가 위치 트랜잭션을 롤백하도록 한다. 리스너는 현재 활성 가족 보호자를 조회하고 Z01/Z02 `NotificationCopy`로 `FcmSendDto`를 조립한다. `eventId=UUID`, `seniorId`·`relatedMemberId`는 시니어 ID, `emergency=false`다. 한 전이는 모든 수신자에게 같은 eventId를 써 수신자×eventId 센터 중복 방지를 유지한다. 직접 `FcmService` 전송은 하지 않는다.
2. **재진입 이벤트.** 현재 코드는 `SafeZoneExitEvent`만 발행하며 `SafeZoneAlertService`의 `previousLocationType == null && currentLocationType != null` 재진입 분기는 사건 종료만 한다. 이 분기에서 `SafeZoneEnterEvent`를 발행해 같은 리스너가 Z02를 enqueue한다. 기존 `RealtimeLocationService.calculateStayInfo`의 전이 판정과 커밋 후 `location:stay:{memberId}` 저장 순서는 재사용한다. 초기 위치가 이미 밖이거나 안이면 알림 전이로 간주하지 않는다.
3. **30분 묶기와 롤백.** Redis `safezone:alert:{memberId}`를 TTL 1800초로 복원한다. 첫 `안→밖`에서 `set-if-absent`가 성공할 때만 값을 `EXITED`로 예약하고 Z01 이벤트를 발행한다. 같은 창의 첫 `밖→안`은 원자적 compare-and-set으로 `EXITED→RETURNED`를 전환하고 기존 TTL을 보존한 뒤 Z02 이벤트를 발행한다. `RETURNED`는 활동 중 이탈 묶기가 끝났다는 상태이고, 키 자체는 30분 창의 재이탈 차단을 위해 남긴다. 키가 없거나 이미 `RETURNED`면 돌아옴 알림은 없다. 창 안의 추가 재이탈·재진입은 모두 발송하지 않는다. TTL 만료 뒤 다음 `안→밖`은 새 창과 Z01을 연다. Redis 비교·상태 전환은 Lua 등 단일 원자 연산으로 수행해 중복 재진입의 Z02를 한 번으로 제한한다. 이탈 예약의 `releaseAlertOnRollback`을 복원하고, 재진입 트랜잭션 롤백 시에는 `RETURNED→EXITED`를 조건부로 되돌린다. develop 원본의 `set-if-absent`·1800초·롤백 복구는 재사용하되 **재진입 즉시 키 삭제는 B6 가정에 맞춰 사용하지 않는다**.
4. **사건 경로 제거.** `IncidentService.openForAlert(Long, SAFE_ZONE_EXIT)`와 `attachOrOpen`의 위치 Redis 조회·열린 안심구역 사건 재사용, `respond`의 SAFE_ZONE_EXIT OK 안내, `selfCheckMessage`의 S02를 제거한다. `IncidentEscalation.enqueueInitialAlert`의 SAFE_ZONE_EXIT/S05와 `IncidentRepository.endOpenSafeZoneSituation` 및 `findDueIds`·`escalateTimedOutIfDue`·`claimInitialAlertIfDue`의 SAFE_ZONE_EXIT 조건을 제거한다. 심박 HR 분기와 기존 사건 조회 API는 유지한다. 배포 전인 `FollowupCardService.issueIfEnabled`의 `SAFE_ZONE_V1` 선택과 질문 응답 분기를 제거해 새 카드를 발급하지 않는다.
5. **타입·문구 레지스트리.** `NotificationType.SAFE_ZONE_EXITED`의 `CRITICAL→INTERACTION`, 문구 코드를 Z01로 바꾸고 `SAFE_ZONE_ENTERED`를 같은 메타데이터와 Z02로 추가한다. `NotificationCopy`에 본문 `null`인 Z01·Z02를 넣고 각 타입에 자기 코드만 허용한다. `SAFETY_SELF_CHECK`는 S01만 허용한다. `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE` 타입과 S02·S05·S07·S09 카탈로그 항목·허용 분기를 제거한다. 이 네 문구 및 OK 타입은 배포 전이고 운영 DB에 저장된 적이 없다는 W19 전제를 따른다. `FcmCategory` 값은 추가하지 않는다.
6. **스키마.** 새 테이블·컬럼은 없고 `incident` 컬럼도 남긴다. Redis는 기존 키 패턴을 복원하며 값에 위 상태만 담는다. `NotificationType` 저장 열은 `VARCHAR`이므로 타입 추가 DDL도 없다. 운영 DDL은 없다.

## 5. 처리 흐름

1. `RealtimeLocationService`가 기존 가족 검증·위치 저장을 수행하고 반경 판정으로 이전/현재 구역 타입을 구한다. 같은 위치에서 조기 반환하면 이벤트는 없다.
2. `SafeZoneAlertService`가 4절의 Redis 원자 게이트로 `안→밖` Z01 또는 첫 `밖→안` Z02를 결정한다. 게이트 통과 때만 해당 이벤트를 발행한다.
3. 동기 리스너가 같은 트랜잭션에서 활성 보호자마다 `FcmOutboxService.enqueue`한다. 센터 행은 설정과 토큰 수에 관계없이 수신자별로 저장되며 실제 푸시는 기존 `FcmEligibility`가 결정한다.
4. DB 커밋 뒤 기존 `RealtimeLocationService`가 새 체류 정보를 Redis에 저장하고 outbox dispatcher가 전송한다. 롤백이면 outbox/센터는 남지 않고 알림 키 보상 콜백이 실행된다.

## 6. 예외 / 에러 처리

- 새 HTTP/WebSocket 오류 코드는 없다. Redis 예약·상태 전환이나 동기 enqueue 실패는 기존 위치 갱신 예외 처리와 트랜잭션 롤백을 따른다. 가족이 없거나 활성 보호자가 없으면 수신자 0건을 기록하고 알림은 만들지 않는다.
- 롤백 뒤 Redis 조건부 보상이 실패하거나 동시 위치 갱신이 교차하면 DB 커밋과 Redis 상태가 일시적으로 어긋날 수 있다. [LLD-0058](LLD-0058-safe-zone-alert-rollback.md) §6의 경쟁 한계를 유지하며, 좌표·토큰·알림 본문은 로그에 남기지 않는다.
- FCM 실제 전송은 기존 outbox 재시도 계약을 따른다. 이 문서의 「1회」는 전이별 enqueue/센터 기준이며 provider exactly-once를 뜻하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 첫 `안→밖`에서 `SAFE_ZONE_EXIT` 신규 사건 0건, 시니어 알림 0건, 활성 보호자별 Z01 센터 행 1건이 생긴다. 방장과 `SAFE_ZONE` ON 비방장에게 전송 가능한 Z01 outbox가 생기고, OFF 비방장은 센터만 보며 실제 푸시 0건이다. 제목은 지정 문구이고 본문은 `null`이다.
- [x] AC2. Z01과 Z02는 `INTERACTION`·`BANNER`·`SAFE_ZONE` 그룹·`LOCATION` 필터·`SAFE_ZONE_90D`·`/location?seniorId={seniorId}`를 사용한다. Android `widyu_safety` 채널/high 긴급 헤더와 `emergency=true`를 쓰지 않는다.
- [x] AC3. 첫 `밖→안`에서 Z02가 수신자별 센터/푸시 자격에 따라 1회 enqueue된다. 제목은 「돌아왔어요.」이고 본문은 `null`이다. 초기 안 위치, 중복 재진입, Redis 창이 없는 재진입에서는 0건이다.
- [x] AC4. 첫 이탈 뒤 30분 안의 재이탈은 Z01 0건이고 추가 재진입도 Z02 0건이다. TTL 만료 뒤 다시 `안→밖`이면 새 Z01을 보낸다. 동시 전이와 롤백에서 Redis 상태·TTL·이벤트 개수를 확인한다.
- [x] AC5. `IncidentService`·`IncidentEscalation`·`IncidentRepository` 경유 새 안심구역 사건, 60초 시니어 확인 S02, 최초 사건 알림 S05, S07, OK 안내 S09가 모두 0건이다. `IncidentKind.SAFE_ZONE_EXIT`는 기존 행을 읽을 수 있지만 새로 저장되지 않는다.
- [x] AC6. 안심구역 이탈·재진입에서 `SAFE_ZONE_V1` 후속 카드가 생기지 않는다. 기존 안심구역 사건·S02/S05/S09·카드 테스트는 이 계약으로 개정하고 심박/낙상 회귀를 확인한다.
- [x] AC7. 타입/문구 레지스트리에 Z01·Z02만 안심구역 소식으로 남고 `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE` 및 S02·S05·S07·S09는 없다. `FcmCategory` 값과 DB 스키마는 바뀌지 않는다.
- [x] AC8. JUnit 5·BDDMockito `given/willReturn`, 한글 언더스코어 테스트명과 행위형 `@DisplayName`을 따른다. `bash scripts/harness/verify.sh`가 통과한다. 도메인 enum 변경에 따른 Domain+API 테스트를 수행한다.

## 8. 영향 범위 / 마이그레이션

| 문서 | 구현 후 개정할 내용 |
| --- | --- |
| [LLD-0071](LLD-0071-safe-zone-incident.md) | 사건 열기·재진입 종료·Redis 키 제거·S02/S05·60초·① 인수조건을 LLD-0078로 대체 표기. |
| [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md) | 안심구역 S09·OK 안내와 `SAFE_ZONE_EXIT` 사건 묶기 기대 삭제. 심박 S08은 유지. |
| [LLD-0073](LLD-0073-followup-card-and-answers.md) | `SAFE_ZONE_V1` 발급·질문 기대 삭제. 심박/낙상 카드 유지. |
| [LLD-0060](LLD-0060-notification-type-registry.md) | `SAFE_ZONE_EXITED` 우선순위/문구 수정, `SAFE_ZONE_ENTERED` Z02 추가, OK 타입·S02/S05/S07/S09 및 긴급 타입 수 설명 개정. |

[LLD-0011](LLD-0011-safe-zone-alert-deduplication.md)·[LLD-0058](LLD-0058-safe-zone-alert-rollback.md)의 30분 원자 예약·롤백 보상은 복원하되, B6 상태 전환은 본 문서가 우선한다. 운영 DDL과 기존 사건 행 마이그레이션은 없다. `SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE`는 배포 전 타입이어서 운영 저장 이력이 없다는 전제이며, 실제 배포 이력이 발견되면 삭제 전 데이터 확인이 필요하다.

## 9. 미결정 사항 (Open Questions)

| 항목 | W19 설계 가정 / 확인할 내용 |
| --- | --- |
| B6: 돌아옴과 30분 묶기 횟수 | 코디네이터 결정으로 **한 30분 창에 Z01 최대 1회, 첫 재진입 Z02 최대 1회**를 가정한다. 재진입은 키를 지우지 않고 `EXITED→RETURNED`로 바꾼다. 이후 왕복은 모두 알리지 않고 TTL 만료 뒤 새 이탈부터 다시 시작한다. 제품·FE 회신이 다르면 게이트에서 재결정한다. develop처럼 재진입 때 키를 지워 즉시 재이탈을 보내는 안은 대안으로만 남긴다. |
| `IncidentKind.SAFE_ZONE_EXIT`·`SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE` 삭제 | 사건 kind 값은 기존 행 호환 때문에 유지한다. 신규 OK 타입은 배포 전·저장 이력 없음이라는 전제로 레지스트리에서 제거한다. 과거 행 확인 뒤 enum 값 자체의 최종 삭제 여부를 정한다. |
| 돌아옴 판정 이벤트 출처 | 현재 별도 재진입 이벤트는 없다. `SafeZoneAlertService`의 기존 `밖→안` 분기에서 `SafeZoneEnterEvent`를 발행하는 최소 변경을 제안한다. `RealtimeLocationService`의 전이 판정 외 별도 발행 지점이 필요할지 구현 게이트에서 확인한다. |
| 안심구역 내 시니어 위치 공유 일시중지 | 추가본 04 정책은 이번 범위 밖이다. 위치 전이 입력이 중단되면 돌아옴 감지가 어떻게 재개되는지 별도 검토한다. |

## 10. 참고

- 정본(저장소 밖): `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A10 확정, §4 「6. 안심구역」 B6 미정. 분석: `apiDocs/notification/BE-GAP-2026-10-05.md` §2.2·§3 #4·#5. 계획: `apiDocs/notification/BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11 W19.
- ADR-0039 결정 5(코디네이터 작성 중), [LLD-0077](LLD-0077-initial-alert-leader-only.md) W17 공통 `enqueueInitialAlert` HR 분기, [ERD-0001](../erd/ERD-0001-initial-domain.md).
