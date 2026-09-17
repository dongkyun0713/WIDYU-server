# LLD-0009: 실시간 위치 기반 건강 일정 방문인증

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #387 |
| 관련 ADR | ADR-0007 |
| 작성자 | Codex |
| 작성일 | 2026-07-10 |

## 1. 목적 / 배경

건강 일정 방문인증은 시니어가 일정 장소에 실제 도착했는지 확인해야 한다.
클라이언트가 완료 요청에 좌표를 실어 보내는 방식은 위변조와 API 계약 변경 부담이 있으므로, 서버가 기존 실시간 위치 업데이트를 기준으로 방문 여부를 판단한다.
일정 당일 00시부터 일정 시간 30분 후까지 일정 장소 반경 안에 들어오면 자동 완료하고, 인증 가능 시간이 지나면 미완수로 표시한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `HealthSchedule`에 방문인증 가능 시간창 판단과 표시 상태 보정 로직 추가
- 실시간 위치 업데이트 후 `SeniorLocationUpdatedEvent` 발행
- 건강 일정 도메인 이벤트 리스너에서 당일 방문 일정 자동 완료 처리
- 수동 완료 API 호출 시 서버에 저장된 최신 시니어 위치 기준 반경 검증
- 방문인증 허용창이 지난 `UPCOMING` 일정의 조회 표시와 저장 상태 보정
- 시간창/반경/이벤트 리스너 단위 테스트 추가

### Out of scope
- 완료 요청 DTO에 클라이언트 현재 위경도 추가
- 위치 이벤트 영속화, outbox, 메시지 큐 재시도
- 방문 장소별/사용자별 반경 설정
- 포인트 실제 적립과 중복 적립 방지 정책 (LLD-0040에서 구현)

## 3. 인터페이스 / API

기존 HTTP API 계약은 유지한다.

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

동작 변경:
- 완료 요청은 클라이언트 좌표를 받지 않는다.
- 서버에 저장된 최신 `SeniorLocation`이 일정 장소 반경 75m 안인지 검증한다.
- 실시간 위치 업데이트가 들어오면 별도 완료 요청 없이도 자동 완료를 시도한다.

## 4. 데이터 모델

신규 DB 컬럼은 없다.

### widyu-domain
- `HealthSchedule`
  - `COMPLETION_GRACE_MINUTES = 30`
  - `canCompleteAt(now)`: 일정 당일 00시부터 `scheduledAt + 30분`까지 완료 가능
  - `getDisplayProgressStatus(now)`: 저장 상태가 `UPCOMING`이고 `scheduledAt + 30분`을 지나면 `INCOMPLETE`로 표시

### widyu-api
- `SeniorLocationUpdatedEvent(memberId, latitude, longitude)`
  - 실시간 위치 저장 후 발행되는 내부 이벤트
- `SeniorLocation`
  - 기존 Redis 최신 위치를 재사용한다. TTL은 기존 5분을 유지한다.

## 5. 처리 흐름

### 5-1. 실시간 위치 업데이트 자동 완료

1. 클라이언트가 `/app/location/update`로 위치를 전송한다.
2. `RealtimeLocationService.updateAndBroadcast()`가 시니어 본인 권한을 검증한다.
3. 최신 위치를 Redis `SeniorLocation`에 저장한다.
4. `SeniorLocationUpdatedEvent(memberId, latitude, longitude)`를 발행한다.
5. 위치 trail/stay 정보를 갱신하고 보호자 구독 채널로 브로드캐스트한다.
6. `HealthScheduleLocationEventListener`가 이벤트를 비동기로 수신한다.
7. `HealthScheduleProgressService.completeArrivedSchedules()`가 당일 `UPCOMING` 일정을 조회한다.
8. 각 일정별로 `canCompleteAt(now)`와 반경 75m 도착 여부를 확인한다.
9. 조건을 만족한 일정은 `COMPLETED`로 변경한다.

트랜잭션 경계:
- 위치 업데이트 트랜잭션과 건강 일정 자동 완료 트랜잭션은 분리한다.
- 이벤트 리스너는 `@Async`, `@EventListener`, `@Transactional`로 처리한다.

### 5-2. 수동 완료 요청

