# LLD-0087: 저하 경로 알림의 복구 후 보정·스냅샷 즉시 무효화·장애 주입 측정

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #772 |
| 관련 ADR | [ADR-0040](../adr/ADR-0040-degraded-emergency-alert-without-database.md) |
| 작성자 | Claude (계획), 구현 Opus |
| 작성일 | 2026-10-16 |

## 1. 목적 / 배경

LLD-0086의 저하 경로는 DB 없이 발송만 한다. 그래서 ① 보낸 알림이 `fcm_notification`에 없어 보호자 앱 목록에 보이지 않고, ② 보호자가 가족에서 빠져도 스냅샷이 최대 갱신 주기(5분)만큼 남으며, ③ 「DB 장애 중에도 간다」는 주장에 측정 증거가 없다. 이 세 가지를 채운다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api. 엔티티·DDL 변경 없음.
- `fcm:degraded:reconcile` 목록을 주기적으로 DB 이력으로 옮기는 `DegradedAlertReconciler`
- 가족 제외(`GuardianMyPageService.deleteGuardianFromFamily`)·탈퇴(`MemberWithdrawService`의 가족 관계 삭제) 커밋 후 `EmergencyRecipientSnapshotService.refreshFamily(familyId)` 호출
- 장애 주입 측정 테스트(`DegradedAlertOutageMeasurementTest`, 로컬 Redis 필요, 환경변수 게이트)
- PR #773 리뷰에서 나온 보완 3건(§5.3): Redis 명령 타임아웃, UK 충돌 제외, 저하 모드 수신자 범위를 ADR-0040에 명시

### Out of scope
- 장애 중 측정값(`heart_rate_event`·`heart_rate_emergency`) 재생
- 알림 설정 변경·토큰 교체 시 즉시 갱신(주기 갱신으로 충분, ADR-0040 트레이드오프)
- 운영 RDS 장애 재현

## 3. 인터페이스 / API

새 HTTP API 없음.

설정 추가(`application-fcm.yml`, `local | dev | prod`; test는 `application-test.yml`):

```yaml
fcm:
  degraded:
    reconcile-ms: ${FCM_DEGRADED_RECONCILE_MS:10000}   # @Scheduled 문자열 속성
    reconcile-batch: ${FCM_DEGRADED_RECONCILE_BATCH:100}
```

`FcmDegradedProperties`에 `reconcileBatch`(양수) 추가.

메트릭: `fcm.degraded.reconciled`(이력 저장 수), `fcm.degraded.reconcile_dropped{reason=token_missing|malformed|recipient_missing|rejected}`(뒤 둘은 구현 중 추가, §8), `fcm.degraded.reconcile_deferred`(DB 예외로 되돌린 수).

## 4. 데이터 모델

DB 변경 없음. 보정 기록(JSON, LLD-0086 §5.4에서 적재)은 `guardianId, tokenId, seniorId, title, body, image, category, sentAtMs, streamId`다. `streamId`가 멱등 키다.

## 5. 처리 흐름

### 5.1 보정 (`DegradedAlertReconciler.reconcile`)

