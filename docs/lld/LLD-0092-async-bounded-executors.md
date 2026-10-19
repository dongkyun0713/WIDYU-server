# LLD-0092: @Async 작업을 상한 있는 실행기에서 실행한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #791 수정·Opus 구현 지시, 2026-10-19) |
| Issue | #791 |
| 관련 ADR | ADR-0004, ADR-0010, ADR-0015 |
| 작성자 | Claude |
| 작성일 | 2026-10-19 |

## 1. 목적 / 배경

`AsyncConfig`는 `@EnableAsync`만 있고 `@Async`가 쓸 실행기를 지정하지 않는다. Spring Boot는 `Executor` 빈이 하나도 없을 때만 기본 풀(`applicationTaskExecutor`)을 만드는데, `websocketInboundExecutor`(`MetricsConfig`)와 STOMP 채널 실행기 빈이 있어 기본 풀이 생기지 않는다. 그래서 `@Async`는 **작업마다 새 스레드를 만들고 개수 제한이 없는 `SimpleAsyncTaskExecutor`**로 실행된다(PR #788 Codex 검수 지적, Spring Boot 3.3.5 자동 구성 조건과 컨텍스트 기동으로 확인).

해당 메서드와 영향:
- `AlbumVideoProcessingService.processVideosAsync` / `processStagedVideosAsync` (`@Async @Transactional`): FFmpeg 썸네일·S3 업로드 동안 **DB 커넥션을 붙잡는다.** 동시 업로드에 상한이 없어 CPU 1~2개 서버의 CPU·메모리와 커넥션 풀을 고갈시킬 수 있다
- `HealthScheduleLocationEventListener.handleSeniorLocationUpdated` (`@Async @EventListener @Transactional`): 시니어 위치 갱신마다(세션당 초당 1건) 발행되는 이벤트를 받아 **위치 1건마다 새 스레드와 DB 트랜잭션**을 연다

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `AsyncConfigurer`(또는 동등한 방식)로 **기본 `@Async` 실행기**를 상한 있는 풀로 지정하고, `AsyncUncaughtExceptionHandler`로 예외를 로그로 남긴다
- **영상 처리 전용 실행기**: CPU 무거운 작업이므로 작은 고정 스레드 수와 유한 대기열. `@Async("...")`로 명시
- **위치 이벤트 처리**: 기본 실행기 또는 전용 실행기로 상한을 둔다. 다음 위치 갱신(약 1초 뒤)이 같은 판정을 다시 하므로, 가득 찼을 때 이벤트를 버리고 WARN·메트릭을 남기는 것을 허용한다. 호출 스레드(위치 처리 스레드)에서 대신 실행하지 않는다
- **영상 거절 처리**: 영상 실행기가 가득 차면 호출 스레드(HTTP 요청)에서 FFmpeg를 실행하지 않는다. 앨범이 `PROCESSING`에 영구히 남거나 스테이징 객체·임시 파일이 고아로 남지 않게 하고, 사용자에게 실패를 알 수 있는 응답(예: `TEMPORARILY_BUSY` 재시도 안내)을 준다. 기존 실패 규칙(실패 시 `DELETED`)과 호출부(`AlbumFacadeImpl`, `AlbumUploadSessionFacadeImpl`)의 흐름을 읽고 가장 단순한 일관된 방식을 고른다
- 스레드·대기열 크기는 설정값으로 두고 기본값과 근거를 검증 메모에 남긴다
- 실행기 지표(Micrometer)와 거절 카운터
- 전후 측정: 같은 테스트로 "동시 영상 처리 N건 요청 시 동시에 실행된 최대 수"와 "위치 이벤트 M건 발행 시 생성된 스레드 수"를 비교(로컬, FFmpeg·S3·DB는 대역 가능)

### Out of scope
- 영상 처리 중 DB 커넥션 점유 자체를 줄이는 구조 변경(트랜잭션 경계 분리) — 상한으로 위험을 묶고, 별도 이슈로 남긴다
- WebSocket 실행기(#782, LLD-0091)
- 영상 처리의 영속 작업 큐 전환

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. 영상 실행기 포화 시에만 업로드 완료 API가 재시도 가능한 오류를 돌려줄 수 있다(구현자가 호출부 흐름을 보고 정하고 Swagger 문서를 맞춘다).

## 4. 데이터 모델

변경 없음.

## 5. 처리 흐름

1. 영상: 업로드 완료 요청 → 앨범 `PROCESSING` → 영상 실행기에 제출. 성공 시 기존 흐름. 거절 시 2절의 거절 처리
2. 위치 이벤트: 발행 → 기본(또는 전용) 실행기에 제출. 거절 시 버림·WARN·메트릭
3. 그 밖의 `@Async`: 기본 실행기

## 6. 예외 / 에러 처리

- `@Async void` 예외는 `AsyncUncaughtExceptionHandler`가 메서드·예외 종류만 로그로 남긴다(개인정보·좌표 금지)
- 종료 시 실행기는 진행 중 작업을 기다린다(영상은 길 수 있으므로 상한 시간을 둔다)

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 애플리케이션 컨텍스트에서 `@Async` 메서드가 `SimpleAsyncTaskExecutor`가 아닌 지정한 실행기 스레드에서 실행된다(스레드 이름으로 확인)
- [x] AC2: 동시 영상 처리 요청 N건(예: 20)에서 동시에 실행된 처리 수가 설정한 상한을 넘지 않는다. 변경 전 수치도 같은 테스트로 기록한다
- [x] AC3: 위치 이벤트 M건(예: 1,000)을 빠르게 발행해도 처리 스레드 수가 상한을 넘지 않는다. 변경 전 생성 스레드 수도 기록한다
- [x] AC4: 영상 실행기가 가득 차면 호출 스레드에서 처리하지 않고, 앨범·스테이징 객체·임시 파일이 일관된 상태로 정리되며 사용자에게 재시도 가능한 실패가 전달된다
- [x] AC5: 위치 이벤트가 거절되면 WARN·메트릭이 남고 호출 스레드에서 실행하지 않는다
- [x] AC6: 기존 앨범·건강 일정 테스트가 통과한다
- [x] AC7: `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다

2026-10-19 검증: 기본 `@Async` 실행기(`defaultAsyncExecutor`, 스레드 2·대기열 100, 가득 차면 버림+WARN+`async.executor.rejected`)와 영상 실행기(`albumVideoExecutor`, 스레드 2·대기열 10, 가득 차면 `TaskRejectedException`)를 `AsyncConfigurer`로 지정했다. 기본값 근거: 위치 이벤트·영상 작업이 모두 DB 트랜잭션을 열어 커넥션을 쓰므로 각 2개로 묶고(Hikari 기본 풀 10), FFmpeg는 1~2 vCPU에서 CPU를 다툰다. 위치 이벤트는 실제로 시니어당 초당 1건이라 2스레드·대기열 100이면 대략 초당 100건까지 받는다. 모두 환경변수로 바꿀 수 있다.

영상 거절 시 두 경로 모두 호출 스레드에서 처리하지 않는다. 서버 경유 업로드는 먼저 올린 사진·영상 임시 파일과 `PROCESSING` 앨범을, 직접 업로드는 앨범·스테이징 객체·세션을 기존 보상 경로로 정리하고 503(`TEMPORARILY_BUSY`)을 돌려준다. 직접 업로드는 완료 요청에서 multipart를 마무리하고 세션을 완료로 표시한 뒤 영상을 제출하므로 세션을 남길 수 없다 — 거절되면 사용자는 영상을 다시 올려야 한다(한계).

전후 측정(`AsyncExecutorIntegrationTest`, `ASYNC_MEASURE=1`일 때만, 로컬 H2·대역): 위치 이벤트 1,000건을 약 50ms에 몰아 발행했을 때 처리 스레드 1,000개(작업마다 새 스레드) → 2개, JVM 스레드 최대 993 → 26, DB 활성 커넥션 최대 10 → 2. 이 인위적 폭주에서 896건은 설계대로 버려졌다. 영상 20건 동시 요청: 동시 실행 최대 10(커넥션 풀이 사실상 상한) → 2, 접수 12·거절 8. 실제 FFmpeg의 CPU 경합과 운영 환경은 측정하지 않았다. `verify.sh --base 1bc492cd` 통과(테스트 1,527건, 실패 0·건너뜀 29).

종료 상한 보정(2026-10-19, Codex 검수 지적): Spring은 `awaitTerminationSeconds`가 지나도 경고만 남기고 실행 중 작업을 끊지 않아, 긴 영상 처리·대기열이 비데몬 스레드로 JVM 종료를 막았다. 두 실행기를 `BoundedShutdownExecutor`로 바꿔 대기 시간이 지나면 `shutdownNow`로 실행 중 작업을 중단하고 대기열 작업을 버린 뒤 그 수를 WARN으로 남긴다. 중단된 영상의 앨범이 `PROCESSING`에 남는 것은 재시작 시 진행 중 업로드 처리를 미룬 기존 한계(ADR-0004)와 같다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 영상 업로드가 몰리면 일부 요청이 즉시 재시도 오류를 받는다 — 지금은 같은 상황에서 서버 자원·커넥션 풀이 고갈될 수 있었다. 앱의 재시도 처리 확인 필요.

신규 ADR N/A: 기존 비동기 처리 결정(ADR-0004·0010·0015)을 유지하고 실행 자원에 상한만 둔다.

## 미결정 사항(Open Questions)

없음. 기본값(영상 동시 실행 수·대기열, 기본 실행기 크기)은 서버 사양(1~2 vCPU), FFmpeg CPU 사용, 커넥션 풀 크기를 근거로 구현자가 정하고 설정으로 바꿀 수 있게 한다.

## 10. 참고

- [LLD-0006](LLD-0006-album-video-upload-pipeline.md), [LLD-0012](LLD-0012-album-video-failure-compensation.md)(영상 실패 보상), LLD-0091(PR #788, WebSocket 실행기)
- Spring Boot `TaskExecutionAutoConfiguration`(`@ConditionalOnMissingBean(Executor.class)`)
