# LLD-0065: 푸시 설정 4분류와 방장 안전 알림 필수

> Low-Level Design. 이 문서는 이슈 #700 구현과 PR 본문의 기준이다. W2의 `NotificationType.settingGroup()`과 W3의 수신자×이벤트 센터 행을 전제로 한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #700 |
| 관련 ADR | ADR-0037 결정 3·5, ADR-0038 |
| 작성자 | Codex / feature/700 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 설정 화면은 `GOAL/ALBUM/HOME/ETC`를 보여 주고 저장은 `FcmCategory`별 여러 행을 쓴다. 승인된 제품 계약은 `SAFETY/SAFE_ZONE/MEDICATION_CHECK/GENERAL` 네 종류이며, 현재 가족 방장은 두 안전 푸시를 끌 수 없다. 설정은 푸시 발송 자격만 바꾸고 W3가 만든 센터 행은 보존한다.

## 2. 범위

### In scope

- 변경점 N10·N11·N12. `widyu-domain`: W2의 `PushSettingGroup`을 설정 그룹 정본으로 확장(표시 이름·필수 속성·`fromLegacy(FcmCategory)` 폴백), `MemberNotificationSetting.category` Java 타입 교체, 회원별 푸시 정책 revision 저장. enum 컬럼은 `@Enumerated(STRING)`과 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 쓴다.
- `widyu-api`: 본인 설정 GET/PATCH, 방장 안전 그룹 끄기 409, revision 충돌 거부, `FcmEligibility`의 `type.settingGroup()` 우선 판정. claim과 preflight 양쪽에서 같은 판정을 사용한다.
- `scripts/mysql/migrate_notification_setting_group.sql`: 운영 `category`를 `VARCHAR(32)`로 전환하고 기존 행을 접는다. 구현 시 ERD, Swagger, 테스트도 갱신한다.

### Out of scope

- 이벤트 수신 대상 편집, 다른 보호자의 설정 조회·수정, OS 권한 변경, 자동전화 대상 정책, W3의 센터 행 생성 위치 변경, W11의 사건 상태 변경. `FcmCategory` 값은 추가하지 않는다.
- 시니어의 보호자 전용 설정. 시니어 항목은 회신 전 §9에 따라 `GENERAL` 하나다.

## 3. 인터페이스 / API

기존 `GET /api/v1/fcm/settings`와 `PATCH /api/v1/fcm/settings`를 유지한다. GET은 인증된 본인의 보호자 설정 4개 또는 시니어 설정 `GENERAL` 1개를 반환한다. 기존 PATCH는 로그인 본인만 변경하고 요청에 `policyRevision`을 필수로 받는다. UX §12.3의 `PATCH /api/v1/notification-channel-preferences/{category}`는 **제안, 미채택**이며 구현하지 않는다. 요청 필드는 기존 `group`·`enabled`와 신규 `policyRevision`을 사용한다.

```http
GET /api/v1/fcm/settings
PATCH /api/v1/fcm/settings
Content-Type: application/json

{"group":"GENERAL","enabled":false,"policyRevision":4}
```

```json
{
  "code": "FCM_2010",
  "message": "알림 설정 조회 성공",
  "data": [{
    "group": "SAFETY",
    "groupName": "안전 알림",
    "enabled": true,
    "recipientEnabled": true,
    "productPushEnabled": true,
    "mandatoryProductPush": true,
    "canEditProductPush": false,
    "devicePushStatus": "unknown",
    "policyRevision": 4
  }]
}
```

PATCH 성공은 기존 `FCM_2011` 래퍼에 바뀐 그룹의 같은 응답 형태를 넣는다. `group/groupName/enabled`는 기존 FE 호환을 위해 유지하며 `enabled=productPushEnabled`다. `recipientEnabled`는 해당 사용자가 그룹의 잠재적 수신 대상인지를 나타낸다. 실제 수신자는 이벤트별 서버 규칙이 정하므로 이 값은 모든 이벤트의 전달 약속이 아니다. 반환하는 본인 그룹의 값은 `true`이며 시니어에게 보호자 전용 그룹은 반환하지 않는다. `devicePushStatus`는 서버가 OS 권한을 알 수 없어 현재 `unknown`으로 반환한다. 활성 토큰 유무를 OS 권한으로 해석하지 않는다. 각 행의 revision은 회원별 공통 값이다. 방장 안전 두 그룹은 `recipientEnabled/productPushEnabled/mandatoryProductPush=true`, `canEditProductPush=false`; 비방장은 두 안전 그룹도 수정할 수 있다.

