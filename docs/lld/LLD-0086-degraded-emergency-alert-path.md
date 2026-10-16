# LLD-0086: DB 장애 중 심박 긴급 알림 저하 경로

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #771 |
| 관련 ADR | [ADR-0040](../adr/ADR-0040-degraded-emergency-alert-without-database.md), [ADR-0028](../adr/ADR-0028-fcm-durable-delivery.md) |
| 작성자 | Claude (계획), 구현 Opus |
| 작성일 | 2026-10-16 |

## 1. 목적 / 배경

RDS가 응답하지 않는 동안 심박 단건 경로는 측정값 저장과 outbox 적재가 함께 실패해 보호자에게 아무 알림도 가지 않는다. 수신자 해석이 전부 DB라 큐만 바꿔서는 해결되지 않는다. 보호자 토큰 스냅샷과 긴급 이벤트를 Redis에 두고 DB 없이 발송하는 경로를 만든다. 결정과 대안은 ADR-0040에 있다.

## 2. 범위

### In scope
- 변경 모듈: **widyu-api만**. 엔티티·DDL 변경 없음.
- 보호자 토큰 스냅샷 주기 갱신(Redis, 시니어별)
- 심박 단건 처리(`HeartRateService.processHeartRate`)의 DB 접근 예외 분기
- Redis Stream 적재·소비·발송, 시니어별 5분 표식
- outbox claim 단계의 표식 확인(중복 발송 차단)
- 설정 `fcm.degraded.*`, 메트릭, 테스트

