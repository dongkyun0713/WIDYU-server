# LLD-0076: 심박 위급 저장의 일시적 락 충돌 재시도

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #728 |
| 관련 ADR | - |
| 작성자 | Claude |
| 작성일 | 2026-10-04 |

## 1. 목적 / 배경

심박 샘플 저장 트랜잭션이 데드락처럼 잠깐 기다리면 풀리는 DB 오류로 실패하면, 위급 알림이 나가지 않는다.
위급 판정이 나면 심박 이벤트·위급 기록·판정 기록·보호자 알림 outbox를 한 트랜잭션에 저장한다(LLD-0023, LLD-0047, LLD-0053, LLD-0036).
이 트랜잭션은 실패하면 통째로 롤백되고 서버는 다시 시도하지 않는다. 복구는 기기가 같은 배치를 재전송할 때까지 미뤄진다.
outbox 재발송은 커밋 이후 FCM 발송 실패만 다루므로, outbox 행 자체가 롤백된 이 경우를 복구하지 못한다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api
- `HeartRatePersistenceService.saveBatchSample()`(배치)·`saveMeasurement()`(단건): 락 충돌로 실패하면 새 트랜잭션으로 제한 횟수만큼 다시 시도한다.
- 재시도·소진을 메트릭과 경고 로그로 남긴다.

### Out of scope

