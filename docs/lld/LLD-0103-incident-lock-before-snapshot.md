# LLD-0103: 잠금 전에 고정된 읽기 스냅샷 때문에 동시 요청이 먼저 커밋된 행을 못 보는 결함을 막는다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (코드 리뷰에서 확정된 결함, 사용자 구현·PR 지시, 2026-10-22) |
| Issue | #836 |
| 관련 ADR | [ADR-0035](../adr/ADR-0035-alert-decision-and-incident.md), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) |
| 작성자 | Claude Opus |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

MySQL InnoDB의 기본 격리 수준 REPEATABLE READ는 트랜잭션의 **첫 일반(잠금 없는) SELECT**에서 읽기 스냅샷을 고정한다. 그 뒤 `SELECT ... FOR UPDATE`로 행 잠금을 기다렸다 얻어도, 이어지는 일반 SELECT는 여전히 처음 스냅샷을 읽는다. 잠금 대기 중에 다른 트랜잭션이 커밋한 행은 보이지 않는다. 잠금 읽기와 UPDATE는 최신 커밋 값을 읽으므로 이 문제가 없다.

이 성질 때문에 세 곳이 동시 요청에서 틀린 결과를 낸다.

1. **동시 위급 배치가 사건을 2개 연다(HIGH).** 배치 위급 판정이 커밋되면 `HeartRateEmergencyNotificationService.handleHeartRateEmergency`(AFTER_COMMIT, `REQUIRES_NEW`)가 사건을 연다. 이 트랜잭션의 첫 쿼리가 `decisionRecordRepository.findByDecisionId`(일반 SELECT)라 스냅샷이 여기서 고정된다. 이어지는 `IncidentService.attachOrOpen`의 회원 행 잠금(`memberRepository.findByIdForUpdate`)이 앞 트랜잭션의 커밋을 기다린 뒤에도 `incidentRepository.findByDecisionId`·`findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNull…`은 고정된 스냅샷을 읽어 앞 트랜잭션이 방금 연 사건을 못 본다. 같은 시니어의 위급 배치 2건이 겹치면 사건이 둘 열리고 본인확인 푸시와 보호자 최초 알림이 두 번 나간다. 단건 경로(`decisionId` 없음)는 잠금이 첫 쿼리라 안전하다. `DevIncidentService`는 같은 위험을 주석으로 설명하고 잠금을 첫 쿼리로 둔다.
2. **확인 화면 수신 응답이 낡은 상태를 돌려준다(MEDIUM).** `IncidentService.markPromptDisplayed`는 `findOwned`(일반 SELECT) → 조건부 UPDATE → `findOwned` 재조회 순서다. UPDATE가 0건이면 다른 트랜잭션이 먼저 응답·`ESCALATED`를 커밋한 것인데, 재조회는 첫 SELECT의 스냅샷이라 갱신 전 `CHECKING`을 돌려준다. 앱은 이미 끝난 사건을 아직 기다리는 중으로 본다. 같은 클래스의 `applyRespond`는 같은 이유로 갱신 0건 뒤 `findByIdForUpdate`로 다시 읽는다.
3. **처음 보는 시계 매핑의 동시 등록이 실패한다(MEDIUM).** `ClockMappingService.register`는 `findByClockMappingId`(일반 SELECT, 없음) → `ClockMappingInsertService.insert`(`REQUIRES_NEW`) → UK 충돌이면 같은 트랜잭션에서 재조회한다. 재조회가 충돌 전 스냅샷이라 상대가 커밋한 행이 보이지 않고 원래 `DataIntegrityViolationException`으로 실패한다. LLD-0044 5.1 2단계는 "호출 트랜잭션은 새 persistence context로 다시 조회"하라고 정했지만 코드는 같은 트랜잭션에서 읽었다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 위급 리스너: 회원 행 잠금을 트랜잭션의 첫 쿼리로 둔다
- `markPromptDisplayed`: 조건부 UPDATE 뒤 재조회를 잠금 읽기(`incidentRepository.findByIdForUpdate`)로 바꾼다
- 시계 매핑: UK 충돌 뒤 재조회를 `ClockMappingInsertService.findCommitted`(`REQUIRES_NEW`, 읽기 전용)로 옮긴다
- `PromptDisplayedRequest.of` 정적 팩토리(테스트가 DTO 생성 규칙을 지키기 위한 추가, 동작 변경 없음)
- 단위 테스트, 실제 MySQL 동시성 테스트(환경 변수가 있을 때만 실행)

