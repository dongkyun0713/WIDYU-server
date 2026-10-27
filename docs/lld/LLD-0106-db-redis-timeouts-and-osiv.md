# LLD-0106: DB·Redis 응답 대기 상한과 OSIV를 설정으로 명시한다

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #818 |
| 관련 ADR | [ADR-0040](../adr/ADR-0040-degraded-emergency-alert-without-database.md), [ADR-0023](../adr/ADR-0023-production-single-container-deployment.md) |
| 작성자 | Claude |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

RDS가 응답하지 않으면 DB를 쓰는 스레드가 커넥션 하나를 얻으려고 **30초**를 기다린다. `application-datasource.yml`에 Hikari 설정이 없어 기본 `connectionTimeout` 30초가 적용되고, JDBC URL에 `connectTimeout`·`socketTimeout`이 없어 이미 실행 중인 쿼리는 OS TCP 재전송이 끝날 때까지 끊기지 않는다.

이 대기는 DB 없이 위급 알림을 보내는 저하 경로(ADR-0040)를 무력하게 만든다. 심박 1건은 심박 실행기 스레드에서 아래 DB 접근을 차례로 기다린다.

1. `HeartRateService.processHeartRate`의 회원 확인 — 실패하면 저하 경로로 간다.
2. 응답을 `/topic/heart-rate/{seniorId}`로 보낼 때 `FamilyTopicOutboundInterceptor.preSend`가 구독자마다 `verifyActiveFamilyAccess`를 부른다. 브로커 채널에 실행기가 없어 이 호출도 같은 발신 스레드에서 돈다.

보호자 k명이 구독 중이면 1건에 `(1 + k) × 30초`다. 심박 실행기 8스레드의 처리량은 k=0에서 초당 0.27건, k=2에서 초당 0.09건이다. 시니어 한 명이 초당 1건을 보내도 대기열 200이 수 분 안에 차고 `TEMPORARILY_BUSY` 거절이 시작된다. LLD-0087 5.4 측정은 `getConnection()`이 즉시 예외를 던지는 DataSource를 써서 이 대기를 재지 못했다.

같은 묶음의 설정 결함 두 가지를 함께 고친다.

- `RedisConfig`가 Lettuce `commandTimeout`을 `Duration.ofSeconds(1)`로 하드코딩한다. 커스텀 `RedisConnectionFactory` 빈이 있어 Boot의 Lettuce 자동 구성이 빠지므로 `application-redis.yml`의 `spring.data.redis.timeout`(`REDIS_COMMAND_TIMEOUT`, 기본 3초, ADR-0040 결정 7)은 어디에도 적용되지 않는다. 문서는 3초, 실제는 1초다.
- `spring.jpa.open-in-view`를 dev·local은 `false`로 두지만 prod와 test에는 없다. 운영만 Spring 기본값 `true`라 HTTP 요청이 응답을 다 쓸 때까지 영속성 컨텍스트와 DB 커넥션을 잡을 수 있고, 테스트는 dev와 다른 조건(OSIV 켜짐)에서 돈다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (설정 파일, `RedisConfig`, `RedisProperties`)
- Hikari `connection-timeout`·`validation-timeout`·`maximum-pool-size`, JDBC URL `connectTimeout`·`socketTimeout`을 환경변수 기본값으로 명시
- `RedisConfig`가 `spring.data.redis.timeout`을 `commandTimeout`에 적용(값이 없으면 3초)
- `spring.jpa.open-in-view: false`를 공통 `application.yml`로 옮기고 dev·local의 중복 제거
- 설정 파일 바인딩 테스트

### Out of scope
- DB 장애를 감지하면 DB 호출을 아예 건너뛰는 빠른 실패(서킷 브레이커, 인가 캐시) — 9절
- `@Scheduled` 공유 스케줄러 크기(`SchedulerConfig`, CPU 수)와 저하 경로 소비자 전용 실행기 — 별도 이슈
- JWT 임시 토큰 만료 단위(ms)와 access·refresh 단위(초) 불일치 — 별도 이슈
- `docker-compose*.yml`에 새 환경변수 전달 추가. 기존 `REDIS_COMMAND_TIMEOUT`·`WS_*`·`ASYNC_*`처럼 운영은 yml 기본값을 쓴다. 운영에서 바꾸려면 compose에 전달을 추가해야 한다.

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. 설정만 추가한다.

