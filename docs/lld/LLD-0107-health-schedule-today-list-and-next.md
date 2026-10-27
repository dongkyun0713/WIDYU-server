# LLD-0107: 홈·목표 화면 건강 일정 컴포넌트의 오늘 일정 목록과 다음 일정

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> LLD 하나 = PR 하나가 원칙.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #877 |
| 관련 ADR | - |
| 작성자 | Claude (Fable 5.1) |
| 작성일 | 2026-10-23 |

## 1. 목적 / 배경

위듀케어 목표홈·건강일정홈, 위듀 목표·홈의 건강 일정 컴포넌트에 대한 대표님 결정을 서버 응답에 반영한다. 오늘 날짜를 보고 있다면 예정 시각이 지난 일정도 계속 보여주고, 현재 시각 이후의 다음 일정이 없으면 다음 일정 자리를 일정 추가 요청 컴포넌트로 대체한다. 지금 네 화면의 홈·목표 API는 `scheduledAt > now`인 가장 가까운 일정 하나만 내려주므로 오늘 지난 일정은 응답에서 사라지고, 클라이언트가 "오늘 일정"과 "다음 일정"을 구분해 그릴 재료가 없다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 아래 4개 응답에 오늘 일정 목록 `todaySchedules`를 추가한다. 지난 일정·완료된 일정을 모두 포함하고 항목마다 진행 상태와 예정 시각 경과 여부를 담는다.
  - `GET /api/v1/goals/home/senior` → `SeniorGoalHomeResponse` (위듀 목표)
  - `GET /api/v1/goals/home/guardian?memberId=` → `GuardianGoalHomeResponse` (위듀케어 목표홈)
  - `GET /api/v1/home/senior/cards` → `SeniorHomeCardsResponse` (위듀 홈)
  - `GET /api/v1/home/guardian/cards?...` → `GuardianHomeCardsResponse` (위듀케어 홈)
- 기존 다음 일정 필드(`hospital`, `healthSchedule`)는 "현재 시각 이후 첫 일정"으로 유지하고, 없으면 `null`이다. `null`이 곧 "일정 추가 요청 컴포넌트" 신호임을 Swagger에 명시한다.
- 위듀케어 건강일정홈이 쓰는 일정 목록 API(`GET /api/v1/goals/health-schedules/daily/seniors`, `/daily/guardians/{memberId}`, `/weekly`)는 이미 당일 00:00부터 전체를 `progressStatus`와 함께 반환하므로 동작을 바꾸지 않는다. Swagger 설명에 "예정 시각이 지난 일정도 포함"을 보강한다.
- 4개 서비스의 단위 테스트.

### Out of scope
- 클라이언트의 빈 상태 문구·일정 추가 요청 컴포넌트 자체(FE).
- 지난 일정을 오늘 이후에도 유지하는 기준(결정에 없음, "오늘 날짜를 보는 동안"으로 한정).
- 완료 상태별 표시 예외(결정에 없음, 모든 상태를 포함).
- `INCOMPLETE` 전환 시점(5분 스케줄러, LLD-0040)·알림·보상 로직.
- 다음 일정 조회 범위 통일(목표홈·시니어 홈은 1개월, 보호자 홈 카드는 무제한 — 현행 유지).

## 3. 인터페이스 / API

```http
GET /api/v1/goals/home/senior
GET /api/v1/goals/home/guardian?memberId={seniorId}
GET /api/v1/home/senior/cards
GET /api/v1/home/guardian/cards
```

응답에 `todaySchedules` 배열을 추가한다(항상 존재, 없으면 빈 배열). 다음 일정 필드는 기존 그대로다.

```json
{
  "isSuccess": true,
  "code": "...",
  "message": "...",
  "result": {
    "hospital": null,
    "todaySchedules": [
      {
        "healthScheduleId": 12,
        "scheduleName": "내과 정기검진",
        "scheduledAt": "2026-10-23T09:30:00",
        "placeAddress": "서울특별시 성북구 고려대로 73",
        "latitude": 37.5894,
        "longitude": 127.0327,
        "progressStatus": "INCOMPLETE",
        "elapsed": true
      },
      {
        "healthScheduleId": 15,
        "scheduleName": "물리치료",
        "scheduledAt": "2026-10-23T11:00:00",
        "placeAddress": "...",
        "latitude": 37.58,
        "longitude": 127.03,
        "progressStatus": "COMPLETED",
        "elapsed": true
      }
    ]
  }
}
```

