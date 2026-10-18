# LLD-0089: 처리 중 재전송된 심박을 실패가 아닌 중복으로 처리한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #777 진행·Opus 구현 지시, 2026-10-18) |
| Issue | #777 |
| 관련 ADR | ADR-0031, ADR-0040 |
| 작성자 | Claude |
| 작성일 | 2026-10-18 |

## 1. 목적 / 배경

앱은 심박 배치의 처리 결과를 받지 못하면 같은 `batch_id`로 다시 보낸다. 응답을 받지 못한 것이 처리되지 않았다는 뜻은 아니므로 서버는 재전송을 중복 처리하지 않아야 한다.

순차 재전송은 이미 멱등하다(`batch_id` UK → `DUPLICATE`). 그러나 **첫 요청이 아직 처리 중일 때** 같은 배치가 다시 오면 두 요청이 배치 조회(`SensorBatchService` `findByBatchId`)와 샘플 조회(`HeartRateBatchService` `existsByMemberIdAndMeasuredAt`)를 모두 통과한다. 조회 후 저장(check-then-act)이라 늦게 저장하는 쪽이 `heart_rate_event`의 UK `(member_id, measured_at)`에 걸린다. 이 `DataIntegrityViolationException`을 아무도 잡지 않아 **이미 저장된 배치에 실패 응답**(WebSocket `/user/queue/errors`, REST 409 `REQ_4090`)이 나가고 `[WS_EXCEPTION]` ERROR 로그가 남는다.

실제 MySQL 9.0.1(REPEATABLE-READ) 재현(2026-10-18, R=20, 3회 실행 동일): 행·위급·판정·사건·알림 요청은 모두 1회로 중복이 없었다(샘플·위급·판정이 한 트랜잭션이고 사건·outbox는 커밋 후 생성). 그러나 동시 재전송 140건이 실패 응답을 받았고, AI는 같은 샘플을 다시 판정했으며 `heart.decision{output=NO_ALERT}` 메트릭이 과다 집계됐다(기대 3, 실측 8/반복). 단건 경로(`HeartRateService.processHeartRate`)도 같은 구조로 동시 재전송 20건이 실패했다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 배치 경로: `storeAndAssess`에서 샘플 저장(`saveBatchSample`)이 UK 충돌로 실패하면, 같은 `(member_id, measured_at)` 행이 **실제로 있는지 다시 조회해 확인한 경우에만** 건너뛴 샘플로 센다. 행이 없으면(다른 무결성 오류) 그대로 던진다.
- 배치 경로의 늦은 요청은 이어서 기존 `sensor_batch` UK 처리(원문 해시 일치 시 `DUPLICATE`)로 끝난다. 새 응답 상태를 만들지 않는다.
- `heart.decision{output=NO_ALERT}` 메트릭은 실제로 저장된 샘플만 센다.
- 단건 경로: `processHeartRate` 저장 단계의 UK 충돌도 같은 확인을 거쳐 순차 재전송과 같은 응답(`getHeartRateStatus`)을 준다. 저하 경로(ADR-0040)로 보내지 않는다는 LLD-0087 §5.3-2의 판단은 유지하고, "그대로 던진다"만 이 LLD가 대체한다.
- 실제 MySQL 재현 테스트(`HeartBatchResendMySqlTest`, `HEART_MYSQL_URL` 있을 때만 실행)로 전후 비교, 단위 테스트

### Out of scope (보류한 판단)
- **AI 중복 호출 제거.** 처리 전에 배치를 선점(PROCESSING 행 또는 별도 선점 키)하면 사본은 AI를 부르지 않는다. 하지만 "`sensor_batch` 행 존재 = 처리 완료"(ADR-0031 결정 4)를 깨고, 중단된 선점을 회수하는 복구 장치가 새로 필요하다. 중복 판정은 같은 입력의 재판정이고 결과 행은 하나만 남으므로 이번에는 감수한다. AI가 회원별 이력을 쌓아 중복 호출이 판정에 영향을 주는지 확인되면 별도 이슈로 다룬다.
- `INSERT IGNORE`/`ON DUPLICATE KEY`(JPA 밖 네이티브 쿼리, 위급·판정 행과 한 트랜잭션 유지가 어려움), 회원별 락(AI 지연 동안 락 점유)
- 같은 키 S3 재업로드(키에 원문 SHA-256이 들어가 같은 내용 덮어쓰기)
- IMU 배치 경로(샘플 테이블이 없어 기존 `sensor_batch` UK 처리로 충분)

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. 동시 재전송의 늦은 요청이 받는 응답만 바뀐다.