## 4. 데이터 모델

| 위치 | 변경 | 불변식 |
| --- | --- | --- |
| `widyu-domain/fcm/PushSettingGroup` | W2의 `SAFETY`(안전 알림), `SAFE_ZONE`(안심구역), `MEDICATION_CHECK`(복약 확인), `GENERAL`(일반 알림), `NONE`을 설정 정본으로 쓴다. 앞의 두 값에 `mandatoryForLeader=true`; `NONE`은 저장·PATCH 금지. | `FcmCategory`는 변경하지 않는다. |
| `widyu-domain/fcm/MemberNotificationSetting` | 기존 `category` 컬럼의 Java 타입을 `PushSettingGroup`으로 바꾸고 `@Enumerated(STRING)`·`@JdbcTypeCode(SqlTypes.VARCHAR)`를 붙인다. | `UK(member_id,category)` 유지. 행이 없으면 켜짐. 방장 안전 그룹은 저장값과 무관하게 강제 켜짐; 비방장 안전 OFF는 행에 저장한다. |
| `widyu-domain/member/Member` | `notification_policy_revision BIGINT NOT NULL DEFAULT 0`을 추가한다. | 회원별 네 그룹 공통 revision. 기존 `medication_alarm_revision`과 별개다. 실제 값이 바뀐 PATCH에서만 증가한다. |
| `widyu-api/fcm/dto` | 요청 `policyRevision: Long` 필수; 응답에 §3의 여섯 필드 추가. `NotificationSettingResponse.from()/of()` 사용. | Service/Facade에서 DTO를 직접 `new`하지 않는다. |

W2의 `NotificationType.settingGroup()`이 반환하는 `PushSettingGroup`을 직접 사용한다. `NONE`과 `DATA_ONLY`는 개인 설정을 건너뛰지만 ACTIVE·토큰 owner·가족 검증은 유지한다. type이 NULL인 레거시 outbox에만 `PushSettingGroup.fromLegacy(FcmCategory)`를 쓴다. 폴백은 `MEDICINE_SCHEDULE→MEDICATION_CHECK`, `SAFE_ZONE→SAFE_ZONE`, `ALBUM/TARGET/HEALTH_SCHEDULE/WALK/HEART_MESSAGE/ETC→GENERAL`이다. `ALL/INCIDENT_SELF_CHECK/LOCATION_NOTICE`는 null로 설정을 건너뛴다. type이 있는 M05·S08/S09·G01 등은 FcmCategory와 관계없이 `GENERAL`이다.

운영 스크립트는 native ENUM이면 `member_notification_setting.category`를 먼저 `VARCHAR(32) NOT NULL`로 바꾼다. 이미 VARCHAR여도 최종 정의는 같다. 기존 `ALBUM/TARGET/HEALTH_SCHEDULE/WALK/ETC` 다섯 카테고리가 전부 저장돼 있고 모두 꺼진 회원에게만 `GENERAL=false` 한 행을 만든다. 하나라도 빠진 행은 기본 켜짐이므로 그 밖의 회원에게는 `GENERAL` 행을 만들지 않는다. `MEDICINE_SCHEDULE→MEDICATION_CHECK`, `SAFE_ZONE` 행 삭제를 수행한다. 이는 현행 `anyMatch` 읽기 의미와 같다. 사전 검사에서 매핑에 없는 실제 운영 값이 보이면 임의 변환 없이 중지한다.

## 5. 처리 흐름