`application-datasource.yml`(`local | dev | prod`가 포함하는 `datasource` 프로파일):

| 설정 | 환경변수 | 기본값 | 의미 |
| --- | --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | `DB_POOL_CONNECTION_TIMEOUT_MS` | 3000 | 풀에서 커넥션을 얻을 때까지 기다리는 최대 시간 |
| `spring.datasource.hikari.validation-timeout` | `DB_POOL_VALIDATION_TIMEOUT_MS` | 1000 | 빌려주기 전 유휴 커넥션 생존 확인의 최대 시간 |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_MAX_SIZE` | 10 | 풀 최대 커넥션 수(Hikari 기본값과 같음) |
| URL `connectTimeout` | `DB_CONNECT_TIMEOUT_MS` | 3000 | 새 커넥션의 TCP 연결 대기 |
| URL `socketTimeout` | `DB_SOCKET_TIMEOUT_MS` | 60000 | 실행 중 쿼리가 응답 패킷을 기다리는 최대 시간 |

Hikari 시간 설정은 `long` 밀리초 setter라 `3s` 같은 Duration 문자열을 받지 않는다. 모두 밀리초 정수로 둔다.

`application-redis.yml`의 `spring.data.redis.timeout: ${REDIS_COMMAND_TIMEOUT:3s}`는 그대로 두고, `com.widyu.global.properties.RedisProperties`에 `Duration timeout`(`@DefaultValue("3s")`)을 추가한다.

`application.yml`(모든 프로파일):

```yaml
spring:
  jpa:
    open-in-view: false