- `todaySchedules`: 오늘(서버 Asia/Seoul 기준 `[오늘 00:00, 내일 00:00)`)에 예정된 ACTIVE 일정 전체, `scheduledAt` 오름차순. 지난 일정·완료된 일정 포함.
- `elapsed` (boolean): `scheduledAt`이 응답 생성 시각(`now`) 이하이면 `true`. `progressStatus`와 독립이며, `INCOMPLETE` 전환 전(5분 지연 구간)에도 경과를 즉시 표시할 수 있게 둔다.
- `progressStatus`: `UPCOMING` / `INCOMPLETE` / `COMPLETED` (기존 enum). 일정 상세 조회(`/daily`)와 같은 값을 주도록 표시 상태 `HealthSchedule.getDisplayProgressStatus(now)`를 쓴다. 저장 상태가 `UPCOMING`이어도 방문 인증 가능 시각(예정 시각 + 30분)이 지났으면 `INCOMPLETE`다.
- `hospital`(목표홈)·`healthSchedule`(홈 카드): 변경 없음. `scheduledAt > now`인 첫 일정, 없으면 `null`. 클라이언트는 `null`일 때 일정 추가 요청 컴포넌트를 그린다.
- 홈 카드(`SeniorHomeCardsResponse`, `GuardianHomeCardsResponse`)의 `todaySchedules`도 같은 항목 형식이다.

## 4. 데이터 모델

DB 변경 없음.

- 신규 DTO(widyu-api): `com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem`
  - 필드: `healthScheduleId`, `scheduleName`, `scheduledAt`(`yyyy-MM-dd'T'HH:mm:ss`), `placeAddress`, `latitude`, `longitude`, `progressStatus`, `elapsed`
  - 팩토리: `static TodayHealthScheduleItem from(HealthSchedule schedule, LocalDateTime now)` — `elapsed = !schedule.getScheduledAt().isAfter(now)`, `progressStatus = schedule.getDisplayProgressStatus(now)`
  - 목록 팩토리: `static List<TodayHealthScheduleItem> listFrom(List<HealthSchedule> schedules, LocalDateTime now)` — `now`와 같은 날짜의 일정만 남기고 `scheduledAt` 오름차순으로 정렬한다. 오늘 00:00~1개월 범위를 조회한 결과를 그대로 넘겨도 오늘 일정만 담긴다.
- 4개 응답 record에 `List<TodayHealthScheduleItem> todaySchedules` 필드와 `of(...)`/생성 팩토리 인자를 추가한다. 서비스에서 `new` 하지 않는다(규칙 2, 중첩 record 포함).

## 5. 처리 흐름

1. 인증(`memberUtil.getCurrentMember()`)과 가족 접근 검증은 기존 흐름 그대로다(`@ValidateFamilyAccess`, `getMember(memberId)`).
2. `now = LocalDateTime.now()`, `todayStart = now.toLocalDate().atStartOfDay()`, `tomorrowStart = todayStart.plusDays(1)`을 한 번 계산해 다음 일정 선정과 오늘 목록에 같은 `now`를 쓴다. 공개 메서드는 `LocalDateTime.now()`를 구해 같은 이름의 패키지 접근 메서드(`getSeniorGoalHome(now)`, `getGuardianGoalHome(memberId, now)`, `getHomeCards(now)`, `getHomeCards(memberId, now)`)에 넘긴다. 단위 테스트는 이 메서드에 고정 시각을 넣는다(기존 `findNextSchedule(schedules, LocalTime now)`와 같은 방식).
3. 오늘 목록 → `TodayHealthScheduleItem.listFrom(schedules, now)`. ACTIVE 조건은 엔티티 `@Where`가 건다. 정렬은 `listFrom`이 한다(`findByMemberIdAndDate`에는 `ORDER BY`가 없다).
   - 목표홈(시니어·보호자)·시니어 홈: 기존 `findByMemberIdAndWeek(memberId, now, now+1개월)` 조회의 시작 경계를 `todayStart`로 넓혀 한 번의 조회로 오늘 목록과 다음 일정(`scheduledAt > now` 중 최소)을 모두 뽑는다. 쿼리 수 변화 없음.
   - 보호자 홈: 다음 일정 조회(`findFirstBy...After(now)`)가 기간 제한 없는 단건 조회라 합칠 수 없으므로 `findByMemberIdAndDate(memberId, todayStart, tomorrowStart)`를 1개 추가한다.
   - 어느 쪽이든 엔드포인트당 추가 쿼리는 최대 1개다(LLD-0024의 조회 파이프라인 원칙).
4. 다음 일정: 기존 로직 유지(`scheduledAt.isAfter(now)` 중 최소). 보호자 홈 카드의 `findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(now)`도 그대로.
5. 응답 조립은 기존 `of(...)` 팩토리에 `todaySchedules`를 추가해 넘긴다. 트랜잭션 경계(`@Transactional(readOnly = true)`)·Facade·이벤트는 바뀌지 않는다.

## 6. 예외 / 에러 처리