- DB가 수 분 이상 응답하지 않는 장애. 재시도로 해결되지 않으며 기기 재전송에 맡긴다. 서버 측 영속 수신(S3 원문 재처리·메시지 브로커)은 후속으로 검토한다.
- 재시도 소진 운영 경보(Grafana 규칙·운영 알림 채널). 메트릭만 남기고 경보 규칙은 별도 작업으로 다룬다.
- 배치 인덱스 행(`sensor_batch`)·배치 단위 판정 보류 저장의 재시도. 위급 알림과 묶이지 않는다.
- 위급 알림 발송 정책. 시니어 본인확인 선행(#706)과 무관하게 동작한다(8절).

## 3. 인터페이스 / API

API 계약 변경 없음. `POST /api/v1/sensor/batches`, WebSocket `/app/sensor/batches/send`, `/app/heart-rate/send-single`의 요청·응답·ACK는 그대로다.

## 4. 데이터 모델

스키마·엔티티 변경 없음.

## 5. 처리 흐름

1. 재시도 애노테이션 `@RetryOnTransientLockFailure`(`global/retry`)
   - 대상 예외: `PessimisticLockingFailureException`(데드락, 락 대기 초과). Hibernate가 MySQL 1213·1205를 `CannotAcquireLockException`으로 번역하며 이 타입의 하위다.
   - 최대 3회 시도, 50ms에서 시작해 2배씩 늘리는 백오프(최대 200ms).
   - 소진하면 마지막 예외를 그대로 던진다.
2. 트랜잭션 경계
   - 두 메서드는 트랜잭션 없는 다른 빈(`HeartRateBatchService`, `HeartRateService`)에서 프록시로 진입하므로 각 메서드가 최외곽 트랜잭션이다.
   - 재시도 인터셉터는 트랜잭션 인터셉터 바깥에서 돈다(`@EnableRetry` 기본 순서, `@RetryOnPointConflict`와 같은 구조). 시도마다 새 트랜잭션이 열리고 실패한 시도는 롤백된다.
3. 판정 기록 재저장
   - 판정 기록은 `HeartRateBatchService`가 AI 판정 직후 만들어 넘긴다. id가 IDENTITY라 첫 시도의 INSERT가 객체에 id를 채우고, 롤백 뒤에도 그 id가 남는다.
   - 재시도에서 같은 객체를 `save()`하면 Spring Data는 id가 있어 `merge`한다. Hibernate 6.5는 그 id의 행이 없으면 새 행으로 INSERT하고 새 id를 받는다. 통합 테스트 SQL 로그에서 `decision_record` INSERT → 락 충돌 → 같은 id SELECT(없음) → `decision_record` INSERT → 심박·위급·outbox INSERT 순서를 확인했다.
   - 호출자는 이후 `decisionId`(문자열)만 쓰고 surrogate id는 쓰지 않는다.
   - 엔티티 사본을 만드는 방식(`toBuilder`)도 검토했다. 현재 버전에서 필요하지 않고 widyu-domain 변경이 생겨 채택하지 않았다. 이 merge 동작이 Hibernate 업그레이드로 바뀌면 배치 재시도 통합 테스트가 실패하므로 그때 사본 저장으로 바꾼다.
4. 부수효과
   - AI 판정은 `saveBatchSample()` 호출 전에 끝나므로 재시도해도 AI를 다시 부르지 않는다.
   - 보호자 알림 outbox는 같은 트랜잭션에서 저장되고 커밋 후에만 발송 작업을 제출한다(LLD-0036). 실패한 시도의 outbox 행과 발송 제출은 남지 않는다.
   - 단건 경로의 최신값(Redis `HeartRateResult`)은 트랜잭션 밖 쓰기라 롤백되지 않는다. 같은 키를 같은 값으로 덮으므로 재시도해도 결과가 같다.
5. 관측
   - 시도가 실패할 때마다 `db.transient.retry{outcome=attempt_failed, method}` 카운터를 올리고 경고 로그(메서드·시도 횟수·예외 타입)를 남긴다. 마지막 시도의 실패도 포함하므로 소진된 호출은 3으로 잡힌다.
   - 소진하면 `outcome=exhausted`로 센다. 심박 값·판정 사유는 로그에 남기지 않는다(#639).
   - 리스너는 전역 리스너로도 등록되어 `@RetryOnPointConflict` 메서드가 락 충돌로 끝난 경우도 `exhausted`로 센다(재시도 없이 1회).

## 6. 예외 / 에러 처리

| 예외 | 재시도 | 이유 |
| --- | --- | --- |
| `PessimisticLockingFailureException`(데드락·락 대기 초과) | O | 잠시 뒤 다시 시도하면 대부분 성공한다 |
| DB 연결 실패(`CannotGetJdbcConnectionException`, `CannotCreateTransactionException`) | X | DB 중단이면 재시도도 실패하고, 커넥션 대기 시간 × 횟수만큼 수신 스레드를 붙잡는다 |
| `DataIntegrityViolationException` | X | 중복·잘못된 데이터라 결과가 같다 |
| `BusinessException`(회원 없음 등) | X | 업무 오류 |

- 새 에러 코드 없음. 소진하면 기존과 같이 예외가 전파되어 배치는 저장 완료 ACK 없이 `/user/queue/errors`(WebSocket) 또는 500(REST)으로 끝나고 기기 재전송으로 복구된다.
- 커밋 결과를 알 수 없는 실패(COMMIT 직후 연결 끊김)는 재시도 대상 예외가 아니다. 기기가 재전송하면 `(member_id, measured_at)` 유니크 판정으로 건너뛴다.
- 한계: 락 대기 초과는 MySQL `innodb_lock_wait_timeout`(기본 50초)만큼 기다린 뒤 발생하므로, 최악의 경우 수신 스레드가 그 시간 × 3만큼 묶인다. 이 경로의 INSERT가 락을 오래 기다리는 경우는 같은 시각 샘플의 동시 재전송뿐이라 드물다고 보고, 데드락과 함께 재시도한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 배치 샘플 저장의 첫 시도가 락 충돌로 실패하고 다음 시도가 성공하면 심박 이벤트·위급 기록·판정 기록·보호자 outbox가 각각 한 건만 남는다.
- [x] 위 경우 첫 시도에서 id가 채워진 판정 기록도 호출자가 만든 `decisionId`로 한 건 저장된다.
- [x] 위 경우 발송 작업 제출은 커밋된 outbox 한 건에 대해서만 일어난다.
- [x] 단건 저장의 첫 시도가 락 충돌로 실패하면 다시 시도해 심박 이벤트·위급 기록이 한 건씩 남는다.
- [x] 락 충돌이 3회 연속이면 예외가 호출자에게 전파되고 아무 행도 남지 않는다.
- [x] 제약 위반은 재시도하지 않고 한 번만 시도한다.
- [x] 실패한 시도와 소진이 `db.transient.retry`(`outcome=attempt_failed`·`exhausted`) 카운터에 기록된다.
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB·API·설정 변경 없음. 배포 순서 제약 없음.
- 시니어 본인확인 선행(#706)은 위급 알림 리스너를 커밋 후 실행(`AFTER_COMMIT`)으로 바꾼다. 이 경우에도 실패한 시도에서 발행된 이벤트는 커밋되지 않아 리스너가 실행되지 않으므로 재시도 결과는 같다.
- 정상 경로의 추가 비용은 재시도 프록시 호출 하나다.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고

- LLD-0023 심박 단건 수신, LLD-0036 FCM 영속 발송, LLD-0047 심박 배치 v2, LLD-0053 심박 판정 기록
- `global/retry/RetryOnPointConflict`: 같은 구조의 낙관적 락 재시도
