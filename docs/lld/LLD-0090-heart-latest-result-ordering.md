# LLD-0090: 늦게 처리된 심박이 Redis 최신값을 과거로 되돌리지 않게 한다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #780 진행·Opus 구현 지시, 2026-10-19) |
| Issue | #780 |
| 관련 ADR | ADR-0031 |
| 작성자 | Claude |
| 작성일 | 2026-10-19 |

## 1. 목적 / 배경

보호자 홈(`GuardianHomeService`)·시니어 홈(`SeniorHomeService`)·심박 상태 조회(`HeartRateService`)는 Redis 해시 `heart_rate_result:{memberId}`(`@RedisHash`, TTL 24시간)를 최신 심박으로 읽는다. 쓰기는 두 곳이다.

- 단건: `HeartRatePersistenceService.saveMeasurement` → `heartRateResultRepository.save(result)`
- 배치: `HeartRatePersistenceService.updateLatestResult` → `heartRateResultRepository.save(...)`

둘 다 **측정 시각을 비교하지 않고 덮어쓴다.** 수신 순서는 t1 → t2여도 처리 완료 순서가 t2 → t1이면(앞선 측정의 AI 판정 지연, 공용 inbound 스레드 풀의 병렬 처리, 배치 경로와 단건 경로의 교차) 최신값이 t1으로 돌아간다. TCP는 연결 안의 바이트 순서를 지키지만 서버의 병렬 처리 완료 순서는 지키지 않는다. PR #779 리뷰도 동시 재전송 사본이 끝까지 처리되며 더 오래된 샘플로 최신값을 덮을 수 있다고 지적했다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (도메인 엔티티 형식 변경은 아래 원칙에 따라 필요할 때만 widyu-domain)
- 최신 심박 쓰기 두 경로를 **"저장된 값이 없거나, 새 측정 시각이 저장된 측정 시각보다 늦을 때만 갱신"하는 원자적 조건부 쓰기** 하나로 모은다. 같은 측정 시각이면 갱신하지 않는다
- 갱신할 때만 TTL을 24시간으로 다시 건다. 갱신하지 않으면 기존 값과 TTL을 건드리지 않는다
- 기존 읽기(`heartRateResultRepository.findByMemberId`)가 바뀐 쓰기 결과를 그대로 읽어야 한다(응답 형식 불변)
- 실제 Redis 통합 테스트로 변경 전후 회귀 건수 측정, 단위 테스트

### Out of scope
- `heart_rate_event` 이력·위급·판정·알림: 순서와 무관하게 모두 저장한다(이력은 최신값과 별개)
- 보호자 토픽 `/topic/heart-rate/{id}` 실시간 브로드캐스트의 순서(클라이언트에 측정 시각을 주고 오래된 값을 버리게 하는 것), inbound 순서 보존 옵션(`setPreserveReceiveOrder`)
- 위치 최신값의 같은 문제(별도 검토)
- #778(동시 재전송 AI 중복 판정)

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. 최신 심박을 읽는 API의 응답 필드·형식은 그대로이고, 값만 "가장 늦은 측정"으로 보장된다.

## 4. 데이터 모델

Redis 키 `heart_rate_result:{memberId}` 유지. 원자적 비교를 위해 구현 방식은 아래 중 하나를 고르고 근거를 LLD 검증 메모에 남긴다.

1. **Lua 스크립트 조건부 갱신(우선 검토).** 한 번의 `EVAL`로 저장된 측정 시각을 읽어 비교하고, 더 늦을 때만 해시 필드 갱신·`EXPIRE`를 함께 실행한다. Spring Data Redis가 쓰는 해시 형식(키 이름, `_class`, 필드 이름, `LocalDateTime` 직렬화 문자열, `heart_rate_result` 인덱스 셋, TTL·phantom 키 여부)을 실제 Redis에서 확인해 **`findByMemberId`가 그대로 읽을 수 있게** 맞춘다. 비교는 문자열 사전순에 기대지 말고 같은 단위의 수치(예: 함께 저장하는 epoch 밀리초 필드)로 한다
2. **회원별 짧은 잠금 + 읽고 비교 후 저장.** 기존 리포지토리를 그대로 쓰지만 잠금 왕복이 늘고 잠금 만료·실패 처리가 필요하다

두 방식 모두 쓰기 경로 두 곳이 같은 메서드를 거치게 한다. ERD 변경 없음.