### Out of scope
- DB 복구 후 `fcm_notification` 이력 보정, 가족 제외·탈퇴 시 스냅샷 즉시 무효화, 장애 주입 측정 → LLD-0087 (#772)
- 배치 v2·낙상 경로(sensor 봉투 저장이 선행) → 별도 ADR
- 장애 중 측정값 재생 버퍼
- 앱 측 `notificationId` dedup

## 3. 인터페이스 / API

새 HTTP API 없음. 기존 심박 단건 API·WebSocket 메시지의 계약은 그대로다.

- STOMP `/app/heart-rate/send-single`(현재 심박 단건의 유일한 진입점, REST 단건 엔드포인트는 없다): DB 접근 예외 상황에서 **500 대신** AI 판정값으로 만든 `HeartRateStatusResponse`(memberId, heartRateStatus, heartRate, measuredAt)를 돌려준다. `fcm.degraded.enabled=false`면 기존대로 예외가 전파된다.

### 설정 (`application-fcm.yml`, `local | dev | prod` 프로파일, test는 `application-test.yml`)

```yaml
fcm:
  degraded:
    enabled: ${FCM_DEGRADED_ENABLED:true}
    snapshot-ttl: ${FCM_DEGRADED_SNAPSHOT_TTL:24h}
    snapshot-refresh-ms: ${FCM_DEGRADED_SNAPSHOT_REFRESH_MS:300000}   # @Scheduled 문자열 속성
    poll-ms: ${FCM_DEGRADED_POLL_MS:1000}                               # @Scheduled 문자열 속성
    lease: ${FCM_DEGRADED_LEASE:60s}
    stream-max-len: ${FCM_DEGRADED_STREAM_MAX_LEN:10000}
```

`FcmDegradedProperties`(record, `@ConfigurationProperties(prefix = "fcm.degraded")`): `enabled`, `snapshotTtl`, `lease`, `streamMaxLen`. 재시도 상한과 긴급 TTL은 `FcmDeliveryProperties`의 `maxRetries`·`emergencyTtl`을 그대로 쓴다. 생성자에서 양수 검증, 위반 시 `IllegalArgumentException`(기존 `FcmDeliveryProperties`와 같은 방식).

### Redis 키

| 키 | 타입 | 값 | TTL |
| --- | --- | --- | --- |
| `fcm:degraded:recipients:{seniorId}` | String(JSON) | `{"seniorName","image","recipients":[{"guardianId","tokenId","token"}]}` | `snapshot-ttl` |
| `fcm:degraded:alerted:{seniorId}` | String | `"1"` (SET NX) | `fcm.delivery.emergency-ttl` |
| `fcm:degraded:stream` | Stream | 항목 필드는 5.3 | `MAXLEN ~ stream-max-len` |
| `fcm:degraded:reconcile` | List(JSON) | 성공 발송 기록(LLD-0087이 소비) | 없음 |

소비자 그룹 이름 `fcm-degraded`, 소비자 이름은 호스트명(없으면 `widyu-api`).

### 메트릭 (Micrometer counter)

- `fcm.degraded.published` — 스트림에 적재한 항목 수
- `fcm.degraded.sent` — 발송 성공 수
- `fcm.degraded.skipped{reason=no_snapshot|throttled|disabled|redis_error}`
- `fcm.degraded.dropped{reason=expired|permanent_token|retries_exhausted|rejected}` (`rejected`는 재시도 불가·영구 토큰 아님인 FCM 거부, 구현 중 추가)
- `fcm.degraded.deduped` — outbox claim에서 표식으로 취소한 행 수

## 4. 데이터 모델

DB 변경 없음. ERD 변경 없음(`docs/erd/` 관련 문서 없음으로 기록).

Redis 값은 `StringRedisTemplate` + Jackson `ObjectMapper`로 직렬화한다(기존 `RedisTemplate<String,Object>`의 JSON 타입 정보 직렬화를 쓰지 않는다). 토큰 문자열은 스냅샷·스트림·보정 목록에만 있고 **로그·메트릭 태그에 넣지 않는다**.

새 클래스(모두 `com.widyu.fcm.application.degraded`):

| 클래스 | 역할 |
| --- | --- |
| `FcmDegradedProperties` | 설정 |
| `EmergencyRecipientSnapshotService` | 스냅샷 생성·조회. `refreshAll()` 주기 실행, `refreshFamily(familyId)` (LLD-0087 훅이 호출), `find(seniorId)` |
| `EmergencyRecipientSnapshot` | record: `seniorName`, `image`, `List<Recipient(guardianId, tokenId, token)>` |
| `DegradedAlertMarker` | `tryMark(seniorId)` (SET NX EX), `isMarked(seniorId)` (Redis 실패 시 false) |
| `DegradedEmergencyAlertService` | `alertWithoutDatabase(seniorId, measuredAt)` — 표식 → 스냅샷 → XADD |
| `DegradedEmergencyAlertConsumer` | 주기 XREADGROUP + pending 회수, `FcmTransport` 발송, XACK, 보정 목록 적재 |
| `DegradedDelivery` | record: 스트림 항목 1건의 역직렬화 |

## 5. 처리 흐름

### 5.1 스냅샷 갱신 (`EmergencyRecipientSnapshotService.refreshAll`)

1. `@Scheduled(fixedDelayString = "${fcm.degraded.snapshot-refresh-ms:300000}")`. **`@Transactional`을 두지 않는다**(구현 시 변경): DB가 안 될 때 트랜잭션 시작 예외는 메서드 안 catch에 닿지 않고, 조회 예외를 잡아도 롤백 전용 표시가 커밋 시점 예외로 다시 나와 5단계를 지킬 수 없다. 두 조회는 각자 읽기만 한다. `enabled=false`면 돌지 않는다.
2. 한 번의 쿼리로 (시니어 memberId, 시니어 이름, 프로필 이미지, 보호자 id, 토큰 id, 토큰 문자열)을 가져온다. 조건: `SeniorProfile.family = FamilyMembership.family`, `MemberFcmToken.member = FamilyMembership.guardian AND active = true`, 시니어·보호자 `status = ACTIVE`. 리포지토리 메서드는 `MemberFcmTokenRepository` 또는 `FamilyMembershipRepository`에 JPQL projection으로 추가한다(QueryDSL도 가능).
3. 알림 설정은 현재 `PushSettingGroup` 기준이다. 심박 위급(`NotificationType.HEART_RATE_EMERGENCY`)의 설정 그룹은 `SAFETY`이고 방장에게는 필수다. 그래서 2단계 쿼리에 `LEFT JOIN MemberNotificationSetting(category = SAFETY)`을 넣어 `방장 OR 설정 없음 OR enabled = true`인 보호자만 남긴다(`NotificationSettingService.isNotificationEnabled`와 같은 규칙, N+1 없음).
4. 시니어별로 JSON을 `SET key value EX snapshot-ttl`. 보호자 토큰이 0명인 시니어는 키를 **삭제**한다(낡은 스냅샷 방지).
5. Redis 예외는 warn 로그 1줄(시니어 수만)로 끝내고 다음 주기에 다시 시도한다. DB 예외도 같다. 둘 다 스케줄러를 죽이지 않는다.
6. `refreshFamily(familyId)`는 같은 쿼리를 가족 하나로 좁혀 실행한다(LLD-0087 훅용, 이번 PR에서 메서드만 둔다).
7. 기동 직후 첫 갱신은 `fixedDelay`의 `initialDelay`를 두지 않고 바로 돈다(스냅샷 없는 창을 줄인다).

### 5.2 심박 단건 처리 분기 (`HeartRateService.processHeartRate`)

```text
try {
    validateMemberExists(memberId);                       // DB
    if (existsByMemberIdAndMeasuredAt(...)) return getHeartRateStatus(memberId);
} catch (DataAccessException | TransactionException e) {
    return degraded(memberId, request, e);                // 아래
}
detection = detector.detect(...);                        // AI만, DB 없음
try {
    result = persistence.saveMeasurement(...);
} catch (DataAccessException | TransactionException e) {
    return degraded(memberId, request, detection, e);
}
return HeartRateStatusResponse.from(result);
```

`degraded(...)`:
1. `fcm.degraded.enabled`가 false면 원래 예외를 그대로 던진다.
2. 판정이 아직 없으면 `detector.detect(...)`를 호출한다. AI 예외는 기존대로 전파한다.
3. `detection.emergency()`면 `DegradedEmergencyAlertService.alertWithoutDatabase(memberId, request.measuredAt())`를 호출한다. 이 호출은 예외를 밖으로 내지 않는다.
4. `HeartRateStatusResponse.of(memberId, detection.status(), request.heartRate(), request.measuredAt())`를 돌려준다(새 정적 팩토리).
5. warn 로그 1줄: `memberId`, 예외 클래스명, 위급 여부만. **bpm·AI 응답을 로그에 넣지 않는다**(#639).

`BusinessException`(회원 없음 등)은 catch 대상이 아니다. `HeartRateService`에는 클래스 수준 `@Transactional`이 없어 catch 지점이 롤백 전용 트랜잭션 안에 있지 않다는 것을 테스트로 고정한다(`processHeartRate`에 `@Transactional`을 붙이지 않는다).

### 5.3 적재 (`DegradedEmergencyAlertService.alertWithoutDatabase`)

1. `DegradedAlertMarker.tryMark(seniorId)` — `SET fcm:degraded:alerted:{seniorId} 1 NX` + TTL `emergencyTtl`. 실패(이미 있음)면 `skipped{throttled}` 후 종료.
2. `EmergencyRecipientSnapshotService.find(seniorId)` — 없으면 `skipped{no_snapshot}` 후 종료. 표식은 남겨 두지 않는다(**표식 삭제**해서 스냅샷이 생기면 다음 샘플이 다시 시도하게 한다).
3. 수신자마다 `XADD fcm:degraded:stream *` 후 `XTRIM fcm:degraded:stream MAXLEN ~ streamMaxLen` 한 번(Spring Data Redis 3.3의 `StreamOperations`에 XADD MAXLEN 옵션이 없어 구현 시 변경). 필드:
   `seniorId, guardianId, tokenId, token, title, body, image, category=HEART_MESSAGE, measuredAt(ISO), expiresAt(epoch ms = now + emergencyTtl)`.
   제목·본문은 정상 경로 최초 보호자 알림과 같은 승인 문구표 `NotificationCopy.of(HEART_RATE_EMERGENCY, "S04", {시니어 이름})`다. 정상 경로(`IncidentEscalation.notificationFor`)가 이미 이 문구표 하나를 쓰므로 옮길 상수가 없고 `HeartRateEmergencyNotificationService`는 바꾸지 않는다(설계 당시 문구 서술은 현재 코드와 달랐다). 이미지 null은 빈 문자열로 싣고 소비자가 null로 되돌린다.
4. `published` 카운트. Redis 예외는 `skipped{redis_error}` + warn 로그 후 종료(요청은 실패시키지 않는다).

### 5.4 소비 (`DegradedEmergencyAlertConsumer.poll`)

1. `@Scheduled(fixedDelayString = "${fcm.degraded.poll-ms:1000}")`, `@Transactional(propagation = NOT_SUPPORTED)`. `enabled=false`면 돌지 않는다. 그룹은 기동 시점이 아니라 **첫 poll에서** `XGROUP CREATE ... 0 MKSTREAM`으로 만들고 `BUSYGROUP`은 무시한다(기동 중 Redis가 없어도 컨텍스트가 뜨게, 그룹보다 먼저 적재된 항목도 읽게). poll이 예외로 끝나면 다음 주기에 그룹을 다시 확인한다.
2. 먼저 pending 회수: `XPENDING` 으로 idle ≥ `lease`인 항목을 `XCLAIM` 해 이 소비자로 가져온다. 전달 횟수(`deliveryCount`)가 `1 + maxRetries` **이상**이면(전체 시도를 다 썼으면) `XACK` + `dropped{retries_exhausted}`.
3. 그다음 `XREADGROUP GROUP fcm-degraded {consumer} COUNT 50 STREAMS fcm:degraded:stream >`.
4. 항목마다:
   - `expiresAt`이 지났으면 `XACK` + `dropped{expired}`.
   - `FcmTransport.send(FcmDelivery, () -> true)` 호출(구현 시 변경: `FcmDelivery`에 `expiresAt`을 실어 FCM TTL을 정상 경로처럼 남은 시간으로 둔다. TTL이 없으면 단말이 꺼져 있을 때 낡은 위급이 몇 시간 뒤 뜬다). DTO는 `notificationType=HEART_RATE_EMERGENCY`, `emergency=true`, `relatedMemberId=seniorId`, `seniorId`, data는 `FcmSendDto.dataForEnqueue(streamId)`(eventId=스트림 id, 딥링크 `/location?seniorId=`). `notificationId`는 알림센터 행 id(숫자) 의미라 싣지 않는다.
   - `success` → `XACK`, `RPUSH fcm:degraded:reconcile {json}` (`guardianId, tokenId, seniorId, title, body, image, category, sentAtMs, streamId`), `sent` 카운트.
   - `permanentToken` → `XACK` + `dropped{permanent_token}`. 토큰 비활성화는 DB가 없어 하지 않는다(다음 스냅샷 갱신이 거른다).
   - `retryable` → ack 하지 않고 둔다. lease가 지나면 2단계가 다시 가져온다.
   - 재시도 불가·영구 토큰 아님 → `XACK` + `dropped{rejected}`.
   - 전송 중 예외·항목 파싱 실패 → ack 하지 않고 warn 로그(스트림 id·예외 클래스명만). 같은 배치의 다음 항목은 계속 처리한다.
   - 성공 시 순서는 `XACK` → `RPUSH`다. 같은 위급을 두 번 보내지 않는 쪽을 보정 기록보다 우선한다.
5. Redis 예외는 warn 로그 1줄로 끝내고 다음 주기로 넘긴다. 평시에는 스트림이 비어 있어 `XREADGROUP` 한 번만 돈다.

### 5.5 정상 경로 중복 차단 (`FcmOutboxTransactions.claim`)

`row.claim(...)` 성공 직후, `row.isEmergency() && row.getNotificationType() == HEART_RATE_EMERGENCY && row.getRelatedMemberId() != null` 이면(구현 시 변경: `HEART_MESSAGE` 카테고리에는 워치 도움 요청 `SELF_HELP_REQUESTED`도 긴급으로 들어 있어, 카테고리로 거르면 저하 경로가 알린 뒤 5분 동안 시니어가 직접 누른 도움 요청까지 취소된다) `DegradedAlertMarker.alreadyAlerted(row)`(내부에서 `isMarked(relatedMemberId)`)를 본다. true면 `row.cancel()` + `deduped` 카운트 후 `null` 반환. Redis 조회 실패는 false로 본다(fail-open). `FcmEligibility`는 건드리지 않는다.

## 6. 예외 / 에러 처리

새 에러 코드 없음.

| 상황 | 동작 |
| --- | --- |
| DB 접근 예외 + `enabled=false` | 원래 예외 전파(기존 동작) |
| DB 접근 예외 + 위급 아님 | AI 판정값으로 응답, 적재 없음 |
| DB 접근 예외 + AI 예외 | AI 예외 전파(기존 동작과 동일) |
| Redis 예외(적재) | 응답은 그대로, `skipped{redis_error}` |
| Redis 예외(소비·표식 조회) | 다음 주기 재시도 / outbox는 발송 진행 |
| 스냅샷 없음 | 적재 생략, 표식 해제 |

## 7. 인수조건 (Acceptance Criteria)

- [x] `HeartRateServiceTest`: `DataAccessResourceFailureException`·`CannotCreateTransactionException`·`TransactionSystemException` 각각에서 위급 판정이면 `alertWithoutDatabase(memberId, measuredAt)`가 1회 호출되고 응답이 AI 판정값이다.
- [x] `HeartRateServiceTest`: DB 예외 + 위급 아님이면 적재 호출이 없고 응답이 판정값이다.
- [x] `HeartRateServiceTest`: `enabled=false`면 원래 예외가 전파되고 적재 호출이 없다.
- [x] `HeartRateServiceTest`: DB 정상이면 저하 경로가 호출되지 않고 기존 응답과 같다. `BusinessException`은 저하 경로로 가지 않는다.
- [x] `DegradedEmergencyAlertServiceTest`: 같은 시니어 2회 연속 호출에 적재는 1회다(표식). 스냅샷 없음이면 적재 0회이고 표식이 해제된다. 수신자 3명이면 항목 3개가 적재되고 필드에 토큰·제목·만료가 들어간다.
- [x] `DegradedEmergencyAlertConsumerTest`(StreamOperations mock): 성공 → ack + 보정 목록 1건, retryable → ack 없음, 만료 → ack + dropped, 영구 무효 토큰 → ack, 전달 횟수 초과 → ack + dropped.
- [x] `EmergencyRecipientSnapshotServiceTest`(`@DataJpaTest`, H2에서 JPQL 실행 + Redis mock): 비활성 회원·비활성 토큰·안전 알림(`SAFETY`) 끈 비방장 보호자가 제외되고 방장은 설정과 무관하게 포함되며, 수신자 0명 시니어·탈퇴 시니어의 키는 삭제된다.
- [x] `FcmOutboxTransactionsTest`: 표식이 있는 시니어의 긴급 심박 행은 claim에서 취소되고 `deduped`가 1 증가한다. 표식 조회 예외면 정상 claim된다. 일반 알림 행은 표식과 무관하다.
- [x] `DegradedEmergencyStreamRedisTest`(로컬 Redis `assumeTrue`, CI는 실행. 2026-10-16 로컬은 6379 Docker Redis가 응답하지 않아 임시 redis-server 6380으로 포트만 바꿔 1회 실행해 2건 통과): XADD → poll → mock transport 성공 → XACK 되고 pending 0, 보정 목록 1건. retryable 뒤 lease 경과 → 재전달돼 전달 횟수 2.
- [x] `FcmDegradedPropertiesTest`: 0·음수 값은 기동 실패.
- [x] 심박 위급 제목·본문 문구가 정상 경로와 저하 경로에서 같은 문구표 `NotificationCopy` S04를 쓴다(`DegradedEmergencyAlertServiceTest`가 S04 렌더링 결과와 비교, 기존 `HeartRateEmergencyNotificationServiceTest` 통과 유지).
- [x] 로그에 bpm·토큰·AI 응답이 남지 않는다(`HeartRateServiceTest` 로그 캡처 + 저하 경로 로그 문장 코드 리뷰, PR 본문에 명시).
- [x] `bash scripts/harness/verify.sh` 통과, `./gradlew :backend:widyu-api:test` 통과.
- [x] Swagger 변경 없음(응답 스키마 동일). PR 본문에 명시.

## 8. 영향 범위 / 마이그레이션

- 수정: `HeartRateService`, `HeartRateStatusResponse`(`of` 팩토리 추가), `FcmOutboxTransactions`(claim 표식 확인), `FamilyMembershipRepository`(`findEmergencyRecipients` JPQL projection), `SeniorProfileRepository`(`findSeniorMemberIds`), `application-fcm.yml`, `application-test.yml`. `FcmDeliveryProperties`·`HeartRateEmergencyNotificationService`는 변경 없음(문구는 이미 `NotificationCopy` 하나를 공유).
- 신규: `fcm/application/degraded/*` 7개, `member/repository/EmergencyRecipientRow`(projection record), 테스트 6개(`FcmDegradedPropertiesTest`·`DegradedEmergencyAlertServiceTest`·`DegradedEmergencyAlertConsumerTest`·`EmergencyRecipientSnapshotServiceTest`·`DegradedEmergencyStreamRedisTest` 신규, `HeartRateServiceTest`·`FcmOutboxTransactionsTest` 보강). `FcmOutboxIntegrationTest`·`FcmSchedulerOutboxIntegrationTest`는 `@MockBean DegradedAlertMarker`만 추가.
- DB·DDL·ERD 변경 없음. 운영 배포 시 `FCM_DEGRADED_*` 환경변수는 선택이며 기본값으로 동작한다. Compose `.env`에 추가하지 않아도 된다.
- Redis 사용량: 시니어당 스냅샷 1~2KB, 스트림 최대 10,000항목(수 MB), 표식 수십 B. 운영 `noeviction` 상한 256MB 대비 무시할 수준.
- 평시 추가 부하: 1초마다 빈 `XPENDING`·`XREADGROUP` 각 1회, 5분마다 스냅샷 쿼리 2회(수신자 projection·시니어 ID).

## 9. 미결정 사항 (Open Questions)

> 2026-10-16 사용자 결정: **구현은 아래 기본값대로 진행. 운영값은 사용자 승인 대기.**

- 운영 `fcm.degraded.enabled` 기본값을 `true`로 둘지. 구현은 `true` 기본으로 하되 운영 `.env`에서 끌 수 있다. 사용자 승인 전까지 PR 본문에 「운영 기본 ON, 환경변수로 OFF 가능」으로 명시한다.
- 스냅샷 갱신 주기 5분·TTL 24시간은 제안값이다. 알림 설정 변경이 최대 5분 늦게 반영되는 것을 사용자가 수용하는지.

### 구현 중 발견한 설계 확인 사항 (설계는 바꾸지 않고 기록, 2026-10-16)

- **수신자 범위가 정상 경로와 다르다.** 현재 정상 경로의 심박 최초 보호자 알림은 사건(`IncidentEscalation.enqueueInitialAlert`)을 거쳐 **방장 한 명**에게만 가고, `sensor.incident.self-check-first`가 켜져 있으면 시니어 본인확인 마감(최대 90초) 뒤에 간다. 저하 경로는 이 LLD대로 안전 알림을 받는 **모든 보호자에게 즉시** 보낸다. DB 장애 중에는 본인확인 화면을 띄울 사건을 열 수 없으므로 즉시 발송 자체는 불가피하지만, 수신자를 방장으로 좁힐지는 제품 결정이다.
- **Redis가 느려지면 fail-open이 늦다.** claim의 표식 조회는 outbox 행 잠금을 잡은 트랜잭션 안에서 돈다. Redis가 거부가 아니라 무응답이면 Lettuce 명령 타임아웃(`spring.data.redis.timeout` 미설정 시 60초)만큼 정상 경로 위급 발송이 늦어진다. 로컬에서 응답 없는 6379 포트로 Redis 게이트 테스트가 60초씩 걸린 것으로 같은 성질을 확인했다. Redis 타임아웃 설정은 이 PR 범위 밖이다.
- **`DataAccessException`은 DB 장애만이 아니다.** 같은 `measured_at` 동시 재전송의 UK 충돌(`DataIntegrityViolationException`)과 `saveMeasurement` 안 최신값 Redis 저장 실패(`RedisConnectionFailureException`)도 저하 경로로 간다. 결과는 위급 알림 1건으로 같지만(표식이 정상 경로 행을 취소) 분류는 부정확하다.