```

## 4. 데이터 모델

변경 없음.

## 5. 처리 흐름

### 5.1 값의 근거

- **커넥션 대기 3초.** 정상 쿼리는 수 ms라 풀 10개가 3초 동안 모두 묶이는 것은 이미 과부하다. 그때 30초를 더 기다려 성공시키기보다 실패를 빨리 돌려 저하 경로·재시도로 넘기는 편이 낫다. 심박 1건의 최악 대기는 `(1 + k) × 3초`로 줄어 8스레드 처리량이 k=0에서 초당 2.7건, k=2에서 초당 0.9건이 된다. Redis 명령 상한(3초)과 같은 크기라 장애 시 대기 예산을 한 숫자로 설명할 수 있다.
- **생존 확인 1초.** Hikari는 500ms 넘게 쉰 커넥션을 빌려주기 전에 `isValid(validationTimeout)`로 확인한다. 기본 5초는 커넥션 대기(3초)보다 길어 확인 한 번이 대기 예산 전부를 쓸 수 있다. 1초면 죽은 커넥션을 버리고 다음 커넥션을 볼 여유가 남는다. Hikari 하한은 250ms다.
- **풀 10 유지.** LLD-0092가 `@Async` 실행기 상한(기본·영상 각 2)을 풀 10 기준으로 잡았고, 앱 인스턴스는 EC2 한 대(ADR-0023)라 RDS `max_connections`에 비해 작다. 늘릴 근거(커넥션 대기 지표)가 아직 없으므로 값만 드러내고 바꾸지 않는다.
- **TCP 연결 3초.** RDS는 같은 리전이라 정상 연결은 수 ms다. 설정이 없으면 Connector/J는 OS 기본 연결 대기를 쓴다.
- **소켓 대기 60초.** 가장 긴 정상 단일 쿼리보다 길어야 한다. 긴 작업은 회차 내보내기(`RunExportWorker`, 회차 자료를 조회)와 월 1회 약품 동기화(`MedicineSyncScheduler`)인데, 둘 다 쿼리 여러 개로 나뉘고 단일 쿼리가 수 초를 넘는 경로는 코드에서 찾지 못했다. 큰 결과 집합은 행을 읽는 동안 패킷이 계속 오므로 이 상한에 걸리지 않는다. 60초는 그 여유를 두면서 장애 시작 시점에 실행 중이던 쿼리가 무기한 커넥션을 쥐는 것을 막는다. 운영 쿼리 지연 분포로 확인하지 않았다(9절).
- **Redis 3초.** ADR-0040 결정 7의 값을 실제로 적용한다. 지금 실제 값은 하드코딩된 1초라 이 변경은 Redis 무응답 시 대기를 1초에서 3초로 늘린다. 저하 경로·outbox 표식 조회 모두 fail-open이라 늘어난 2초는 포기까지의 시간이다. 앱에 블로킹 Redis 명령(`BLPOP`, `XREAD BLOCK`)은 없다.

### 5.2 장애 중 동작

1. RDS가 무응답이 되면 이미 쿼리 중인 커넥션은 최대 `socketTimeout`(60초) 뒤 예외로 풀에 반환·폐기된다.
2. 이후 `getConnection()`은 `connection-timeout`(3초) 안에 `SQLTransientConnectionException`을 던진다. Spring이 `DataAccessException`·`TransactionException`으로 바꾸므로 `HeartRateService`의 기존 catch가 저하 경로로 보낸다.
3. 아웃바운드 인가 조회도 3초 뒤 실패하고, 인터셉터는 기존대로 메시지를 버린다(fail-closed).

### 5.3 OSIV 끄기의 영향과 점검 방법

OSIV가 꺼지면 영속성 컨텍스트는 서비스 트랜잭션 안에서만 산다. 트랜잭션 밖(컨트롤러, 응답 직렬화)에서 지연 로딩하면 `LazyInitializationException`이 난다. 점검 근거는 다음과 같다.

- dev·local은 멀티 모듈 전환(ef5078ee, 2025-10-19)부터 OSIV를 끄고 운영과 같은 코드를 돌렸다. 운영과 dev의 차이는 이 설정 하나다.
- 컨트롤러는 DTO를 반환한다(코드 규칙). 컨트롤러가 엔티티를 import하는 곳은 `MedicineScheduleController` 한 곳이며 `Member`의 기본 필드(`getType`, `getId`)만 읽는다.
- WebSocket·스케줄러·`@Async` 경로는 원래 OSIV 대상이 아니라 영향이 없다.
- 테스트는 지금까지 OSIV가 켜진 채 돌았다. 공통 `application.yml`에 두면 test 프로파일도 꺼진다. `application-test.yml`에 따로 쓰지 않는 이유는 테스트가 공통 설정을 그대로 쓰게 하기 위해서다. 다만 실제 JPA와 HTTP 계층을 함께 띄우는 테스트는 거의 없다(`@SpringBootTest` + 웹 환경은 WebSocket 테스트 2개). 그래서 CI 통과는 컨트롤러 지연 로딩이 없다는 증거가 되지 못하고, 주된 근거는 위의 dev 운용 이력과 정적 점검이다.
- LLD-0064 8절은 운영 OSIV 켜짐을 전제로 방장 변경·정렬 명령에서 `EntityManager.clear()`를 둔다. OSIV가 꺼지면 선행 권한 조회가 다른 영속성 컨텍스트에서 끝나므로 `clear()`는 비울 것이 줄 뿐 동작은 같다.

## 6. 예외 / 에러 처리

새 에러 코드 없음. 커넥션 대기 초과는 지금과 같은 예외(`CannotCreateTransactionException` 등)가 30초 대신 3초 뒤에 난다. 정상 부하에서 풀이 3초 넘게 고갈되면 지금은 늦게 성공하던 요청이 실패한다(트레이드오프).

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: `application-datasource.yml`을 바인딩하면 Hikari `connectionTimeout`=3000, `validationTimeout`=1000, `maximumPoolSize`=10이고, 환경변수로 바꾼 값이 반영된다.
- [x] AC2: 같은 파일의 JDBC URL에 `connectTimeout=3000`·`socketTimeout=60000`이 들어간다.
- [x] AC3: `application-redis.yml`을 바인딩해 `RedisConfig`로 연결 팩토리를 만들면 `commandTimeout`이 3초이고, `REDIS_COMMAND_TIMEOUT=5s`면 5초다. `timeout`이 없는 바인딩은 3초다.
- [x] AC4: 공통 `application.yml`과 local·dev·prod·test 프로파일 파일을 합쳐도 `spring.jpa.open-in-view`가 false다.
- [x] AC5: Hikari 커넥션 대기 상한이 실제로 줄어든다. 연결은 받지만 응답하지 않는 로컬 TCP 서버(무응답 DB 대역)에 대한 `getConnection()` 대기를 기본 설정과 새 설정으로 비교한다(9절 측정).
- [x] AC6: 변경한 Java 파일이 `validate-java-rules.sh`를 통과하고 `:backend:widyu-api:compileJava`가 통과한다.
- [ ] AC7: 전체 `./gradlew :backend:widyu-api:test`가 OSIV 꺼진 상태로 통과한다(PR CI에서 확인). 이 통과는 기존 테스트가 깨지지 않았다는 뜻이며 HTTP 경로의 지연 로딩 부재를 증명하지 않는다(5.3).
- [x] Swagger 변경 없음.

## 8. 영향 범위 / 마이그레이션

- 수정: `application.yml`, `application-datasource.yml`, `application-dev.yml`, `application-local.yml`, `RedisConfig`, `RedisProperties`, 테스트 지원 `HeartRateRedisFixture`(레코드 생성자 인자 추가).
- 신규 테스트: `ConnectionSettingsBindingTest`(설정 파일 바인딩 → Hikari·JDBC URL·Lettuce `commandTimeout`·OSIV).
- DB 마이그레이션 없음. 배포 시 환경변수 추가 불필요(모두 기본값).
- Redis 명령 대기는 실제로 1초 → 3초가 된다(5.1).
- 운영 OSIV가 꺼진다. 배포 뒤 오류 로그에서 `LazyInitializationException`을 확인한다.

### 측정 (2026-10-22, 로컬)

연결은 받지만 MySQL 인사 패킷을 보내지 않는 로컬 TCP 서버를 무응답 DB 대역으로 두고, HikariCP 5.1.0 + Connector/J 8.3.0으로 `getConnection()` 대기를 쟀다(`initializationFailTimeout=-1`로 빈 풀에서 시작, Spring 컨텍스트 없음). 풀에 살아 있는 커넥션이 없고 새 연결이 멈춘 상황이다.

| 설정 | 시도 | 대기(ms) | 결과 |
| --- | --- | --- | --- |
| 기본(Hikari 30000, URL 타임아웃 없음) | 3 | 30022, 30010, 30003 | `SQLTransientConnectionException` |
| 새 설정(3000·1000, URL 3000·60000) | 5 | 3030, 3003, 3004, 3005, 3004 | `SQLTransientConnectionException` |

`socketTimeout`(실행 중 쿼리가 멈춘 경우)과 실제 RDS·운영 부하는 측정하지 않았다.

## 9. 미결정 사항 (Open Questions)

- 기본값(커넥션 대기 3초, 소켓 대기 60초, 풀 10)은 코드와 로컬 측정으로 정했고 운영 쿼리·커넥션 대기 분포로 확인하지 않았다. 운영 지표(`hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds`)를 본 뒤 조정할지 확인이 필요하다.
- Redis 명령 대기를 ADR-0040 결정 7대로 3초로 둘지, 지금 실제 값인 1초로 맞출지. 이 LLD는 ADR대로 3초를 적용한다.
- 3초로 줄여도 보호자 2명이 구독한 시니어 한 명의 심박(초당 1건)을 8스레드가 다 받지 못한다(초당 0.9건). 장애를 감지하면 DB 호출을 건너뛰는 빠른 실패(서킷 브레이커, 아웃바운드 인가 캐시)를 후속으로 둘지 결정이 필요하다.

## 10. 참고

- HikariCP `HikariConfig`(connectionTimeout·validationTimeout 하한 250ms), Connector/J `connectTimeout`·`socketTimeout`(기본 0 = 무제한)
- LLD-0087 5.3(Redis 명령 타임아웃), LLD-0091(WebSocket 실행기), LLD-0092(@Async 실행기와 풀 10 기준)