### Out of scope
- 격리 수준 변경(전역 READ COMMITTED 전환 등)
- 낙상 사건 열기(`openForAlert(decision, FALL_SUSPECTED)`): 판정 하나에 사건 하나이고 `decision_id` UK가 중복을 막는다
- 잠금 전에 일반 SELECT를 하는 다른 경로 전수 조사(5.4에 점검한 범위만 적는다)

## 3. 인터페이스 / API

HTTP·STOMP 계약 변경 없음. `POST /api/v1/incidents/{id}/prompt-displayed` 응답 필드는 그대로이고, 갱신 0건일 때 값이 최신 커밋 기준이 된다.

## 4. 데이터 모델

변경 없음.

## 5. 처리 흐름

### 5.1 위급 리스너 (`HeartRateEmergencyNotificationService.handleHeartRateEmergency`, `REQUIRES_NEW`)

1. `memberRepository.findByIdForUpdate(event.memberId())` — 첫 쿼리. 없으면 `MEMBER_NOT_FOUND`(기존 `attachOrOpen`과 같은 예외)
2. `decisionId`가 있으면 판정 기록을 읽는다. 잠금을 얻은 뒤의 첫 일반 SELECT라 스냅샷이 여기서 잡히고, 앞서 같은 회원 잠금을 쥐었던 트랜잭션의 사건이 보인다
3. `incidentService.openForAlert(...)` → `attachOrOpen`이 다시 `findByIdForUpdate`를 부른다. 같은 트랜잭션이 이미 쥔 잠금이라 기다리지 않는다
4. 이후 흐름(최초 알림 여부)은 그대로다

`IncidentService`에 "잠금 → 판정 기록 조회 → 열기" 메서드를 새로 두는 방식도 있었다. 판정 기록 리포지토리 의존이 `IncidentService`로 옮겨 가고 기존 `openForAlert(DecisionRecord, kind)`(낙상과 공용)를 나눠야 해서, 트랜잭션을 여는 리스너가 첫 쿼리를 책임지는 `DevIncidentService`와 같은 구조를 택했다.

### 5.2 확인 화면 수신 (`IncidentService.markPromptDisplayed`)

1. `findOwned`(본인 사건 확인, 일반 SELECT)
2. 조건부 UPDATE(`markPromptDisplayed`, `clearAutomatically`)
3. `incidentRepository.findByIdForUpdate(owned.getId())`로 다시 읽어 응답을 만든다. 갱신 1건이면 이미 이 행의 잠금을 쥐고 있어 대기가 없고, 0건이면 먼저 커밋된 응답·상태를 읽는다

### 5.3 시계 매핑 (`ClockMappingService.register`)

1. 기존 조회 → 없으면 `insert`(`REQUIRES_NEW`)
2. UK 충돌이면 `clockMappingInsertService.findCommitted(clockMappingId)` — 새 트랜잭션·새 persistence context에서 읽는다. 없으면 원래 예외를 던진다(기존)
3. 대조·관측 범위 넓히기(UPDATE)는 그대로 호출 트랜잭션에서 한다

재조회만 새 트랜잭션으로 옮겼다. `register` 전체를 READ COMMITTED로 바꾸는 한 줄 대안도 있었지만, LLD-0044가 정한 "새 persistence context로 재조회"를 그대로 따르고 다른 쿼리의 격리 수준은 건드리지 않았다.

### 5.4 점검한 범위

| 경로 | 첫 쿼리 | 판단 |
| --- | --- | --- |
| 단건 위급 리스너(`decisionId` 없음) | `attachOrOpen`의 회원 잠금 | 안전(변경 전에도) |
| `openHelpRequest` | `requireActiveSenior`의 회원 잠금 | 안전 |
| `cancelHelpRequest` | READ COMMITTED 트랜잭션 | 안전 |
| `DevIncidentService.reopenHeartEmergency` | 회원 잠금 | 안전 |
| `applyRespond` 갱신 0건 | `findByIdForUpdate` 재조회 | 안전 |

## 6. 예외 / 에러 처리