1. GET은 `MemberUtil`로 인증 본인만 읽는다. `FamilyMembership.isLeader`와 회원별 그룹 행을 조회한다. 시니어는 `GENERAL`만 반환한다. 행이 없으면 기본 true, 방장 필수 그룹은 행과 무관하게 true다. 응답에 현재 `Member.notificationPolicyRevision`을 싣는다.
2. PATCH는 저장 트랜잭션에서 본인 회원 행을 `findByIdForUpdate`로 잠그고 요청 `policyRevision`과 최신 값을 비교한다. 다르면 409이며 행·revision은 그대로다. 현재 `FamilyMembership`을 읽어 방장 여부를 판정한다. 방장이 `SAFETY/SAFE_ZONE`을 끄면 409, 시니어가 `GENERAL` 외 그룹 또는 누구든 `NONE`을 쓰면 400이다. 다른 회원을 지정하는 입력은 허용하지 않는다.
3. 허용된 PATCH는 `(member_id,category)` 한 행을 upsert한다. 현재값(행 없으면 true)이 달라질 때만 회원 revision을 1 올리고 새 값을 응답한다. 동일 값 재요청도 요청 revision은 검사한다. 회원 행 잠금으로 같은 revision의 동시 PATCH 두 건이 모두 저장되지 않게 한다.
4. W3의 `claim`과 `preflight`는 모두 `FcmEligibility.eligible`을 호출한다. 판정 순서는 ACTIVE·토큰 owner → type이 있으면 `type.settingGroup()`(`NONE/DATA_ONLY`는 설정 건너뜀, 필수 그룹이고 현재 방장이면 true, 아니면 저장 행 또는 기본 true) → type이 없으면 `PushSettingGroup.fromLegacy(fcmCategory)`(null이면 설정 건너뜀) → 관련 회원 ACTIVE·같은 가족이다. claim 뒤 역할·설정 변경은 preflight에서 다시 잡는다.
5. `FcmOutboxService.enqueue`는 W3대로 수신자·가족을 확인한 뒤 센터 저장 타입 행을 토큰 루프 앞에서 만든다. 설정 OFF여도 센터 행은 남고 outbox만 claim/preflight에서 취소된다. `pushEligible`은 타입의 enqueue 정책 스냅샷이며 개인 설정이나 최종 전송 결과가 아니다. 새 이벤트 리스너·외부 호출은 없다.

## 6. 예외 / 에러 처리

