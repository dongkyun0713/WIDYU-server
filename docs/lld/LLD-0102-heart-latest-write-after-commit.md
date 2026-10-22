# LLD-0102: Redis 최신값 쓰기 실패가 심박 저장·위급 알림을 되돌리지 않게 한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (코드 리뷰에서 확정된 결함, 사용자 구현·PR 지시, 2026-10-22) |
| Issue | #817 |
| 관련 ADR | [ADR-0040](../adr/ADR-0040-degraded-emergency-alert-without-database.md) |
| 작성자 | Claude Opus |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

MySQL이 정상이어도 Redis 하나가 실패하면 단건 심박과 그 위급 알림이 함께 사라졌다.

단건 저장 `HeartRatePersistenceService.saveMeasurement`(`@Transactional`)는 심박 이벤트를 저장하기 **전에** Redis 최신값(`HeartRateLatestResultWriter.saveIfNewer`, LLD-0090)을 썼다. 이 쓰기는 Redis 예외를 그대로 던진다. Redis가 OOM·명령 타임아웃으로 실패하면 트랜잭션이 롤백돼 `heart_rate_event`·`heart_rate_emergency`가 남지 않고 위급 이벤트도 나가지 않는다. Spring Data Redis 예외(`RedisConnectionFailureException`, `QueryTimeoutException`, `RedisSystemException`)는 모두 `DataAccessException` 하위 타입이라 `HeartRateService.processHeartRate`는 이를 DB 장애로 보고 저하 경로(LLD-0086)로 보낸다. 저하 경로도 Redis Stream에 적재하므로 함께 실패한다. 결과는 보호자 알림 0건이다. LLD-0086 9절 확인 사항은 이 경우 "결과는 위급 알림 1건으로 같다"고 적었지만, Redis가 실패한 상황에서는 저하 경로가 적재하지 못하므로 사실이 아니다.

배치 경로는 샘플을 먼저 저장해 유실은 없다. 다만 마지막 샘플의 최신값 갱신(`HeartRateBatchService.storeAndAssess` → `updateLatestResult`)이 던지면 `sensor_batch` 인덱스 행이 만들어지지 않고(샘플 먼저·인덱스 나중, ADR-0031 결정 4), 앱은 같은 배치를 계속 재전송한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 단건 최신값 쓰기를 저장 트랜잭션 밖, 커밋이 끝난 뒤로 옮긴다
- 최신값 쓰기(`updateLatestResult`) 실패는 WARN 로그와 메트릭만 남기고 예외를 내지 않는다. 단건·배치가 이 메서드 하나를 거친다
- 저하 경로 분기(`DataAccessException | TransactionException` catch) 안에 남은 Redis 호출 점검과 기록(6절)
- 단위·통합 테스트

