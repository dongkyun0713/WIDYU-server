# LLD-0056: 보호자 위치 열람 기록과 시니어 통보 (L2)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #665 |
| 관련 ADR | [ADR-0036](../adr/ADR-0036-privacy-records-consent-access-logs.md) 결정 3·4·6, ADR-0020 |
| 작성자 | Claude |
| 작성일 | 2026-09-21 |
| 선행 | #664(LLD-0055, `ConsentService.isGranted`). 이 브랜치는 feature/664 위에 쌓는다 |

## 1. 목적 / 배경

보호자가 시니어 위치를 조회하면 누가·언제·어떤 경로로 봤는지 자동 기록하고 시니어에게 통보한다(위치정보법 제16조②·제19조③④). 지금은 다섯 경로 어디에도 기록이 없다.

## 2. 범위

### In scope
- widyu-api / widyu-domain, 패키지 `com.widyu.location.access`
- `location_access_log` 테이블·엔티티
- 읽기 경로 5곳에 기록 훅
- 통보(즉시/모아서) + 시니어 조회 API
- `FcmCategory.LOCATION_NOTICE` 추가, 설정 `location-access.*`

### Out of scope
- 열람 차단, 앱 화면, 관리자 조회, 삭제·보관 상한

## 3. 인터페이스 / API

| 메서드·경로 | 호출자 | 요청 | 응답 |
| --- | --- | --- | --- |
| `GET /api/v1/location/access-logs/mine?from=&to=&page=&size=` | 시니어 본인 | 기간 선택(ISO date-time), 기본 최근 30일 | `Page<LocationAccessLogResponse>` 최근순 |

`LocationAccessLogResponse`: `{ "id", "viewerMemberId", "viewerName", "path", "accessedAt", "notifiedAt" }`. 좌표는 없다(열람 사실만).

`path` 값(`LocationAccessPath` enum): `REST_LAST`(최근 위치), `REST_TRAIL`(이동 경로), `REST_FAMILY`(가족 전체 목록, 시니어마다 1행), `WS_SUBSCRIBE`(STOMP 구독), `HOME_OUTING`(보호자 홈 카드 외출 상태).

## 4. 데이터 모델

`location_access_log` (엔티티 `LocationAccessLog`). 추가 전용 + `notified_at` 갱신만.

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `location_access_log_id` | BIGINT PK | | |
| `viewer_member_id` | BIGINT | NOT NULL | 보호자 |
| `senior_member_id` | BIGINT | NOT NULL | 대상 |
| `path` | VARCHAR(20) | NOT NULL | |
| `accessed_at` | DATETIME(6) | NOT NULL | 서버 시각 |
| `notified_at` | DATETIME(6) | NULL | 통보 FCM enqueue 시각 |

인덱스 `(senior_member_id, accessed_at)`, `(senior_member_id, notified_at)`.

설정 `location-access`(새 `LocationAccessProperties`, `application.yml`): `immediate-cooldown-min`(`${LOCATION_ACCESS_IMMEDIATE_COOLDOWN_MIN:10}`), `digest-cron`(`${LOCATION_ACCESS_DIGEST_CRON:0 0 9 * * *}`).

## 5. 처리 흐름

### 5.1 기록 — `LocationAccessLogService.record(viewerId, seniorId, path)`
- `viewerId.equals(seniorId)`면 아무것도 안 한다.
- `@Transactional(propagation=REQUIRES_NEW)` INSERT. 호출부는 `try/catch(Exception)`으로 감싸 WARN(예외 클래스명·path)만 남기고 조회를 계속한다.
- INSERT 뒤 같은 트랜잭션에서 통보 판단(5.3 즉시 모드).

훅 위치(권한 검증 **통과 뒤**, 응답 생성 전):

| 경로 | 위치 |
| --- | --- |
| `REST_LAST` | `RealtimeLocationService.getLastLocation` |
| `REST_TRAIL` | `RealtimeLocationService.getLocationTrail` |
| `REST_FAMILY` | `RealtimeLocationService.getTrackedSeniors` — 반환 목록의 시니어마다 1행 |
| `WS_SUBSCRIBE` | `JwtChannelInterceptor.handleSubscribe` — `verifyFamilyAccess` 통과 직후 |
| `HOME_OUTING` | `GuardianHomeService`가 `getOutingStatus`를 부르는 곳 — 시니어마다 1행 |

### 5.2 통보 방식 결정
`consentService.isGranted(seniorId, ConsentKey.LOCATION_NOTICE_BATCHED)`가 true면 「모아서」, 아니면 「즉시」.

### 5.3 즉시 모드
기록 직후, 같은 `(senior, viewer)`로 `notified_at`이 `now − cooldown` 이후인 행이 있으면 FCM을 보내지 않고 `notified_at`도 비워 둔다(요약 대상으로 남긴다 — 다음 다이제스트가 「즉시 모드에서 합쳐진 건」도 알린다). 없으면 시니어에게 FCM 1건(`LOCATION_NOTICE`, 제목 「위치 조회 알림」, 본문 「{보호자 이름}님이 내 위치를 확인했어요」, `relatedMemberId=viewerId`, `emergency=false`) enqueue 후 그 행 `notified_at=now`.