| 조건 | HTTP / 코드 | 결과 |
| --- | --- | --- |
| 빈 값·알 수 없는 그룹·`NONE`, 시니어의 보호자 전용 그룹 | 400 / `FCM_4001` (`INVALID_FCM_CATEGORY`) | 행·revision 불변. |
| 방장의 `SAFETY/SAFE_ZONE` 끄기 | 409 / 신규 `FCM_4090` (`MANDATORY_NOTIFICATION_PUSH`) | UI 우회 요청도 거부. |
| 요청 revision 불일치 | 409 / 신규 `FCM_4091` (`NOTIFICATION_POLICY_REVISION_CONFLICT`) | 행·revision 불변. FE는 GET으로 최신 상태를 다시 표시. |
| revision 누락·음수 | 400 / 요청 검증 오류 | 수정하지 않음. |
| 비방장 푸시 OFF·토큰 없음·OS 권한 거부 | PATCH는 200, 전송 단계는 별도 | 수신 대상 정책·센터 행 유지. `unknown`은 전송 성공 뜻이 아님. |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1 (N10): 보호자 GET은 네 그룹, 시니어 GET은 가정에 따른 `GENERAL` 하나를 준다. 새 보호자·행 없는 설정은 켜짐이고 `enabled=productPushEnabled`다.
- [x] AC2 (N11): 방장의 안전 두 그룹 끄기는 `FCM_4090`/409이고 행·revision이 그대로다. 응답은 `recipientEnabled/productPushEnabled/mandatoryProductPush=true`, `canEditProductPush=false`다. 비방장은 두 그룹 켜기·끄기가 200이며 본인 행만 바뀐다.
- [x] AC3 (N11): 응답이 `recipientEnabled`, `productPushEnabled`, `mandatoryProductPush`, `canEditProductPush`, `devicePushStatus`(`unknown` 허용), `policyRevision`을 분리한다. 다른 회원의 설정 조회·수정 API는 없다.
- [x] AC4 (N12): 비방장의 안전 푸시를 꺼도 W3와 통합한 센터 행은 1건 생기고 해당 outbox는 claim 또는 preflight에서 취소된다. 토큰 0개여도 센터 행은 생긴다.
- [x] AC5: type 설정 그룹이 레거시 카테고리보다 우선한다. S08/S09·G01·M05는 `GENERAL`, M04는 `MEDICATION_CHECK`, 심박 위급은 `SAFETY`, 안심구역 이탈은 `SAFE_ZONE`을 따른다. type NULL은 `fromLegacy(FcmCategory)` 폴백, `NONE/DATA_ONLY`는 설정 건너뛰기다. claim·preflight 모두 검증한다.
- [x] AC6: 마이그레이션 매핑 단위 테스트는 다섯 카테고리 전부 꺼진 회원만 `GENERAL=false` 한 행, 일부 행 누락 또는 하나라도 켜진 회원은 `GENERAL` 행 없음, `MEDICINE_SCHEDULE→MEDICATION_CHECK`, `SAFE_ZONE` 삭제, 회원 간 분리를 검증한다. H2를 MySQL native ENUM DDL 검증으로 간주하지 않는다.
- [x] AC7: 낡은 revision 요청은 `FCM_4091`/409다. 동시 PATCH는 하나의 revision만 소비한다. 실제 값이 바뀐 PATCH는 revision을 올린다.
- [x] AC8: GET·기존 PATCH 경로를 유지하고 Swagger에 새 필드·400·409를 반영한다. 새 PATCH 경로는 §3의 미채택 제안으로만 표시한다.
- [x] AC9: JUnit 5 + Mockito `given/willReturn`, 한글 언더스코어 메서드명, 행위형 `@DisplayName`, 상태 검증 우선 규칙을 따른다. 엔티티 변경 뒤 `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과하고 ERD를 갱신한다.

## 8. 영향 범위 / 마이그레이션

운영 `application-prod.yml`은 `ddl-auto=validate`다. **앱 배포 전에** `scripts/mysql/migrate_notification_setting_group.sql`을 적용하지 않으면 새 Java enum·컬럼 타입과 구 데이터가 맞지 않아 기동 또는 설정 조회가 실패한다. 로컬·dev의 `ddl-auto=update`에 의존하지 않는다.

순서는 ① 운영 `SHOW CREATE TABLE member_notification_setting`, 카테고리별 건수·중복·미매핑 값 확인 및 백업 ② 구 앱의 설정 읽기·쓰기 트래픽 중지 ③ native ENUM이면 `MODIFY COLUMN category VARCHAR(32) NOT NULL` 선행 ④ 회원별 GENERAL 집계 후 구 행 삭제·새 행 삽입, 복약 행 개명, SAFE_ZONE 삭제 ⑤ `member.notification_policy_revision BIGINT NOT NULL DEFAULT 0` 추가 ⑥ 남은 구 값·UK·집계 수치 확인 ⑦ 새 앱 배포 후 설정 트래픽 재개다. MySQL DDL은 암묵적 커밋이 있으므로 전체 원자 트랜잭션으로 가정하지 않는다. SQL 주석에 단계별 재실행·복구 절차를 기록한다. `UK(member_id,category)`는 유지한다.

`FcmCategory`와 센터·outbox의 `fcm_category` native ENUM 정의는 변경하지 않는다. H2는 MySQL native ENUM을 흉내 내지 않아 단위·통합 테스트가 운영 DDL 적용 성공을 증명하지 않는다. 운영 MySQL에서 스크립트를 별도 검증하고 PR 비고에 선적용 순서와 H2 한계를 남긴다. 방장 이전 직후 한 요청이 이전 역할을 읽을 수 있으며 방장 권한은 판정 시점의 멤버십에 따른다. claim·preflight마다 방장 여부 조회가 추가되는 비용을 관찰한다.

## 9. 미결정 사항 (Open Questions)

계획 §5의 회신 대기 항목과 가정을 그대로 둔다. 이 LLD에 직접 적용되는 것은 「시니어 푸시 설정 항목」이며, 2026-10-02 구현·자체 검수 시점까지 새 회신은 없었다. 가정이 바뀌는 회신이 오면 담당 Task의 결정 게이트에서 반영한다.

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

`PATCH /api/v1/notification-channel-preferences/{category}`의 추가는 명세 권고이나 제안, 미채택이다. 필수 인수조건은 기존 PATCH의 새 계약이다. revision 저장 위치·동시성 방법은 §4·§5의 구현 설계다.

## 10. 참고

- ADR-0037 결정 3·5, ADR-0038; [LLD-0060](LLD-0060-notification-type-registry.md), [LLD-0062](LLD-0062-notification-center-row-per-event.md), [ERD-0001](../erd/ERD-0001-initial-domain.md).
- 55차 승인판 `HEMLO-REVIEW-v0.5` §8·§13.3(충돌 시 우선), `NOTIFICATION-CENTER-UX-SPEC-v0.3` §11·§12.2·§12.3, `FE-DELIVERY-PACKAGE-v1.2` §8·§12·§15, `FE-HANDOFF-v0.4` §8, `NOTIFICATION-COPY-CATALOG-v0.4` M04·S08·S09.
- `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` N10·N11·N12.