## 5. 처리 흐름

1. 단건·배치가 최신값을 쓸 때 `HeartRateLatestResultWriter`(이름은 구현 시 결정, 별도 빈) 하나를 호출한다
2. 조건부 쓰기가 갱신 여부(boolean)를 돌려준다. 갱신하지 않은 경우 debug 이하로 memberId만 기록한다(bpm·시각 값 로그 금지)
3. 단건 경로의 응답(`HeartRateStatusResponse`)은 지금처럼 이번 요청의 판정 결과를 담는다. 최신값 갱신 여부와 무관하다

## 6. 예외 / 에러 처리

- Redis 오류는 지금과 같은 방식으로 전파·처리한다(기존 저하 경로 판단 ADR-0040을 바꾸지 않는다)
- 저장된 해시가 깨져 비교할 수 없으면(필드 없음 등) 새 값으로 덮어쓴다(기존 동작과 같음)

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 저장된 값이 없으면 새 값이 저장되고 `findByMemberId`로 같은 필드 값이 읽힌다
- [x] AC2: 더 늦은 측정은 갱신되고 TTL이 다시 24시간으로 걸린다. 더 이른 측정·같은 측정 시각은 갱신되지 않고 기존 값·TTL이 유지된다
- [x] AC3: 실제 Redis에서 t1 처리만 늦춰 t2가 먼저 쓰이도록 한 실험을 100회 반복하면 최종 최신값이 t2가 아닌 횟수가 0회다(단건 경로). 배치 경로(오래된 배치가 늦게 끝남)도 같은 방식으로 0회
- [x] AC4: 같은 회원에 서로 다른 측정 시각으로 동시에 쓰기 N건(예: 100)을 보내면 최종값은 가장 늦은 측정 시각이다(반복 R회 모두)
- [x] AC5: AC3·AC4를 변경 전 코드에서 실행한 회귀 건수를 기록하고 변경 후 수치와 함께 남긴다
- [x] AC6: 이력·위급·판정 저장과 기존 심박·홈 조회 테스트가 통과한다
- [x] AC7: `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다

### 검증 메모 (2026-10-19, Claude Opus / feature-780)

**구현 방식: 4절 1안(Lua 조건부 갱신).** 버리는 Redis 7.2.4에서 변경 전 `repository.save`를 MONITOR로 보면 `DEL` → `HMSET`(`_class`·`heartRate`·`measuredAt`·`memberId`·`status`) → `SADD heart_rate_result <id>` → `EXPIRE 86400`을 따로 보낸다. 키스페이스 이벤트가 꺼져 있어 phantom 키는 없고, `findByMemberId`는 `HGETALL heart_rate_result:<id>` 하나만 보낸다. `measuredAt`은 `LocalDateTime.toString()`이라 초·소수부 자릿수가 값마다 달라 문자열 비교에 쓰지 않았다.

- 쓰기 빈 `HeartRateLatestResultWriter.saveIfNewer`가 Spring Data `RedisConverter`로 해시 필드를 만들고, 비교용 `measuredAtEpochMs`(UTC 고정 오프셋 epoch 밀리초)를 더해 `redis/heart-latest-result-save-if-newer.lua`를 한 번 실행한다. 스크립트는 저장된 `measuredAtEpochMs`가 숫자이고 새 값 이상이면 0을 돌려주고 손대지 않는다. 그 밖에는 `DEL`·`HSET`·`EXPIRE`·`SADD`를 실행하고 1을 돌려준다. 해시 형식은 `repository.save`와 같고 필드 하나만 늘어난다(실측 비교 테스트). `findByMemberId`는 늘어난 필드를 무시하고 그대로 읽는다
- 단건(`saveMeasurement`)·배치(`updateLatestResult`) 모두 `HeartRatePersistenceService` 안에서 이 빈을 호출한다. `HeartRateService`·`HeartRateBatchService`는 바꾸지 않았다
- 2안(회원별 잠금)은 잠금 왕복과 만료·실패 처리가 늘고, 형식 호환을 실측으로 확인할 수 있어 쓰지 않았다
- 밀리초 아래만 다른 두 측정은 같은 시각으로 보고 갱신하지 않는다(배치 `ts_ms`와 같은 단위)
- 변경 전 형식 키(비교 필드 없음)와 비교 필드가 숫자가 아닌 키는 덮어쓴다(6·8절)

**실측(AC3·AC4·AC5).** 같은 Redis·JDK 21·기준 커밋 `7c6915a4`에서 `HeartRateLatestResultOrderingRedisTest`(`HEART_REDIS_PORT` 지정 시에만 실행)를 3회씩 실행했다. AI 판정기는 래치로 순서를 강제하는 대역이고 MySQL 리포지토리는 목이다. Redis 연결은 운영 `RedisConfig`, 리포지토리는 Spring Data 리포지토리 팩토리로 만들었다.

| 시나리오 | 조건 | 변경 전 (3회) | 변경 후 (3회) |
| --- | --- | --- | --- |
| S1 단건: t1 판정을 t2 저장 뒤로 미룸 | R=100 | 회귀 100·100·100 | 회귀 0·0·0 |
| S2 배치: 오래된 배치가 새 배치 뒤에 끝남 | R=100 | 회귀 100·100·100 | 회귀 0·0·0 |
| S3 동시 쓰기: 서로 다른 측정 시각 | N=100, R=20 | 최종값 틀림 20·20·20 | 0·0·0 |

S1·S2의 변경 전 100회는 순서를 강제한 실험이라 확률이 아니라 재현 결과다. S3 변경 전 최종값은 매번 무작위 쓰기였고 최댓값(99초)은 한 번도 남지 않았다.

**AC1·AC2.** `HeartRateLatestResultWriterRedisTest` 7건(실제 Redis)이 통과했다. 빈 키 저장 후 `findByMemberId`로 같은 필드를 읽는지, 늦은 측정의 갱신과 TTL 86400초 재설정, 이른 측정과 같은 측정 시각을 무시하고 TTL(100초로 줄여 둔 값)을 유지하는지 확인했다. 변경 전 형식·깨진 비교 필드의 덮어쓰기와 해시 형식 동일성도 확인했다.

**AC6·AC7.** `:backend:widyu-api:test --tests 'com.widyu.heart.*' --tests 'com.widyu.home.*' --tests 'com.widyu.sensor.*'`는 23클래스 176건 통과(실제 Redis 미지정 시 Redis 테스트 10건 건너뜀, 지정 시 176건 모두 실행·통과)했다. `verify.sh --base 7c6915a4`는 정적 검사 통과, 모듈 전체 테스트 218클래스 1,483건 실패 0(건너뜀 23)이었다.

한계: Redis 실측 테스트는 CI에 Redis가 없어 건너뛴다. 운영 Redis·실제 MySQL·전체 Spring 컨텍스트에서의 실측은 하지 않았다. 배포 중 이전 버전 인스턴스가 함께 돌면 그 인스턴스의 `repository.save`는 여전히 비교 없이 덮어쓴다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. Redis 키 형식을 바꾸면 배포 직후 기존 키(최대 24시간 TTL)와 섞이므로, 기존 형식 키도 읽고 비교할 수 있어야 한다(필드가 없으면 6절대로 덮어쓰기). #779와 같은 서비스 파일을 건드리므로 먼저 머지되는 쪽에 맞춰 다른 쪽을 리베이스한다.

신규 ADR N/A: 최신값의 의미("가장 늦은 측정")를 코드로 강제하는 구현이다.

기각한 대안:
- **inbound 순서 보존(`setPreserveReceiveOrder`)·세션별 직렬 처리:** 같은 세션의 처리 순서만 지키고 느린 AI가 같은 세션의 다음 요청을 막는다. REST·재연결·배치·다른 기기의 교차는 막지 못한다
- **전체 단일 스레드 처리:** 사용자 사이 병목
- **읽고 비교 후 저장(잠금 없음):** 비교와 저장 사이에 다른 쓰기가 끼어든다

## 미결정 사항(Open Questions)

없음. 4절의 구현 방식 선택은 형식 호환을 실제 Redis로 확인한 뒤 구현자가 정하고 근거를 남긴다.

## 10. 참고

- [LLD-0047](LLD-0047-heart-rate-batch-v2.md), [LLD-0089](LLD-0089-heart-resend-concurrent-idempotency.md)(PR #779), 설계 검토 메모(비공개) F절
- Spring 메시지 순서: https://docs.spring.io/spring-framework/reference/web/websocket/stomp/ordered-messages.html