### 5.4 모아서 모드 — `LocationAccessDigestScheduler` (`@Scheduled(cron="${location-access.digest-cron}")`, `SchedulerConfig` 조건 준수)
`notified_at IS NULL`인 행을 시니어별로 묶어 각 시니어에게 FCM 1건(「지난 기간 보호자 {n}명이 위치를 {m}회 확인했어요」) enqueue 후 해당 행들 `notified_at=now` 벌크 갱신. 즉시 모드 시니어의 미통보 행(쿨다운으로 합쳐진 것)도 함께 처리한다. 시니어별 try/catch.

### 5.5 조회
`GET …/access-logs/mine`: `seniorId = 현재 회원`, 기간 필터·페이지. `viewerName`은 `Member.name`(가족이므로 노출 가능).

## 6. 예외 / 에러 처리

새 ErrorCode 없음. 기록·통보 실패는 조회를 막지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] `getLastLocation`·`getLocationTrail`·`getTrackedSeniors`·홈 카드·STOMP 구독 각각에서 행이 생긴다(경로 값 확인). 본인 조회는 행이 없다.
- [ ] 기록 서비스가 예외를 던져도 위치 응답은 정상이다.
- [ ] 즉시 모드: 첫 조회에 FCM 1건과 `notified_at`, 쿨다운 안 재조회는 FCM 없음·`notified_at` null, 쿨다운 뒤 재조회는 FCM.
- [ ] 모아서 모드: 조회 시 FCM 없음, 다이제스트가 미통보 행을 묶어 시니어당 FCM 1건과 `notified_at` 갱신.
- [ ] 시니어 조회 API가 기간·페이지로 자기 기록만 준다. 다른 회원 기록은 보이지 않는다.
- [ ] 응답에 좌표가 없다.
- [ ] `JwtChannelInterceptorTest` 기존 회귀 유지.
- [ ] `./gradlew compileJava`, `run-module-tests.sh`, `verify.sh --base` 통과.

## 8. 영향 범위 / 마이그레이션

운영은 `ddl-auto: validate`이므로 배포 **전에** DDL을 손으로 적용한다.

### 8.1 새 테이블
`scripts/mysql/create_location_access_log.sql` — `location_access_log` 생성. `path`는 MySQL ENUM이 아니라 `VARCHAR(20)`이므로 경로 값이 늘어도 ALTER가 필요 없다.

Hibernate 6은 MySQL에서 `@Enumerated(EnumType.STRING)`을 native ENUM으로 매핑한다. 그대로 두면 엔티티(ENUM)와 운영 DDL(VARCHAR)이 어긋나 `validate`가 깨지므로, `LocationAccessLog.path`에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 붙여 VARCHAR로 고정했다.

### 8.2 `FcmCategory.LOCATION_NOTICE` 추가에 따른 컬럼 확장
`scripts/mysql/alter_fcm_category_location_notice.sql`.

| 테이블·컬럼 | 상태 | 조치 |
| --- | --- | --- |
| `fcm_outbox.fcm_category` | `create_fcm_outbox.sql`이 `VARCHAR(32)`로 생성 | 없음 |
| `fcm_notification.fcm_category` | 손으로 쓴 생성 SQL 없음(Hibernate 생성) — ENUM일 수 있음 | ALTER 후보 |
| `member_notification_setting.category` | 손으로 쓴 생성 SQL 없음(Hibernate 생성) — ENUM일 수 있음 | ALTER 후보 |

ALTER 후보 두 개는 **운영에서 먼저 확인한 뒤** 실행한다. ENUM이면 실행하고, VARCHAR면 건너뛴다.

```sql
SHOW COLUMNS FROM fcm_notification LIKE 'fcm_category';
SHOW COLUMNS FROM member_notification_setting LIKE 'category';
```

```sql
ALTER TABLE fcm_notification
    MODIFY COLUMN fcm_category ENUM(
        'ALL', 'ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'MEDICINE_SCHEDULE',
        'HEART_MESSAGE', 'SAFE_ZONE', 'LOCATION_NOTICE', 'ETC');

ALTER TABLE member_notification_setting
    MODIFY COLUMN category ENUM(
        'ALL', 'ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'MEDICINE_SCHEDULE',
        'HEART_MESSAGE', 'SAFE_ZONE', 'LOCATION_NOTICE', 'ETC') NOT NULL;
```

`LOCATION_NOTICE`는 `NotificationSettingGroup` 어디에도 넣지 않았다. 미등록 카테고리는 `NotificationSettingService.isNotificationEnabled`가 기본 허용하므로 설정으로 끌 수 없고, 법이 요구하는 통보에는 그 동작이 맞다.

### 8.3 그 밖
ERD(`ERD-0001` 엔티티·인덱스 표·조회 기준), `backend/CLAUDE.md`의 `location.access` 절, `application.yml`의 `location-access` 블록(`LOCATION_ACCESS_IMMEDIATE_COOLDOWN_MIN`·`LOCATION_ACCESS_DIGEST_CRON`). `FcmCategoryResponse`의 switch가 모든 값을 덮어야 해 「위치 조회 알림」 라벨을 함께 추가했다.

## 9. 미결정 사항 (Open Questions)

- 없음. 쿨다운은 설정값이며 법무 검토가 「건마다」를 요구하면 0으로 둔다(ADR-0036).

## 10. 참고
`RealtimeLocationRestController`·`RealtimeLocationService`, `JwtChannelInterceptor.handleSubscribe`, `GuardianHomeService`, `FamilyAccessService.verifyFamilyAccess`, `WalkNotificationListener`(일일 cron 선례), `SafeZoneNotificationListener`(FcmSendDto 조립), `FcmService.sendMessageToUser`, LLD-0055.