1. `@Scheduled(fixedDelayString = "${fcm.degraded.reconcile-ms:10000}")`, 스케줄 메서드 자체는 `@Transactional(propagation = NOT_SUPPORTED)`.
2. `LPOP fcm:degraded:reconcile COUNT reconcileBatch`(Spring Data `ListOperations.leftPop(key, count)`). 비어 있으면 종료. 평시 비용은 LPOP 1회/10초.
3. 항목마다 별도 빈의 `@Transactional(REQUIRES_NEW)` 메서드 `persist(record)`:
   - 멱등: `fcm_notification`에 같은 `streamId`가 이미 있으면 건너뛴다. 컬럼을 추가하지 않기 위해 **`FcmNotification`의 기존 컬럼으로 멱등을 판단한다**: `memberFcmToken.id = tokenId AND title = title AND createdAt`이 아니라, `decisionId` 컬럼(nullable, 단건 경로는 null)에 `"degraded:" + streamId`를 넣어 `existsByDecisionId`로 판단한다. `decisionId`는 자유 문자열이며 `DecisionRecordRepository.markDeliveredIfFirst`는 outbox 경로에서만 호출되므로 충돌하지 않는다. (구현 전 `FcmNotification.decisionId` 길이 제한을 확인하고, 스트림 id `1697000000000-0` 형식 + 접두어가 들어가는지 검증한다. 안 들어가면 미결정으로 올린다.)
   - `MemberFcmTokenRepository.findById(tokenId)` 없음 → `reconcile_dropped{token_missing}`. 토큰의 현재 소유자가 `guardianId`와 다르면 **원수신자(`guardianId`) 기준**으로 `recipientMember`를 넣는다(ADR-0028: 수신자는 고정).
   - `FcmNotification.builder().recipientMember(guardian).memberFcmToken(token).title.body.image.fcmCategory(HEART_MESSAGE).decisionId("degraded:" + streamId).isRead(false)` 저장. `createdAt`은 BaseTimeEntity라 보정 시각이 된다. `sentAtMs`는 별도 컬럼이 없으므로 저장하지 않는다(한계로 기록).
4. `DataAccessException | TransactionException`이면 **그 항목과 아직 처리하지 않은 나머지를 `RPUSH`로 되돌리고** 이번 주기를 끝낸다(`reconcile_deferred`). 되돌리기 자체가 Redis 예외면 warn 로그(건수만)로 남긴다 — 이 경우 유실 가능성을 ADR 트레이드오프에 추가한다.
5. JSON 파싱 실패 → `reconcile_dropped{malformed}` + warn(길이만).

### 5.2 스냅샷 즉시 갱신 훅

- `GuardianMyPageService.deleteGuardianFromFamily(targetMemberId, familyId)`: 삭제 직후 `TransactionSynchronizationManager.registerSynchronization(afterCommit → snapshotService.refreshFamily(familyId))`.
- `MemberWithdrawService.handleFamilyMembershipWithdrawal(guardianId)`: `familyId`를 삭제 전에 잡아 두고 같은 방식으로 afterCommit 훅.
- `refreshFamily`는 읽기 전용 새 트랜잭션(REQUIRES_NEW)에서 가족 하나의 시니어들만 다시 만든다. 예외는 warn 로그로 끝내고 호출자에게 전파하지 않는다.

### 5.3 PR #773 리뷰 보완

1. **Redis 명령 타임아웃.** `application-redis.yml`의 `spring.data.redis`에 `timeout: ${REDIS_COMMAND_TIMEOUT:3s}`를 추가한다. outbox `claim`의 표식 조회가 행 잠금 안에서 도는데 Redis가 무응답이면 Lettuce 기본 60초를 기다린다. 3초면 fail-open이 실제로 「빨리 포기」가 된다. 앱 전체 Redis 명령에 적용되므로 PR 본문에 영향 범위(refresh token·위치·인증 한도·스트림)를 적는다. 기존 Redis 게이트 테스트가 이 값에 영향받지 않는지 확인한다.
2. **UK 충돌은 저하 경로가 아니다.** `HeartRateService.processHeartRate`의 두 번째 catch에서 `DataIntegrityViolationException`(같은 `measured_at` 동시 재전송)은 저하 경로로 보내지 않고 그대로 던진다(기존 동작 유지). 첫 번째 catch(회원 확인·중복 확인)는 그대로 둔다. `HeartRateServiceTest`에 케이스 추가. (LLD-0089에서 UK 충돌은 재확인 후 현재 상태 응답으로 대체)
3. **저하 모드 수신자 범위를 ADR에 적는다.** ADR-0040 「결정」에 한 항목을 추가한다: 「저하 모드에서는 사건(본인확인·2단계)을 열 수 없으므로 안전 알림을 받는 모든 보호자에게 즉시 보낸다. 정상 경로의 방장 1명·본인확인 우선(ADR-0038·0039)과 다르며, 장애 중에는 늦은 알림보다 넓은 알림을 택한다.」 트레이드오프에도 한 줄 추가. 사용자가 방장으로 좁히기를 원하면 스냅샷 JPQL에 방장 조건만 더하면 되므로 그 사실도 적는다.