새 에러 코드 없음. 리스너의 회원 없음은 기존 `attachOrOpen`이 던지던 `MEMBER_NOT_FOUND`를 한 단계 먼저 던질 뿐이다. 리스너는 AFTER_COMMIT이라 예외가 심박 저장을 되돌리지 않는다(기존). `markPromptDisplayed`에서 잠금 재조회가 비면 `INCIDENT_NOT_FOUND`(사건 삭제는 없으므로 실제로는 나지 않는다).

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 위급 리스너의 첫 쿼리가 회원 행 잠금이고, 판정 기록 조회와 사건 열기가 그 뒤에 온다(단위 테스트, 호출 순서)
- [ ] AC2: 실제 MySQL에서 같은 시니어의 위급 판정 두 건을 겹치게 처리하면 사건 1개, 본인확인 푸시 1회다(`IncidentConcurrentOpenMySqlTest`, **로컬 MySQL이 없어 미실행**)
- [x] AC3: `markPromptDisplayed`가 갱신 0건이면 잠금 읽기로 다시 읽은 현재 상태를 돌려준다(단위 테스트)
- [x] AC4: 시계 매핑 UK 충돌 뒤 재조회가 `findCommitted`(새 트랜잭션)를 거치고, 행이 있으면 대조 후 통과, 없으면 원래 예외를 던진다(단위 테스트)
- [x] AC5: 기존 사건·심박·센서 테스트가 통과한다
- [x] AC6: 변경 Java 파일의 `validate-java-rules.sh`와 `:backend:widyu-api:compileJava`가 통과한다

### 검증 메모 (2026-10-22, Claude Opus / feature-836)

- AC1: `HeartRateEmergencyNotificationServiceTest` "판정 경로 위급을 받으면 회원 행을 잠근 뒤에 판정 기록을 읽는다"(InOrder)
- AC2: 테스트는 첫 리스너를 본인확인 푸시 대역 안에 붙잡고, 둘째 리스너가 `information_schema.innodb_trx`에서 `LOCK WAIT`이 된 것을 본 뒤 풀어 준다. 수정 전 리스너는 판정 기록 조회로 스냅샷을 고정한 뒤 잠금을 기다리므로 사건 2개가 기대된다. MySQL이 없어 실행하지 못했다. 같은 클래스를 H2에서 순차 호출로 바꾼 임시 사본으로 스프링 구성(빈 주입·사건 1개·푸시 1회)만 확인하고 사본은 지웠다. H2는 이 스냅샷 동작을 재현하지 못한다
- AC3: `IncidentServiceTest` "다른 트랜잭션이 먼저 답해 확인 화면 수신 기록이 0건이면 잠금 읽기로 지금 상태를 돌려준다"
- AC4: `ClockMappingServiceTest` 경합 2건을 `findCommitted` 기준으로 고쳤다
- 실행 명령과 결과는 PR 본문 테스트 절에 남긴다

## 8. 영향 범위 / 마이그레이션

DB 변경 없음. 배포 순서 제약 없음.

- 위급 리스너가 판정 기록을 읽기 전에 회원 행 잠금을 잡는다. 잠금을 쥐는 시간은 전과 같다(전에도 `attachOrOpen`이 같은 트랜잭션에서 잡아 커밋까지 쥐었다)
- 시계 매핑 경합 시 재조회가 커넥션 하나를 더 쓴다. 경합 때만이고 `insert`도 이미 같은 방식이다
- 신규 ADR N/A: 기존 직렬화 결정(회원 행 잠금, LLD-0054·0070)을 지키도록 쿼리 순서만 바로잡는다

## 9. 미결정 사항 (Open Questions)

없음. AC2의 실제 MySQL 실행은 미결정이 아니라 미실행 검증이다(`INCIDENT_MYSQL_URL` 지정 후 실행).

## 10. 참고

- [LLD-0054](LLD-0054-incident.md) 사건, [LLD-0070](LLD-0070-self-check-first-heart.md) 본인확인 우선, [LLD-0085](LLD-0085-self-check-prompt-displayed.md) 확인 화면 수신, [LLD-0044](LLD-0044-clock-mapping-registry.md) 시계 매핑
- MySQL 8.0 Reference Manual, 17.7.2.3 Consistent Nonlocking Reads: 같은 트랜잭션의 일관 읽기는 첫 읽기가 만든 스냅샷을 쓴다. 잠금 읽기는 최신 상태를 읽는다