### Out of scope
- LLD-0090 조건부 쓰기(Lua) 자체. "늦게 끝난 과거 측정이 최신값을 되돌리지 않는다"는 그대로다
- 저하 경로(LLD-0086·0087)의 적재·소비 동작
- 중복 재전송 응답의 최신값 조회 분류 변경(9절 미결정)
- 측정 시각 상·하한 검증(#819에서 정책 확인)

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. 단건 응답(`HeartRateStatusResponse`)은 지금처럼 이번 요청의 판정 결과다.

## 4. 데이터 모델

변경 없음. Redis 키 `heart_rate_result:{memberId}` 형식도 그대로다.

메트릭 1개를 추가한다: `heart.latest.write.failed`(counter, 태그 없음). 최신값 쓰기가 실패한 횟수다.

## 5. 처리 흐름

### 5.1 단건 (`HeartRateService.processHeartRate`, 트랜잭션 없음)

1. 회원·중복 확인(DB). DB 접근 예외면 저하 경로(기존)
2. AI 판정
3. `saveMeasurement` — 자기 트랜잭션에서 회원 조회, `heart_rate_event`·위급이면 `heart_rate_emergency` 저장, `HeartRateEmergencyEvent` 발행. **Redis를 부르지 않는다.** DB 접근 예외면 저하 경로(기존). 반환은 최신값으로 쓸 `HeartRateResult`다
4. 프록시 호출이 돌아온 시점에 커밋과 AFTER_COMMIT 리스너(사건 열기·보호자 최초 알림, `HeartRateEmergencyNotificationService`)가 끝나 있다
5. `updateLatestResult(memberId, status, heartRate, measuredAt)` — Redis 조건부 쓰기. 실패해도 예외를 내지 않는다
6. 이번 판정으로 응답한다

`TransactionSynchronization.afterCommit`이나 `@TransactionalEventListener` 대신 호출자가 커밋 뒤에 부르는 방식을 택했다. 배치 경로가 이미 이 구조(샘플 저장 트랜잭션들이 끝난 뒤 `updateLatestResult`)이고, AFTER_COMMIT 리스너는 `afterCompletion`에서 돌아 같은 단계의 동기화끼리 등록 순서에 기대야 한다. 호출자 방식은 Redis가 무응답(명령 타임아웃 3초, ADR-0040 결정 7)이어도 보호자 알림 리스너를 늦추지 않는다. `afterCommit`에 두면 리스너보다 먼저 돌아 그만큼 알림이 늦어진다.

`processHeartRate`에 `@Transactional`을 붙이면 3단계가 바깥 트랜잭션에 합류해 5단계가 커밋 앞으로 돌아간다. 이 메서드는 원래도 트랜잭션을 붙이지 않는다(LLD-0086 5.2).

### 5.2 배치 (`HeartRateBatchService.storeAndAssess`, 트랜잭션 없음)

흐름은 그대로다. 샘플마다 `saveBatchSample`(자기 트랜잭션) → 루프 뒤 마지막 저장 샘플로 `updateLatestResult`. 바뀐 것은 `updateLatestResult`가 예외를 내지 않는다는 점과, DB 작업이 없는데 붙어 있던 `@Transactional`을 뺀 점이다. 빈 트랜잭션도 시작할 때 DB 커넥션을 잡으므로 Redis만 쓰는 메서드에는 필요 없다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
| --- | --- |
| 최신값 Redis 쓰기 실패(단건·배치) | `heart.latest.write.failed` +1, WARN `memberId`·예외 클래스명. 심박·위급·알림·배치 인덱스는 그대로 저장된다. bpm·측정 시각은 로그에 넣지 않는다(#639) |
| 저장 트랜잭션 DB 접근 예외 | 기존대로 저하 경로. 저장하지 못한 측정은 최신값에도 올리지 않는다 |

### 6.1 저하 경로 분기 안의 Redis 호출 점검

`processHeartRate`의 두 `catch (DataAccessException | TransactionException)` 블록 안에서 일어나는 호출을 확인했다.

| 위치 | 호출 | Redis | 결과 |
| --- | --- | --- | --- |
| 첫 try | `validateMemberExists`, `existsByMemberIdAndMeasuredAt` | 아니오 | DB 장애만 분류된다 |
| 첫 try | 중복이면 `getHeartRateStatus` → `heartRateResultRepository.findByMemberId` | **예** | Redis 실패가 DB 장애로 분류된다. 9절 미결정 |
| 둘째 try | `saveMeasurement` 본문 | 이 변경 후 없음 | DB 장애만 분류된다 |
| 둘째 try | AFTER_COMMIT 리스너(사건·알림) | 리스너 안에서 가능 | Spring 6.1은 AFTER_COMMIT을 `afterCompletion`에서 실행하고 그 예외를 로그만 남기고 삼킨다(`TransactionSynchronizationUtils.invokeAfterCompletion`). 호출자로 전파되지 않아 분류에 영향이 없다 |
| catch 밖 | `updateLatestResult` | 예 | 예외를 내지 않고, 내더라도 try 밖이라 분류되지 않는다 |

`DataIntegrityViolationException` catch 안의 `getHeartRateStatus`는 같은 try의 다른 catch로 넘어가지 않아(자바 catch 절은 형제 catch를 다시 타지 않는다) 그대로 전파된다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: Redis 최신값 쓰기가 실패해도 단건 위급 심박의 `heart_rate_event`·`heart_rate_emergency`가 커밋되고, `HeartRateEmergencyEvent`가 커밋 뒤(AFTER_COMMIT) 발행된다
- [x] AC2: AC1에서 단건 요청은 저하 경로로 가지 않는다(`alertWithoutDatabase` 0회). 응답은 이번 판정값이다
- [x] AC3: `updateLatestResult`는 Redis 예외를 내지 않고 `heart.latest.write.failed`를 1 올린다. 배치 경로는 이 메서드를 거치므로 인덱스 행 저장까지 간다
- [x] AC4: 단건 최신값 쓰기는 `saveMeasurement`가 돌아온 뒤에 일어나며, 저장 트랜잭션 안에서는 Redis를 부르지 않는다
- [x] AC5: 저장 중 DB 접근 예외로 저하 경로에 가면 최신값을 쓰지 않는다
- [x] AC6: LLD-0090 조건부 쓰기 경로(`HeartRateLatestResultWriter`)와 기존 심박 테스트가 그대로 통과한다
- [x] AC7: 변경 Java 파일의 `validate-java-rules.sh`와 `:backend:widyu-api:compileJava`가 통과한다

### 검증 메모 (2026-10-22, Claude Opus / feature-817)

- AC1·AC2: `HeartRateLatestWriteFailureTest`(H2 `@DataJpaTest`, 실제 트랜잭션, AI 판정기·Redis 쓰기·저하 경로만 대역). **수정 전 코드에서 먼저 실행해 실패를 확인했다**: `heart_rate_event` 0건(기대 1건)이었고 로그에 `심박 DB 접근 실패, AI 판정만으로 응답: ... type=RedisConnectionFailureException, emergency=true`가 남았다. 수정 후 통과(이벤트 1·위급 1·AFTER_COMMIT 이벤트 1·실패 카운터 1·저하 경로 0회)
- AC3: `HeartRatePersistenceServiceTest` "최신값 Redis 쓰기가 실패하면 예외를 내지 않고 실패 건수만 센다"
- AC4: `HeartRatePersistenceServiceTest`(저장 중 `latestResultWriter` 무호출), `HeartRateServiceTest` "단건 저장 트랜잭션이 끝나면 저장한 측정으로 최신값을 갱신한다"(InOrder)
- AC5: `HeartRateServiceTest` 저하 경로 파라미터 테스트에 `updateLatestResult` 무호출 단언 추가
- AC6: `./gradlew :backend:widyu-api:test --tests 'com.widyu.heart.*'` 14클래스 102건 통과(실제 Redis가 필요한 10건은 `HEART_REDIS_PORT` 미지정으로 건너뜀). `SensorBatchServiceTest` 48건 통과
- 미실행: 실제 Redis 장애 주입, `HeartRateLatestResultOrderingRedisTest`(실제 Redis 필요, 생성자 인자만 맞춤), 실제 MySQL. Redis 장애 시 운영에서의 동작은 확인하지 않았다

## 8. 영향 범위 / 마이그레이션

DB·Redis 형식 변경 없음. 배포 순서 제약 없음.

- **동작 변화 1:** DB 저장이 실패한 단건 측정은 이제 최신값에 오르지 않는다. 전에는 Redis를 DB 저장보다 먼저 써서, 롤백된 측정이 최신값으로 남는 불일치가 있었다. DB 장애 중에는 가족 접근 확인(DB)이 먼저 실패해 최신값을 읽는 화면도 열리지 않으므로 사용자에게 보이는 차이는 없다고 본다
- **동작 변화 2:** Redis가 실패하면 최신값은 직전 값에 머문다(최대 TTL 24시간). 다음 측정의 쓰기가 성공하면 따라잡는다. 홈 화면 등은 그동안 낡은 최신값을 보인다. 이력(`heart_rate_event`)은 빠짐없이 남는다
- LLD-0090 6절 "Redis 오류는 지금과 같은 방식으로 전파·처리한다"는 이 LLD로 바뀐다. 쓰기 빈(`HeartRateLatestResultWriter`)은 지금도 예외를 전파하고, 삼키는 곳은 호출 경로(`updateLatestResult`) 하나다
- LLD-0086 9절 "`saveMeasurement` 안 최신값 Redis 저장 실패도 저하 경로로 간다. 결과는 위급 알림 1건으로 같다"는 이 변경으로 해당 경로가 없어졌다. 그 문서는 고치지 않고 이 LLD를 정정 기록으로 둔다

신규 ADR N/A: 최신값을 이력의 조회용 사본으로 다루는 기존 결정(LLD-0090) 안에서 트랜잭션 경계만 옮긴다.

## 9. 미결정 사항 (Open Questions)

- [ ] 중복 재전송(같은 `measured_at`이 이미 저장됨)의 응답을 만드는 최신값 조회(`getHeartRateStatus`, Redis)가 저하 경로 분기 안에 있다. Redis가 실패하면 이 재전송은 DB 장애로 분류돼 AI를 한 번 더 부르고, 위급이면 저하 경로 적재를 시도한 뒤(Redis가 죽어 있으면 실패) AI 판정값으로 200을 준다. 심박은 이미 저장돼 있어 유실은 없다. 조회를 분기 밖으로 빼면 같은 상황에서 오류 응답이 되고 앱이 재전송한다. 어느 쪽을 택할지는 클라이언트 재전송 정책과 함께 정해야 해 이번에 바꾸지 않았다

## 10. 참고

- [LLD-0090](LLD-0090-heart-latest-result-ordering.md) 최신값 조건부 쓰기, [LLD-0086](LLD-0086-degraded-emergency-alert-path.md) 저하 경로, [LLD-0047](LLD-0047-heart-rate-batch-v2.md) 배치 v2, [LLD-0023](LLD-0023-heart-rate-single-measurement-transport.md) 단건 전송
- Spring Framework 6.1 `TransactionalApplicationListenerSynchronization`: AFTER_COMMIT은 `afterCompletion`에서 처리된다(spring-tx 6.1.14 바이트코드로 확인)