### 5.4 장애 주입 측정 테스트 (`DegradedAlertOutageMeasurementTest`)

- 위치 `backend/widyu-api/src/test/java/com/widyu/fcm/application/degraded/`. `@SpringBootTest` + `@ActiveProfiles("test")`(H2). `@EnabledIfEnvironmentVariable(named = "DEGRADED_REDIS", matches = ".+")` + `assumeTrue(localhost:6379 reachable)`. `@Tag("degraded-redis")`.
- DataSource 차단: 테스트 설정에서 기본 DataSource를 `ToggleableDataSource extends DelegatingDataSource`로 감싼다. `down=true`면 `getConnection()`이 `SQLTransientConnectionException("simulated outage")`를 던진다. `@Primary` 또는 `BeanPostProcessor`로 교체.
- `FcmTransport`는 `@MockBean`으로 두고 `send(token, dto)`를 호출 시각과 함께 기록, `Result.delivered()` 반환. `HeartRateAnomalyDetector`도 `@MockBean`으로 두고 항상 위급 판정을 돌려준다(AI 컨테이너 불필요).
- 시나리오:
  1. given: 시니어 N명(기본 20, 환경변수 `DEGRADED_N`), 각 보호자 2명·토큰 1개씩 저장 → `snapshotService.refreshAll()`로 스냅샷 생성.
  2. when: `down=true` → 시니어마다 `processHeartRate` 1회 호출(위급) → 소비자 `poll()`을 직접 호출(스케줄 대기 없이) → 전송 기록 수집.
  3. then: 전송 시도 = N×2, `XPENDING` 0, 보정 목록 길이 N×2. `processHeartRate` 호출부터 `transport.send`까지 지연의 p50·p95·max를 기록(출력은 `MEASUREMENT ...` 접두 println).
  4. when: `down=false` → `reconciler.reconcile()` 반복 호출.
  5. then: `fcm_notification` N×2건, `decisionId` 접두 `degraded:`, 같은 테스트에서 한 번 더 `reconcile()`해도 건수 불변(멱등). `fcm_outbox` 0건(업무 트랜잭션이 실패했으므로). 추가로 표식 검증: `down=false` 상태에서 같은 시니어의 위급 샘플을 바로 다시 보내면 outbox 행이 생기지만 `claim`에서 취소돼 `transport.send` 호출 수가 늘지 않는다.
  6. 시나리오 B(동일 클래스, 별도 테스트): `down=true`에서 같은 시니어로 10초 안에 샘플 5개 → 전송 시도는 보호자 수만큼만(표식).
- 실행 방법과 결과는 PR 본문에 `MEASUREMENT` 줄을 그대로 붙인다. 측정 기록 파일(`apiDocs/`)은 Claude가 루트 체크아웃에서 작성한다(worktree에 apiDocs 없음).

## 6. 예외 / 에러 처리

새 에러 코드 없음. 5.1 4~5항 참조.

## 7. 인수조건 (Acceptance Criteria)