| 경로 | 변경 전 (동시 재전송의 늦은 요청) | 변경 후 |
| --- | --- | --- |
| WebSocket `/app/sensor/batches/send` | `/user/queue/errors` 서버 오류, ACK 없음 | `/user/queue/sensor/result`에 `DUPLICATE`(또는 먼저 배치 행을 쓴 쪽이면 `STORED`) |
| REST `POST /api/v1/sensor/batches` | 409 `REQ_4090` | 200 `DUPLICATE`/`STORED` |
| WebSocket `/app/heart-rate/send-single` | `/user/queue/errors` 서버 오류 | 순차 재전송과 같은 현재 상태 응답 |

## 4. 데이터 모델

변경 없음. `heart_rate_event` UK `uk_heart_event_member_time (member_id, measured_at)`, `sensor_batch` UK `uk_sensor_batch_batch_id (batch_id)`를 그대로 쓴다. ERD 변경 없음.

## 5. 처리 흐름

1. `storeAndAssess`(트랜잭션 없음)가 샘플마다 기존처럼 조회 → AI → `saveBatchSample`(샘플별 자기 트랜잭션)을 호출한다.
2. `saveBatchSample`이 `DataIntegrityViolationException`으로 끝나면 `existsByMemberIdAndMeasuredAt`로 다시 확인한다. 있으면 `skipped`를 올리고 다음 샘플로 간다. 이 샘플은 저장·최신값 갱신·NO_ALERT 집계·판정 로그 대상이 아니다. 없으면 원래 예외를 던진다.
3. 끝까지 처리한 뒤 기존대로 `sensor_batch`를 저장한다. 사본이 이미 저장했으면 기존 UK 처리로 `DUPLICATE`.
4. 단건: `processHeartRate`의 저장 catch에서 UK 충돌이면 2와 같이 확인 후 `getHeartRateStatus(memberId)`를 반환한다. DB 접근 실패(`DataAccessException` 중 UK 충돌이 아닌 것)는 기존 저하 경로 판단을 바꾸지 않는다.

롤백된 시도는 사건·FCM outbox를 만들지 않는다(둘 다 커밋 후 생성). 따라서 중복 알림은 변경 전후 모두 0이며, 이 LLD는 그 성질을 유지한다.

## 6. 예외 / 에러 처리

