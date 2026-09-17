# LLD-0040: 건강 일정 방문 완료 포인트 적립

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> LLD 하나 = PR 하나가 원칙. "하나의 PR에 넣기엔 diff가 너무 많다(파일 15개 이상)"면 LLD를 분리한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #623 |
| 관련 ADR | ADR-0007 |
| 작성자 | Claude Code |
| 작성일 | 2026-09-17 |

## 1. 목적 / 배경

`HealthSchedule`은 생성 시점부터 `rewardPoint=100`, `isReward=false`를 들고 있지만 실제 적립 로직이 없다.
기존 `HealthScheduleRewardService.accumulateHealthSchedulePoints`는 소유자·완료 여부·중복 적립을 전혀 검사하지 않고 `claimReward()`로 플래그만 세우는 스텁이었고, 이를 `POST /api/v1/goals/health-schedules/points`로 노출해 두어 누구든 임의 일정의 보상 플래그를 세울 수 있었다.
LLD-0009가 Out of scope로 미뤘던 "포인트 실제 적립과 중복 적립 방지 정책"을 여기서 확정한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 방문 인증 완료(수동·자동) 시 `rewardPoint`를 시니어 포인트로 1회 적립
- `isReward` 기반 중복 적립 가드와 `PointHistory.operationKey` 기반 멱등 키
- 소유자가 시니어가 아닌 일정의 적립 제외 가드
- 스텁 API `POST /api/v1/goals/health-schedules/points`와 관련 클래스 제거

### Out of scope
- 적립 금액 변경 (`rewardPoint`는 100 유지)
- 일정 종류·장소별 보상 차등 정책
- 완료 취소·되돌리기 시 포인트 환수
- `HealthSchedule` 자체의 낙관적 락(`@Version`) 도입

## 3. 인터페이스 / API

기존 완료 API 계약은 그대로 유지하고, 동작만 확장한다.

```http
POST /api/v1/goals/health-schedules/complete
```

요청:

```json
{
  "healthScheduleId": 1
}
```

응답:

```json
{
  "code": "HLTH_2008",
  "message": "건강 일정이 완료 처리되었습니다.",
  "data": null
}
```

제거되는 API:

```http
POST /api/v1/goals/health-schedules/points   (삭제)
```

동작 변경:
- 완료 처리(`/complete`, 실시간 위치 기반 자동 완료 모두)가 성공하면 최초 1회에 한해 `rewardPoint`가 적립된다.
- 응답 본문은 변경하지 않는다. 적립 결과는 포인트 조회와 건강 일정 상세 조회(`isReward`)로 확인한다.

## 4. 데이터 모델

신규 테이블·컬럼은 없다. 기존 필드를 그대로 사용한다.

### widyu-domain
- `HealthSchedule.rewardPoint` (기본 100), `HealthSchedule.isReward` (기본 false) — 적립 금액과 적립 여부
- `PointHistory.operationKey` — `unique`, 최대 100자. 멱등 키로 사용
- `SeniorProfile.points` / `SeniorProfile.version` — 잔액과 낙관적 락

### widyu-api
- 삭제: `dto/request/HealthSchedulePointGetRequest`
- 적립 시 사용하는 값
  - description: `"건강 일정 방문"`
  - operationKey: `"HEALTH_SCHEDULE_REWARD:" + healthScheduleId`

## 5. 처리 흐름

### 5-1. 공통 적립 로직

`HealthScheduleProgressService.completeAndReward(schedule)` (private)

1. `schedule.complete()`로 `COMPLETED` 전이.
2. `schedule.getIsReward()`가 `true`면 적립 없이 반환 (중복 가드).
3. 일정 소유자 `Member.getType()`이 `SENIOR`가 아니면 로그만 남기고 반환.
4. `SeniorProfileService.addPointsToMember(ownerId, rewardPoint, "건강 일정 방문", "HEALTH_SCHEDULE_REWARD:{scheduleId}")` 호출.
5. `schedule.claimReward()`로 `isReward=true`.

3번 가드 이유: `HealthScheduleService.createHealthScheduleForMe`는 회원 타입을 검사하지 않아 보호자 소유 일정이 존재할 수 있다. 이 경우 `SeniorProfile`이 없어 `SENIOR_PROFILE_NOT_FOUND`가 발생하고, 적립 실패가 완료 처리까지 롤백시키게 된다. 보상은 시니어 대상 기능이므로 완료만 남기고 적립은 건너뛴다.

### 5-2. 수동 완료

1. `POST /api/v1/goals/health-schedules/complete` → `HealthScheduleController` → `HealthScheduleFacadeImpl` (`@Transactional` 없음)
2. `HealthScheduleProgressService.completeSchedule` (`@RetryOnPointConflict` + `@Transactional`)
3. 일정 조회 → 접근 권한 검증 → `canCompleteAt(now)` → 최신 `SeniorLocation` 반경 75m 검증
4. `completeAndReward(schedule)`