- [x] `DegradedAlertReconcilerTest`(ListOperations·리포지토리 mock): 기록 1건 → `FcmNotification` 1건 저장, `decisionId`가 `degraded:{streamId}`, 원수신자 기준 `recipientMember`.
- [x] 같은 `streamId`를 두 번 처리해도 저장은 1건.
- [x] 토큰 없음 → 저장 없이 dropped, 목록에 되돌리지 않음.
- [x] 두 번째 항목에서 `DataAccessResourceFailureException` → 첫 항목은 저장, 두 번째·세 번째는 `RPUSH`로 되돌아가고 deferred 2.
- [x] `GuardianMyPageServiceTest`·`MemberWithdrawServiceTest`: 가족 제외·탈퇴 커밋 후 `refreshFamily(familyId)`가 1회 호출된다(afterCommit 동기화를 `TransactionSynchronizationManager`로 실제 돌리거나 기존 테스트 패턴 `HeartRateEmergencyAfterCommitTest` 참고).
- [x] `EmergencyRecipientSnapshotServiceTest`에 `refreshFamily`: 가족 하나의 시니어만 갱신되고 다른 가족 키는 건드리지 않는다.
- [x] `DegradedAlertOutageMeasurementTest`: 5.4의 then 전부. 로컬 Redis + `DEGRADED_REDIS=1`로 실제 실행한 출력(`MEASUREMENT` 줄)을 PR 본문에 첨부.
- [x] `FcmDegradedPropertiesTest`에 `reconcileBatch` 양수 검증 추가.
- [x] `HeartRateServiceTest`: `saveMeasurement`가 `DataIntegrityViolationException`을 던지면 저하 경로 호출 없이 그대로 전파된다.
- [x] `application-redis.yml`에 `spring.data.redis.timeout` 3초 기본이 들어가고, 기존 Redis 게이트 테스트(`JwtTokenProviderRedisTest`·`WsTokenServiceRedisTest`·`DegradedEmergencyStreamRedisTest`)가 로컬 Redis에서 통과한다.
- [x] ADR-0040 「결정」에 저하 모드 수신자 범위 항목이 추가된다.
- [x] `bash scripts/harness/verify.sh` 통과, `./gradlew :backend:widyu-api:test` 통과.
- [x] Swagger 변경 없음.

## 8. 영향 범위 / 마이그레이션

- 수정: `GuardianMyPageService`, `MemberWithdrawService`, `FcmDegradedProperties`, `application-fcm.yml`, `application-redis.yml`(timeout), `application-test.yml`, `HeartRateService`(UK 충돌 제외), `EmergencyRecipientSnapshotService`(refreshFamily 구현 보강 시), `docs/adr/ADR-0040`(수신자 범위 결정 추가).
- 신규: `DegradedAlertReconciler`, `DegradedAlertReconcileTransactions`(REQUIRES_NEW 빈), 테스트 3~4개, `ToggleableDataSource` 테스트 지원 클래스.
- `FcmNotification.decisionId`를 저하 경로 멱등 키로 재사용한다. 단건 경로 알림은 원래 null이라 기존 조회(`markDeliveredIfFirst`)와 충돌하지 않는다. DDL 없음.
- 평시 추가 부하: 10초마다 `LPOP` 1회.

### 구현 중 코드에 맞춘 점 (2026-10-16)