새 에러 코드 없음. 기존 인증·가족 접근 예외 그대로.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 오늘 09:30 일정(미완료)이 있고 현재 10:00이면 네 응답의 `todaySchedules`에 그 일정이 `elapsed=true`로 포함된다.
- [x] AC2. 오늘 완료된(`COMPLETED`) 일정도 `todaySchedules`에 `progressStatus=COMPLETED`, `elapsed=true`로 포함된다.
- [x] AC3. 현재 시각 이후 일정이 없으면 `hospital`/`healthSchedule`는 `null`이고 `todaySchedules`는 그대로 채워진다.
- [x] AC4. 오늘 11:00 일정과 내일 일정이 있고 현재 10:00이면 다음 일정은 오늘 11:00이고 `todaySchedules`에는 오늘 일정만 있다(내일 일정 제외).
- [x] AC5. 오늘 일정이 없으면 `todaySchedules`는 빈 배열이다(`null` 아님).
- [x] AC6. `todaySchedules`는 `scheduledAt` 오름차순이다.
- [x] AC7. 기존 필드의 이름·타입·`null` 규칙이 바뀌지 않는다(기존 테스트 통과).
- [x] AC8. 엔드포인트당 건강 일정 조회 쿼리가 최대 1개 늘어난다(서비스 단위 테스트에서 리포지토리 호출 횟수로 검증). 목표홈 2개·시니어 홈은 1개 그대로, 보호자 홈은 1개 → 2개.
- [x] Swagger(각 Docs 인터페이스·DTO `@Schema`)에 `todaySchedules`, `elapsed`, 다음 일정 `null` 의미가 반영된다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다. (2026-10-23 `bash scripts/harness/verify.sh` 안에서 실행, 1570건 중 실패 0·건너뜀 31)
- 오늘 00:00 포함·내일 00:00 제외·삭제 일정 제외 경계는 H2 통합 테스트 `TodayHealthScheduleQueryTest`로 확인했다.

## 8. 영향 범위 / 마이그레이션

- 응답 필드 추가(additive). 기존 클라이언트는 모르는 필드를 무시하므로 깨지지 않는다.
- 변경 파일: `GoalHomeService`, `SeniorHomeService`, `GuardianHomeService`, 응답 record 4개, 신규 `TodayHealthScheduleItem`, Docs 인터페이스 3개(`GoalHomeDocs`, `HomeDocs`, `HealthScheduleDocs`), 테스트(`GoalHomeServiceTest` 보강, 신규 `SeniorHomeServiceTodayScheduleTest`·`GuardianHomeServiceTodayScheduleTest`·`TodayHealthScheduleQueryTest`(H2)·`HealthScheduleFixture`).
- DB·ENUM·배포 절차 영향 없음.
- 같은 서비스 파일을 수정하는 열린 PR: #871(`GoalHomeService` 7곳 `of()` 전환, `GuardianHomeService` 1곳). 충돌을 줄이려면 #871 머지 뒤 rebase하거나, 같은 줄을 건드리지 않게 한다.

## 9. 미결정 사항 (Open Questions)

- [ ] 클라이언트가 홈·목표 응답의 `todaySchedules`를 쓸지, 기존 일정 목록 API(`/daily`)를 따로 호출할지. 서버는 두 경로 모두 같은 값을 주므로 구현을 막지 않는다.
- [ ] 지난 일정 유지 기간과 완료 상태별 예외는 결정에 없어 "오늘 날짜를 보는 동안, 모든 상태 포함"으로 구현한다. 다른 기준이 나오면 후속 LLD로 다룬다.

## 10. 참고

- 대표님의 결정(2026-10-23 전달): "오늘 날짜를 보고 있다면 건강 일정의 예정 시각이 이미 지났더라도 위듀케어 목표홈, 위듀케어 건강일정홈, 위듀 목표, 위듀 홈의 건강 일정 컴포넌트에서 해당 일정을 계속 보여줍니다. 현재 시각 이후의 다음 일정이 없다면 다음 일정을 보여주는 자리를 일정 추가를 요청하는 컴포넌트로 대체합니다." 근거: hemlo 님이 결정 요청 DM 스레드에서 답변. 완료 상태별 예외나 유지 기간 기준은 포함되지 않음.
- Issue #877
- [LLD-0024](LLD-0024-goal-home-query-pipeline.md) 목표홈 조회 파이프라인, [LLD-0040](LLD-0040-health-schedule-visit-reward.md) 진행 상태·완료 보상, [LLD-0079](LLD-0079-health-schedule-edit-lock.md)
- 코드 기준: `GoalHomeService.getUpcomingHospitalInfo`/`getGuardianHospitalInfo`, `SeniorHomeService.getHealthScheduleInfo`, `GuardianHomeService.getHealthScheduleInfo`, `HealthScheduleRepository.findByMemberIdAndDate`/`findByMemberIdAndWeek`