- 재확인 조회 자체가 실패하면 원래 `DataIntegrityViolationException`을 던진다(조용히 삼키지 않는다).
- UK가 아닌 무결성 오류(FK 등)는 재확인에서 행이 없으므로 그대로 전파된다.
- 로그: 건너뛴 경합 샘플은 WARN 이하, memberId·batchId·건수만 남기고 bpm·판정 사유는 남기지 않는다(backend/CLAUDE.md 로그 규칙).

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: S1 순차 재전송(R=20, K=5) 결과가 변경 전과 같다(첫 STORED, 나머지 DUPLICATE, 행·위급·판정·사건·outbox 각 1회/반복).
- [x] AC2: S2a(첫 요청을 AI에서 붙잡고 사본 5개 동시) 반복마다 예외 0건, 응답은 STORED 1건 + DUPLICATE 나머지. 행·위급·판정·사건·outbox는 각 1회/반복.
- [x] AC3: S2b(위급 샘플에서 붙잡고 사본 1개)도 예외 0건, 위급·판정·사건·outbox 각 1회/반복.
- [x] AC4: S3 단건 동시 재전송에서 예외 0건, 두 요청 모두 정상 응답, 행 1회/반복.
- [x] AC5: `heart.decision{output=NO_ALERT}`가 저장된 샘플 기준 기대값과 같다(S2a에서 반복당 3).
- [x] AC6: UK가 아닌 무결성 오류는 그대로 전파된다(단위 테스트).
- [x] AC7: 변경 전 수치(`before.txt`)와 같은 조건의 변경 후 수치를 기록한다. AI 호출 수는 줄지 않는 것이 의도이며 그대로 기록한다.
- [x] AC8: `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다.

**검증 기록 (2026-10-18, Claude).** 실제 MySQL 9.0.1(REPEATABLE-READ)·Redis 7.2.4 일회용 인스턴스, R=20·K=5·C=5, `HeartBatchResendMySqlTest` 3회 실행. 3회 모두 같은 결과이고 S2a AI 호출 수만 실행마다 다르다.

| 지표 (run당) | 변경 전 | 변경 후 |
| --- | --- | --- |
| 실패 응답 (S2a+S2b+S3) | 140 | 0 |
| S2a 응답 | 첫 요청 예외 20, 사본 STORED 20·예외 80 | 첫 요청 DUPLICATE 20, 사본 STORED 20·DUPLICATE 80 |
| S3 단건 동시 | 첫 요청 예외 20, 둘째 정상 20 | 둘 다 정상 20 |
| 행·위급·판정·사건·outbox | 각 1/반복 | 각 1/반복 |
| `heart.decision{output=NO_ALERT}` S2a | 158~160 | 60 (3/반복) |
| ERROR `[WS_EXCEPTION]` | 140 | 0 |
| AI 호출 S1 / S2a / S2b / S3 동시 | 80 / 180 / 100 / 40 | 80 / 314~315 / 100 / 40 |

- S2a의 AI 호출은 줄지 않았을 뿐 아니라 반복당 9에서 13~19로 늘었다. 변경 전에는 진 사본이 첫 샘플 충돌에서 예외로 끝났지만, 이제는 다음 샘플로 계속 가면서 아직 저장되지 않은 샘플을 각자 다시 판정한다. 사본 1개인 S2b와 단건 S3는 변하지 않았다. 결과 행과 알림은 1회 그대로다.
- Hibernate `SqlExceptionHelper`의 ERROR "Duplicate entry" 줄은 run당 140에서 445~455로 늘었다(`heart_rate_event` 325~335, `sensor_batch` 120). 애플리케이션 예외가 아니라 Hibernate가 SQL 오류마다 남기는 로그다. 새 WARN `심박 동시 재전송 샘플 건너뜀`은 run당 130~137줄이다.
- 단위 테스트: `com.widyu.sensor.*`·`com.widyu.heart.*` 153건 통과, 실패 0, skip 4(MySQL 측정 테스트, `HEART_MYSQL_URL` 없음).
- `bash scripts/harness/verify.sh --base 7c6915a4`: 규칙 검사 통과, `compileJava` 성공, `:backend:widyu-api:test` 1481건 실패 0·skip 17.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 앱은 동시 재전송에서 오류 대신 `DUPLICATE`를 받게 되며, 이는 순차 재전송과 같은 의미(이미 저장됨)다. 앱 변경은 필요 없다.

신규 ADR N/A: ADR-0031의 "저장 우선·재전송 멱등" 결정을 동시 경합까지 지키는 구현이다. 선점 방식으로 바꾸는 것은 ADR-0031 결정 4를 바꾸는 일이라 이번 범위에서 보류했고, 하게 되면 ADR로 다룬다.

## 미결정 사항(Open Questions)

없음.

## 10. 참고

- [LLD-0041](LLD-0041-sensor-batch-ingest.md), [LLD-0047](LLD-0047-heart-rate-batch-v2.md), [LLD-0053](LLD-0053-heart-decision-record.md), [LLD-0087](LLD-0087-degraded-alert-reconciliation.md) §5.3
- 재현 테스트: `backend/widyu-api/src/test/java/com/widyu/sensor/integration/HeartBatchResendMySqlTest.java`