- **훅 위치.** 커밋 뒤 동기화 등록은 호출자마다 쓰지 않고 `EmergencyRecipientSnapshotService.refreshFamilyAfterCommit(familyId)`에 둔다. 읽기 전용 REQUIRES_NEW는 `refreshFamily`에 애너테이션으로 걸지 않고 afterCommit 콜백 안에서 `TransactionTemplate`으로 연다(같은 빈 안 호출이라 프록시가 안 걸리고, 트랜잭션 시작 예외가 콜백의 catch에 닿아야 이미 커밋된 요청을 실패시키지 않는다). `refreshFamily`는 `enabled=false`면 돌지 않는다.
- **마지막 방장 탈퇴.** 이 경우 가족이 해체되고 시니어의 가족이 null이 되어 `refreshFamily(familyId)`로는 그 시니어를 찾지 못한다. 해체 전에 `findSeniorMemberIds(familyId)`로 시니어를 잡아 두고 커밋 뒤 키를 지운다(`evictAfterCommit`). 받을 보호자가 없는 시니어의 키 삭제는 갱신 결과와 같다.
- **이력 행 필드.** 앱 알림센터가 정상 경로 행과 같게 보이도록 `type=HEART_RATE_EMERGENCY`, `eventId=streamId`, `seniorId`, `deepLink`, `expiresAt`(보존 기간), `retentionPolicyVersion`, `pushEligible=true`도 채운다. 이를 위해 `FcmOutboxService.RETENTION_POLICY_VERSION`을 public으로 바꿨다.
- **버림 사유 추가.** 보호자 회원 행이 없으면 `reconcile_dropped{recipient_missing}`. 저장이 `DataIntegrityViolationException`이면 되돌리지 않고 `reconcile_dropped{rejected}`로 버린다. 되돌리면 같은 기록이 매 주기 실패하며 뒤 기록을 막는다.
- **DB 장애가 아닌 예외도 한 건만 버린다(PR #774 리뷰 반영).** 저장 중 DB·트랜잭션 예외가 아닌 런타임 예외(예: 배포 사이 이름이 바뀐 `category`의 `FcmCategory.valueOf` 실패)는 `reconcile_dropped{rejected}`로 그 한 건만 버리고 루프를 계속한다. 잡지 않으면 예외가 스케줄 메서드 밖으로 나가 이미 `LPOP`으로 꺼낸 나머지 기록(최대 `reconcile-batch - 1`건)이 되돌아가지 않고 사라진다.
- **스냅샷 쓰기 직렬화(PR #774 리뷰 반영).** 주기 갱신(`refreshAll`)이 보호자 제외 전 목록을 읽고 Redis에 쓰기 전에 커밋 후 갱신이 먼저 쓰면, 주기 갱신이 낡은 목록으로 덮어 제외한 보호자가 다음 주기(최대 5분)까지 스냅샷에 남는다. `EmergencyRecipientSnapshotService`의 `ReentrantLock` 하나로 「DB 조회 → Redis 쓰기·삭제」를 직렬화한다. 커밋 후 콜백은 읽기 트랜잭션을 열기 전에 락을 잡아 DB 커넥션을 쥔 채 기다리지 않는다. JVM 안 락이라 앱 인스턴스가 한 대일 때만 보장된다(운영 EC2 1대). 여러 대가 되면 키별 버전 비교(Lua)로 바꾼다.
- **`enabled=false`여도 보정은 돈다.** 끄기 전에 쌓인 기록을 옮기기 위해서다. 비용은 주기마다 빈 LPOP 1회다.
- **측정 방법.** 시니어마다 `processHeartRate` 직후 `poll()`을 한 번 부른다(전부 받고 한 번에 poll하면 다른 시니어 처리 시간이 지연에 섞인다). 지연은 마이크로초로 출력하고 스케줄 대기(`poll-ms` 1초)는 포함하지 않는다. 멱등은 보정한 기록을 목록에 그대로 다시 넣고 한 번 더 보정해 확인한다. 측정 컨텍스트는 다른 통합 테스트의 H2를 건드리지 않게 별도 H2 URL을 쓰고, Redis 포트는 `DEGRADED_REDIS_PORT`(기본 6379)로 바꾼다. Redis 확인은 앱 Redis 연결의 PING이다.
- **기존 Redis 게이트 테스트와 타임아웃.** `JwtTokenProviderRedisTest`·`WsTokenServiceRedisTest`·`DegradedEmergencyStreamRedisTest`는 스프링 설정이 아니라 직접 만든 `LettuceConnectionFactory`를 써서 `spring.data.redis.timeout`의 영향을 받지 않는다. 2026-10-16 로컬 6379 Redis가 응답하지 않아 포트만 임시로 6380으로 바꿔 실행해 8건 통과했고 변경은 되돌렸다.

## 9. 미결정 사항 (Open Questions)

> 2026-10-16 사용자 결정: **구현은 기본값(N=20, 수신자 전 보호자 유지)으로 진행.**

- 측정 시니어 수 N은 20으로 구현했다. 로컬 실행 시간은 측정 클래스 전체 약 1초다.
- 저하 모드 수신자를 전 보호자로 둘지 방장으로 좁힐지(§5.3-3). 구현은 전 보호자이고 ADR-0040 결정 6에 적었다. **사용자 결정 대기.**
- (확인 완료) `FcmNotification.decisionId`는 `length = 40`이라 `degraded:` + 스트림 id가 들어간다. 측정에서 최대 24자였다. 이 컬럼은 연구 철회 시 판정 id로 행을 찾는 연결키인데 저하 경로 값은 접두어가 달라 충돌하지 않는다.
- (후속 후보) 방장이 **시니어**를 가족에서 지우는 경우(`deleteSeniorFromFamily`)는 이 LLD 범위 밖이라 훅이 없다. 그 시니어의 스냅샷은 다음 주기(최대 5분)까지 남아, 그 사이 DB 장애가 겹치면 옛 가족 보호자에게 그 시니어의 위급이 갈 수 있다.