트랜잭션 경계: `completeSchedule`이 최외곽 트랜잭션이고 외부 부수효과(PG·FCM)가 없으므로 `@RetryOnPointConflict`를 적용한다 (LLD-0003 §8 적용 조건 충족).

### 5-3. 자동 완료 (실시간 위치)

1. `SeniorLocationUpdatedEvent` → `HealthScheduleLocationEventListener` (`@Async` + `@EventListener` + `@Transactional`)
2. `HealthScheduleProgressService.completeArrivedSchedules(memberId, lat, lng)`
3. 당일 `UPCOMING` 일정 중 `canCompleteAt` + 반경 75m를 만족하는 건마다 `completeAndReward(schedule)`

트랜잭션 경계: 리스너가 최외곽 트랜잭션이므로 서비스 메서드에 `@RetryOnPointConflict`를 붙여도 재시도가 동작하지 않는다. 대신 충돌 시 롤백하고 다음 위치 이벤트가 자연 재시도한다.

### 5-4. 멱등성

- 1차 가드: `isReward` — 같은 트랜잭션/후속 요청에서 중복 적립을 막는다.
- 2차 가드: `PointHistory.operationKey` unique — `isReward` 갱신이 커밋되기 전에 다른 트랜잭션이 같은 일정을 적립하려 하면 DB 제약으로 막힌다.

## 6. 예외 / 에러 처리

신규 에러 코드는 추가하지 않는다.

| 조건 | 처리 |
| --- | --- |
| 이미 적립된 일정 재완료 | 예외 없음. 완료 상태만 유지하고 적립 생략 |
| 소유자가 시니어가 아님 | 예외 없음. 완료만 처리하고 `log.info` 기록 |
| 시니어 프로필 없음 | `SENIOR_PROFILE_NOT_FOUND` — 트랜잭션 롤백 (완료도 취소) |
| 포인트 잔액 낙관적 락 충돌 | 수동 경로는 `@RetryOnPointConflict`로 최대 5회 재시도, 소진 시 409 `POINT_CONCURRENT_UPDATE` |
| `operationKey` 중복 | `DataIntegrityViolationException` → 롤백. 포인트는 1회만 적립된 상태로 유지 |

기존 완료 검증(시간창·반경·권한) 예외는 LLD-0009와 동일하다.

## 7. 인수조건 (Acceptance Criteria)

- [x] UPCOMING 일정을 반경 75m 안에서 수동 완료하면 `COMPLETED`, `isReward=true`, `addPointsToMember(memberId, 100, "건강 일정 방문", "HEALTH_SCHEDULE_REWARD:{id}")` 1회 호출.
- [x] 실시간 위치 이벤트로 자동 완료해도 같은 적립이 1회 일어난다.
- [x] 이미 `isReward=true`인 일정을 다시 완료 처리해도 적립 호출이 없다.
- [x] `member`가 GUARDIAN인 일정을 완료하면 상태는 COMPLETED가 되고 적립 호출은 없다.
- [x] `POST /api/v1/goals/health-schedules/points`와 관련 클래스가 존재하지 않는다.
- [x] Swagger 완료 API 설명에 최초 1회 적립이 반영된다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB 마이그레이션 없음. 신규 테이블·컬럼·ENUM 값 없음.
- **클라이언트 API 변경**: `POST /api/v1/goals/health-schedules/points`를 제거한다. 별도 적립 호출 없이 `/complete` 한 번으로 적립까지 끝나므로, 이 엔드포인트를 호출하던 클라이언트는 호출을 삭제해야 한다. (`HLTH_2006` 응답 코드도 함께 사라진다.)
- 삭제 클래스: `HealthScheduleRewardService`, `HealthScheduleRewardServiceTest`, `HealthSchedulePointGetRequest`, `HealthScheduleFacade(.Impl).accumulateHealthSchedulePoints`, `HealthScheduleDocs`의 포인트 적립 Swagger 메서드.
- 기존 데이터: 이미 `COMPLETED`이면서 `isReward=false`인 일정은 소급 적립하지 않는다. 다시 완료 처리되는 경우에만 적립된다.
- `HealthScheduleProgressService`가 `SeniorProfileService`에 의존하게 되어, 완료 트랜잭션 안에서 `SeniorProfile`·`PointHistory` 쓰기가 발생한다.

## 9. 미결정 사항 (Open Questions)

- 수동 완료와 자동 완료가 동시에 커밋되면(`HealthSchedule`에 `@Version`이 없다) 두 번째는 `PointHistory.operationKey` unique 제약으로 실패한다(수동 경로 500, 자동 경로 로그). 포인트는 1회만 적립되므로 현재는 허용하며, 실제로 관측되면 `findByIdForUpdate`를 도입한다.

## 10. 참고

- LLD-0009 (실시간 위치 기반 건강 일정 방문인증)
- LLD-0003 §8 (포인트 잔액 낙관적 락과 `@RetryOnPointConflict` 적용 조건)
- ADR-0007
- Issue #623
- `HealthScheduleProgressService`, `SeniorProfileService`, `HealthScheduleLocationEventListener`
