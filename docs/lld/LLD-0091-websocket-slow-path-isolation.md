# LLD-0091: 느린 심박·센서 처리를 WebSocket 수신 스레드에서 분리한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #782 측정 후 구현·Opus 진행 지시, 2026-10-19) |
| Issue | #782 |
| 관련 ADR | ADR-0017(심박 결과는 처리 후 발신 세션에 ACK) |
| 작성자 | Claude |
| 작성일 | 2026-10-19 |

## 1. 목적 / 배경

위치 갱신·심박·센서 STOMP 메시지는 모두 `websocketInboundExecutor`(`MetricsConfig`: core = CPU×2, max 16, 대기열 1,000, `CallerRunsPolicy`)에서 처리된다. 심박은 이 스레드에서 AI 서버를 동기 호출하고(읽기 타임아웃 10초), 센서 배치는 S3 업로드를 동기로 한다. Spring Boot 3.3.5는 유일한 `AsyncTaskExecutor` 빈을 outbound(응답) 채널에도 쓰므로 **응답도 같은 대기열을 한 번 더 기다린다.**

실측(#782 1단계, 로컬 루프백·서비스 대역, `-XX:ActiveProcessorCount`로 CPU 1·2개 흉내, 위치 세션 20·심박 세션 H가 각각 초당 1건):
- 심박 수요(H × AI 지연)가 코어 스레드 수(CPU 1개 → 2, 2개 → 4)를 넘는 순간 **모든 사용자의 위치 ACK가 18~23초** 지연(p95). 그 아래에서는 2~5ms
- `ThreadPoolExecutor`는 대기열 1,000개가 다 차야 스레드를 늘리므로, 늘어난 뒤에는 이미 1,000건이 위치 메시지 앞에 줄 서 있다
- 대기열이 차면 `CallerRunsPolicy`가 Tomcat 수신 스레드(`http-nio-*`)에서 AI 호출을 실행해 수신 스레드까지 10초씩 묶였고, AI 10초·심박 4세션에서 위치 ACK의 41~55%가 45초 안에 오지 않았다
- CPU를 2개로 늘려도 임계점만 올라갈 뿐 넘은 뒤의 양상은 같다

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- **경로별 실행 공간 분리(bulkhead):** 심박 단건 처리와 센서 배치 처리를 각자 전용 실행기(상한 있는 스레드·대기열)로 넘긴다. 수신 스레드는 요청을 넘기고 바로 반환한다. 위치 갱신은 지금처럼 수신 스레드에서 처리한다
- **응답 채널 분리:** outbound 채널에 전용 실행기를 지정한다
- **처리 후 응답 유지:** 심박·센서 ACK는 처리가 끝난 뒤 발신 세션에만 보낸다(ADR-0017, LLD-0088과 같은 세션 지정 방식). 보호자 토픽 브로드캐스트도 처리 후
- **과부하 응답:** 전용 실행기의 대기열이 가득 차면 수신 스레드에서 실행하지 않는다(`CallerRunsPolicy` 금지). 발신 세션의 `/user/queue/errors`로 재시도 가능한 과부하 오류를 보내고 WARN 로그·메트릭을 남긴다
- **예외 경로:** 전용 실행기 안에서 난 예외는 `@MessageExceptionHandler`가 잡지 못하므로, 기존 `WebSocketExceptionHandler`와 같은 형식으로 발신 세션 `/user/queue/errors`에 보내고 같은 로그(`[WS_EXCEPTION]` 등 Grafana 규칙이 읽는 형식)를 남긴다
- 수신 실행기의 `CallerRunsPolicy` 재검토: 위치만 남으므로 대기열이 차면 수신 스레드 실행 대신 거절·로그로 바꿀지 측정과 함께 판단하고 근거를 남긴다
- 스레드 수·대기열 크기는 설정값(`application-*.yml`)으로 두고, 기본값과 근거(AI 타임아웃 10초, 1Hz 전송)를 LLD 검증 메모에 남긴다
- 변경 전과 같은 측정 테스트(`WebSocketInboundPoolMeasurementTest`)로 전후 비교

### Out of scope
- AI 호출 자체의 비동기·논블로킹화(WebClient 등): 트랜잭션·영속 코드가 콜백 스레드로 옮겨 가 변경 범위가 크다
- 대기열을 작게 해서 스레드를 먼저 늘리는 방식, 코어 스레드만 늘리는 방식: 임계점만 옮기고 양상은 같다(1단계 측정)
- 외부 메시지 브로커 도입, 다중 서버
- 느린 수신자(송신 버퍼) 정책
- 앱 코드 변경(과부하 오류는 기존 오류 큐로 가므로 앱의 기존 재전송 경로를 탄다)

## 3. 인터페이스 / API

STOMP 목적지·페이로드 변경 없음. 바뀌는 것은 시점과 과부하 응답뿐이다.

| 경로 | 변경 전 | 변경 후 |
| --- | --- | --- |
| `/app/heart-rate/send-single` | 수신 스레드에서 AI 동기 호출 후 ACK | 심박 실행기에서 처리 후 같은 ACK(`/user/queue/heart-rate/result`, 발신 세션) |
| `/app/sensor/batches/send` | 수신 스레드에서 S3 업로드 후 `@SendToUser` | 센서 실행기에서 처리 후 같은 ACK(`/user/queue/sensor/result`, 발신 세션). 검증 실패 `REJECTED` 동일 |
| `/app/location/update` | 수신 스레드 | 변경 없음 |
| 과부하 | 수신 스레드에서 실행(CallerRuns) | `/user/queue/errors`에 재시도 가능 과부하 오류 |

## 4. 데이터 모델

변경 없음. ERD 변경 없음.

## 5. 처리 흐름

1. 수신 스레드: 인증·인가 인터셉터(기존) → 컨트롤러가 회원·세션 ID를 확정(Principal은 수신 스레드에서 읽는다)하고 처리 작업을 전용 실행기에 제출 → 반환
2. 전용 실행기: 기존 서비스 호출(트랜잭션·멱등·저장 순서 기존과 동일) → 보호자 토픽 브로드캐스트 → 발신 세션 ACK
3. 제출 거절(대기열 가득): 발신 세션에 과부하 오류, WARN, 메트릭 증가. 처리하지 않는다
4. 처리 중 예외: 발신 세션 오류 응답 + 기존 예외 로그 형식

## 6. 예외 / 에러 처리

- 보안 컨텍스트·MDC(traceId)는 스레드를 넘어가지 않으므로 필요한 값(회원 ID, 세션 ID, traceId)을 명시적으로 넘긴다
- 애플리케이션 종료 시 전용 실행기는 진행 중 작업을 기다린 뒤 종료한다(graceful)

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: AI 지연 10초·심박 세션 4·8, CPU 1·2개 조건에서 위치 ACK p95가 AI 지연이 없을 때와 같은 수준(수십 ms 이내)이고 위치 ACK 수신 600/600이다
- [x] AC2: 같은 조건에서 수신 스레드(`http-nio-*`)가 심박 처리를 실행하지 않는다(CallerRuns 0)
- [x] AC3: 심박·센서 ACK는 처리 후 발신 세션에만 1건씩 간다(LLD-0088 통합 테스트 `WebSocketSessionAckIntegrationTest` 통과)
- [x] AC4: 전용 실행기가 가득 차면 발신 세션이 과부하 오류를 받고, 처리되지 않은 요청 수와 오류 수가 같다
- [x] AC5: 처리 중 예외는 발신 세션 `/user/queue/errors`로 가고 기존 로그 형식을 따른다
- [x] AC6: 변경 전 측정(`before.txt`)과 같은 셀을 변경 후 측정해 함께 기록한다(심박 ACK 수·처리량 포함, 줄어든 지표도 그대로)
- [x] AC7: `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다

### 검증 메모 (2026-10-19, Claude Opus / feature-782)

**구현.** `WebSocketWorkDispatcher.dispatch`가 수신 스레드에서 발신 세션 ID·Principal·목적지·MDC를 확정해 전용 실행기에 넘긴다. 컨트롤러는 Principal로 회원을 확정한 뒤 넘기고(LLD-0088), 작업 스레드가 기존 서비스 → 보호자 토픽 → `sendToSession`(세션 지정 헤더, `getMessageHeaders()`) 순으로 보낸다. 센서는 `@SendToUser` 반환 대신 같은 방식으로 ACK를 보내고, `REJECTED`·S3 실패 분기는 `process()`에 그대로 두었다. 작업 중 예외는 `WebSocketExceptionHandler.handle`(기존 `@MessageExceptionHandler`도 이 메서드로 위임)이 같은 WARN·`[WS_EXCEPTION]` 형식으로 남기고, Api 값은 기존과 같은 `컨트롤러.메서드` 이름이다. `@Async`는 쓰지 않았다(명시적 `Executor.execute`).

- **과부하 응답:** 거절(`RejectedExecutionException`) 시 실행하지 않고 발신 세션 `/user/queue/errors`에 `{"error":"TEMPORARILY_BUSY","message":"요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."}`를 보낸다. 기존 오류 형식(`error`·`message`)을 지키되 `error` 자리에 예외 클래스 이름 대신 기존 `ErrorCode.TEMPORARILY_BUSY`(SRV_5031) 이름을 실어 앱이 재시도 대상으로 구분할 수 있게 했다. WARN 로그는 api·memberId·sessionId만, 메트릭은 `websocket.work.rejected{api}` 카운터
- **응답 채널:** `websocketOutboundExecutor`를 `configureClientOutboundChannel`에 지정했다. Spring이 등록된 실행기의 스레드 이름을 `clientOutboundChannel-`로 바꾸므로 설정에서도 같은 이름을 쓴다. `ChannelRegistration`은 `executor(...)`가 `taskExecutor(...)`보다 우선하고, Boot 자동 설정은 `AsyncTaskExecutor`가 하나일 때만 `executor(...)`를 부른다(javap 확인). 실행기가 넷이 된 지금 수신·응답 채널이 각자 지정한 실행기를 쓰는지 통합 테스트가 확인한다
- **메트릭:** `heart.websocket.processing`(백분위 설정됨)은 컨트롤러 `@Timed`가 이제 제출 시간만 재게 되므로 작업 스레드 안에서 `Timer`로 잰다(태그 `path=single`, `@Timed`가 붙이던 class·method·exception 태그는 없어짐). 새 실행기는 `executor.*{name=websocket.heart|websocket.sensor|websocket.outbound}`
- **종료:** 세 실행기 모두 `waitForTasksToCompleteOnShutdown=true`, 대기 상한 심박 15초(AI 읽기 타임아웃 10초 + 여유), 센서 10초(S3 apiCallTimeout 5초 + 여유), 응답 5초

**기본값(`application.yml` `websocket.executor.*`, 환경변수로 변경).** 심박·센서는 core = max 고정이다. `ThreadPoolExecutor`는 대기열이 다 찬 뒤에야 스레드를 늘려 늘어날 때 이미 대기열 전체가 밀려 있었다(1단계 측정).

| 실행기 | 스레드 | 대기열 | 거절 | 근거 |
| --- | --- | --- | --- | --- |
| 심박 | 8 (`WS_HEART_THREADS`) | 200 (`WS_HEART_QUEUE_CAPACITY`) | 과부하 오류 | 세션당 1Hz. AI 정상(실측 14~81ms)이면 스레드당 초당 10건 이상. AI 10초면 상한 초당 0.8건. 거절된 단건은 앱이 다시 보내지 않으면 저장되지 않아 짧은 몰림은 대기열이 받고, 200건은 AI 정상 시 약 2초에 비워진다 |
| 센서 | 8 (`WS_SENSOR_THREADS`) | 500 (`WS_SENSOR_QUEUE_CAPACITY`) | 과부하 오류 | 스트림당 1Hz, S3 PUT 상한 5초. 오프라인 뒤 몰아 보내는 배치를 받는다. 32KB × 500 = 최대 16MB |
| 응답 | 4 (`WS_OUTBOUND_THREADS`) | 1000 (`WS_OUTBOUND_QUEUE_CAPACITY`) | 호출자 실행 | 운영 2 vCPU 기준 Spring 기본(CPU×2). 버리면 ACK가 사라지므로 가득 차면 보내는 스레드가 직접 보낸다(세션 버퍼에 넣는 짧은 작업) |

**수신 실행기 `CallerRunsPolicy`: 유지.** 변경 후 측정에서 수신 실행기 큐 최대 9, 활성 최대 2로 거절 정책이 발동할 상황이 없었다. 남은 작업은 ms 단위 위치·구독·연결 처리이고, 이 큐에서 거절하면 CONNECT·SUBSCRIBE·DISCONNECT까지 버려진다. 넘칠 때 수신 스레드가 직접 처리하는 역압이 버리는 것보다 낫다.

**측정(AC1·AC2·AC4·AC6).** 1단계와 같은 `WebSocketInboundPoolMeasurementTest`·조건(L=20, 1초 1건, 창 30초, 드레인 15초, C∈{1,2}, D∈{0,2000,10000}, H∈{1,2,4,8}, 로컬 루프백·서비스 대역)으로 2026-10-19 17:38~17:55 KST에 26셀을 쟀다. D=10초 × H=4·8 × C=1·2는 각 2회. 셀별 전체 표는 PR #788 본문에 있다.

| 셀 | 위치 ACK 수신 (전 → 후) | 위치 p95 (전 → 후) | 심박 ACK 측정 창 (전 → 후) | Tomcat 스레드 심박 실행 (전 → 후) |
| --- | --- | --- | --- | --- |
| C=1, D=10s, H=4 | 275/600 → 600/600 (2회) | 미수신 → 2~4ms | 8~9/120 → 4/120 | 282~283 → 0 |
| C=1, D=10s, H=8 | 352/600 → 600/600 (2회) | 미수신 → 1~3ms | 19/240 → 0/240, 과부하 오류 48 | 382 → 0 |
| C=2, D=10s, H=4 | 269~274/600 → 600/600 (2회) | 미수신 → 2~3ms | 6~7/120 → 4/120 | 276~281 → 0 |
| C=2, D=10s, H=8 | 351/600 → 600/600 (2회) | 미수신 → 1~2ms | 20/240 → 0/240, 과부하 오류 48 | 381 → 0 |
| C=1·2, D=2s, H=4 | 600/600 → 600/600 | 18.2~20.4s → 2~6ms | 109~112 → 111~112 /120 | 0 → 0 |
| C=1·2, D=2s, H=8 | 600/600 → 600/600 | 21.1~21.5s → 2~5ms | 225~226 → 96 /240 | 57~66 → 0 |
| C=1·2, D=0 | 600/600 → 600/600 | 2~5ms → 2~5ms | 전부 → 전부 | 0 → 0 |

- 전 셀 위치 ACK p95 1~7ms, p99 ≤31ms, 최대 310ms. 심박 대역은 전 셀 심박 실행기 스레드에서만, 위치 대역은 수신 실행기 스레드에서만 실행됐다
- 셀이 끝난 뒤 심박 송신 = ACK + 과부하 오류(미집계 0, 26셀). 처리하지 않은 요청 수와 과부하 오류 수가 같다(AC4). 이 합계는 셀 끝에 대역 대기를 풀어 남은 작업을 즉시 끝낸 값이라 처리량 지표로 쓰지 않는다
- **줄어든 지표:** 심박 수요가 8스레드를 넘는 셀(D=2s·H=8, D=10s)에서 측정 창 안 심박 ACK가 변경 전보다 적다. 변경 전에는 대기열이 찬 뒤 16스레드와 Tomcat 스레드까지 심박에 쓰여 더 처리했지만 모든 사용자의 위치 ACK가 21초 이상 밀리거나 오지 않았다. 심박 처리량은 여전히 AI 지연 × 스레드 수에 묶이고, 상한을 넘는 수요는 과부하 오류로 돌아간다

**테스트(AC3·AC5).** `WebSocketSessionAckIntegrationTest`(심박·센서가 각 실행기 스레드에서만 실행, 수신·응답 채널 실행기 지정 확인 추가. 라우팅 테스트라 대기열을 1000으로 올려 연속 전송이 거절되지 않게 함), `WebSocketWorkDispatcherTest`(가득 찬 실행기에 넘친 3건 → 오류 3건·카운터 3·처리 2건, 작업 중 서버 오류 → 세션 오류 큐·`[WS_EXCEPTION]` 한 줄, 4xx → WARN), `WebSocketExecutorConfigTest`(고정 스레드·AbortPolicy, 종료 시 진행 중 작업 완료), 컨트롤러 단위 테스트 갱신. `verify.sh --base 87b9c18f` 통과(widyu-api 1502건, 실패 0, 건너뜀 28).

**수신 시각 보정(2026-10-19).** 센서 배치 WebSocket 경로는 `server_received_at_ms`를 실행기 작업 시작이 아니라 수신 스레드에서 넘기기 전에 잡아 `SensorBatchService.ingest(memberId, payload, serverReceivedAtMs)`로 넘긴다. 실행기 대기가 수신 시각에 섞이지 않는다(정책 1.1.5). REST는 서비스 진입 시각 그대로이고 `accepted`·`persisted`는 처리 시점이다. 심박 단건 경로는 수신 시각을 기록하지 않아 해당 없음.

**남은 위험.** 앱이 `TEMPORARILY_BUSY`를 받고 재전송하는지 미확인(8절). 대기열을 넘는 몰림(심박 208건, 센서 508건 이상 동시)은 이제 과부하 오류가 된다(이전에는 수신 대기열 1000과 CallerRuns가 받았다). 센서 S3 지연 부하 측정과 실제 서버 측정은 하지 않았다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 심박·센서 ACK가 오는 시점은 같다(처리 완료 후). 과부하 시 앱은 오류 큐로 재시도 가능 오류를 받는다 — 지금은 같은 상황에서 응답이 수십 초 늦거나 오지 않았다. 앱이 이 오류를 받으면 기존처럼 재전송하는지는 앱 팀 확인이 필요하다(배치 경로는 ACK가 올 때까지 재전송, #777 멱등 처리로 안전).

신규 ADR N/A: 처리 위치(스레드)만 바꾸고 ADR-0017의 응답 계약은 유지한다. 기각한 대안과 이유는 2절 Out of scope.

## 미결정 사항(Open Questions)

없음. 과부하 오류의 코드·문구는 기존 `/user/queue/errors` 형식 안에서 구현자가 정하고 근거를 남긴다.

## 10. 참고

- [LLD-0023](LLD-0023-heart-rate-single-measurement-transport.md), [LLD-0088](LLD-0088-websocket-single-auth-session-ack.md), [LLD-0089](LLD-0089-heart-resend-concurrent-idempotency.md)
- 1단계 측정: `WebSocketInboundPoolMeasurementTest`(`WS_POOL_MEASURE=1`일 때만 실행)
- Spring 성능 설정: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/configuration-performance.html