1. `POST /api/v1/goals/health-schedules/complete` 호출
2. 일정 조회 및 시니어/보호자 접근 권한 검증
3. `canCompleteAt(now)` 검증
4. Redis `SeniorLocation` 최신 위치 조회
5. 최신 위치가 일정 장소 반경 75m 안인지 검증
6. 조건을 만족하면 `COMPLETED` 처리

### 5-3. 미완수 표시와 저장 보정

1. 조회 응답 DTO는 기존처럼 `HealthSchedule.getDisplayProgressStatus()`를 사용한다.
2. 저장 상태가 `UPCOMING`이어도 `scheduledAt + 30분`을 지나면 조회 응답은 `INCOMPLETE`로 내려간다.
3. `HealthScheduleScheduler`가 5분마다 실행된다.
4. `scheduledAt < now - 30분`인 `UPCOMING` 일정을 조회해 `INCOMPLETE`로 저장 보정한다.

## 6. 예외 / 에러 처리

신규 에러 코드는 추가하지 않는다. 기존 `BAD_REQUEST`, `FORBIDDEN`을 사용한다.

| 조건 | 응답 |
| --- | --- |
| 건강 일정을 찾을 수 없음 | `BAD_REQUEST`, "건강 일정을 찾을 수 없습니다." |
| 접근 권한 없음 | `FORBIDDEN`, "해당 일정에 접근할 권한이 없습니다." |
| 방문인증 가능 시간창 밖 | `BAD_REQUEST`, "건강 일정 방문 인증은 당일 00시부터 일정 시간 30분 후까지만 가능합니다." |
| 최신 위치 정보 없음 | `BAD_REQUEST`, "최근 위치 정보가 없습니다." |
| 일정 장소 반경 밖 | `BAD_REQUEST`, "건강 일정 장소 반경 75m 안에서만 방문 인증할 수 있습니다." |

자동 완료 이벤트 처리에서는 조건 불만족 일정을 예외로 보지 않고 건너뛴다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 일정 당일 00시부터 일정 시간 + 30분까지 방문인증 가능창으로 판단한다.
- [x] 실시간 위치가 당일 `UPCOMING` 일정 장소 반경 75m 안에 들어오면 자동으로 `COMPLETED` 처리한다.
- [x] 실시간 위치가 일정 장소 반경 밖이면 자동 완료하지 않는다.
- [x] 수동 완료 API는 클라이언트 좌표를 받지 않고 서버 최신 위치 기준으로 반경을 검증한다.
- [x] 최신 위치 정보가 없으면 수동 완료를 실패 처리한다.
- [x] 저장 상태가 `UPCOMING`이어도 일정 시간 + 30분이 지나면 조회 응답은 `INCOMPLETE`로 표시한다.
- [x] 방문인증 허용창이 지난 `UPCOMING` 일정은 배치로 `INCOMPLETE` 저장 보정한다.
- [x] 위치 핫패스는 건강 일정 서비스를 직접 호출하지 않고 이벤트만 발행한다.
- [x] 이벤트 리스너는 `@Async`, `@EventListener`, `@Transactional`로 처리한다.
- [x] `./gradlew compileJava`가 통과한다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.
- [x] `./gradlew :backend:widyu-domain:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB 마이그레이션 없음.
- HTTP API 요청/응답 필드 변경 없음.
- 실시간 위치 업데이트 후 건강 일정 자동 완료가 비동기로 동작한다.
- `HealthScheduleScheduler` 실행 주기는 매일 자정에서 5분마다로 변경된다.
- `SeniorLocation` TTL 5분 정책은 유지한다. 수동 완료 시 최신 위치가 만료되면 실패한다.

## 9. 미결정 사항 (Open Questions)

- 방문 장소 도착 반경은 현재 75m 상수로 적용한다. 장소별 반경 설정은 후속 정책으로 분리한다.
- 이벤트 처리 실패 재시도/outbox/message queue는 현재 범위에서 제외한다.
- 포인트 실제 적립과 `isReward` 기반 중복 가드는 LLD-0040에서 구현했다.

## 10. 참고

- ADR-0007
- Issue #387
- PR #388
- `RealtimeLocationService`
- `HealthScheduleProgressService`
- `HealthScheduleLocationEventListener`
- `HealthScheduleScheduler`
