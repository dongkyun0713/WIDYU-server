# LLD-0079: 방문 인증 완료 건강일정 수정·삭제 잠금

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 오라클이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11 게이트 승인·구현·검증 완료) |
| Issue | #737 |
| 관련 ADR | ADR-0038, ADR-0039(작성 중) |
| 작성자 | Codex / feature-737 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

현재 `HealthScheduleService.updateHealthSchedule`은 요청의 `progressStatus`를 그대로 반영하여 방문 인증 없이 `COMPLETED`로 만들 수 있다. 완료된 일정도 수정하거나 삭제할 수 있어 방문 인증 기록이 바뀐다. 방문 인증을 마친 일정의 변경을 막고, 인증 전 일정은 당일에도 편집할 수 있게 한다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-api`의 건강일정 수정·삭제·수동 완료 경로, Repository, Swagger, 테스트와 `widyu-domain`의 `ErrorCode` 두 종. 엔티티 필드 변경은 없다.
- 저장된 `progressStatus=COMPLETED` 일정의 `PATCH`·`DELETE`를 HTTP 409로 거부하고 내용·보상·삭제 상태를 보존한다.
- `PATCH.progressStatus=COMPLETED`를 차단한다. 완료는 기존 수동·위치 기반 방문 인증 경로로만 전이한다.
- 수동 완료와 수정·삭제가 경합할 때 같은 행을 잠가 완료된 일정을 뒤늦은 쓰기가 덮지 않도록 한다.
- 거부된 보호자 요청에서 H03/H04 변경 이벤트를 발행하지 않는다. H02~H04 성공 동작은 유지한다.

### Out of scope

- 복약·걷기 잠금, 앱 화면, 완료 취소, 방문 인증 시간·거리·포인트 정책 변경.
- H01 임박 알림의 시간·수신자·전달 방식 변경. 보호자 대상 H01-C-SENIOR 발송 여부는 정본 §4의 별도 미결정 항목이다.
- 위치 기반 자동 완료와 마감 배치의 잠금·재조회 변경.

## 3. 인터페이스 / API

경로·요청 필드·정상 응답 형식은 유지한다.

```http
PATCH  /api/v1/goals/health-schedules/{healthScheduleId}
DELETE /api/v1/goals/health-schedules/{healthScheduleId}
POST   /api/v1/goals/health-schedules/complete
```

`PATCH` 요청의 각 필드는 nullable이며 `null`은 해당 필드를 유지한다. 가능한 enum 값은 `UPCOMING`, `INCOMPLETE`, `COMPLETED`다.

```json
{
  "scheduleName": "정기 건강검진",
  "placeAddress": "서울대학교병원",
  "latitude": 37.5665,
  "longitude": 126.9780,
  "scheduledAt": "2026-10-11T14:30:00",
  "progressStatus": "UPCOMING"
}
```

성공 응답은 기존 `PATCH`의 `HLTH_2002` + `HealthScheduleResponse`, `DELETE`의 `HLTH_2003` + `data:null`이다. 완료된 일정의 두 쓰기 요청은 같은 HTTP 409 응답을 받는다.

```json
{
  "code": "HEALTH_SCHEDULE_4090",
  "message": "방문 인증을 마친 건강 일정은 수정하거나 삭제할 수 없습니다.",
  "data": null,
  "traceId": null
}
```

`traceId`는 추적 컨텍스트가 있으면 문자열이다. 기본 설정에서 `debug`는 직렬화하지 않는다. 미완료 일정의 `PATCH.progressStatus=COMPLETED`는 서비스에서 HTTP 400 `HEALTH_SCHEDULE_4000`(`HEALTH_SCHEDULE_INVALID_PROGRESS_TRANSITION`, 「완료는 방문 인증으로만 처리됩니다.」)으로 거부한다. 바인딩 오류의 `REQ_4000`과 구분한다. `POST /complete` 계약은 그대로다.

## 4. 데이터 모델

- 잠금은 저장된 `HealthSchedule.progressStatus=COMPLETED`로 판단한다. 시간에 따라 계산하는 `getDisplayProgressStatus()`의 `INCOMPLETE`는 잠금 사유가 아니다. `isReward`는 적립 여부이므로 완료 판정에 쓰지 않는다. 보호자 소유 일정은 완료해도 `isReward=false`일 수 있다(LLD-0040).
- `HealthSchedule`은 이미 `@SQLDelete`로 `DELETE`를 `status='DELETED'` 갱신으로 바꾸며 `@Where(status='ACTIVE')`로 숨긴다. 완료 일정은 Repository 삭제 호출 자체를 막으므로 논리 삭제도 물리 삭제도 하지 않는다.
- 테이블·컬럼·인덱스·ENUM 값과 `HealthScheduleUpdateRequest` 형식은 변경하지 않는다. ERD-0001의 기존 구조를 사용한다.

### 상태 전이 표 (`PATCH`)

| 저장 상태 | 요청 `progressStatus` | 결과 |
| --- | --- | --- |
| `UPCOMING` | `null` / `UPCOMING` / `INCOMPLETE` | 허용. `null`은 상태 유지 |
| `INCOMPLETE` | `null` / `UPCOMING` / `INCOMPLETE` | 허용. 인증 전 편집의 기존 동작 유지 |
| `UPCOMING` / `INCOMPLETE` | `COMPLETED` | 거부. 완료 API 또는 위치 기반 자동 인증만 완료 가능 |
| `COMPLETED` | 모든 값(`null` 포함) | HTTP 409. 모든 필드 불변 |

미완료 일정은 당일에도 수정·논리 삭제할 수 있다. 완료 후 `UPCOMING`/`INCOMPLETE`로 되돌리는 수정도 불가하다.

## 5. 처리 흐름

1. `HealthScheduleService.updateHealthSchedule`·`deleteHealthSchedule`은 각각의 `@Transactional` 안에서 `PESSIMISTIC_WRITE`로 일정 행을 읽는다. 기존 소유자·가족 접근 검증을 수행한 후 저장 상태를 검사한다. 존재하지 않거나 권한이 없을 때의 기존 오류는 유지한다.
2. 저장 상태가 `COMPLETED`이면 `HEALTH_SCHEDULE_EDIT_LOCKED`를 던진다. 미완료 일정의 `PATCH.progressStatus=COMPLETED`도 필드 수정 전에 거부한다. 두 검사는 `HealthSchedule.update()` 또는 Repository `delete()`와 `publishGuardianChange()`보다 앞선다.
3. 같은 Repository 잠금 조회를 수동 완료 `HealthScheduleProgressService.completeSchedule`에도 적용한다. 먼저 완료가 커밋되면 대기한 수정·삭제는 최신 상태를 읽어 409를 반환한다. 먼저 수정이 커밋되면 완료는 바뀐 일정으로 인증 조건을 다시 판단한다.
4. 위치 기반 자동 완료 `completeArrivedSchedules`와 마감 배치 `markOverdueSchedulesAsIncomplete`는 변경하지 않는다. 두 경로의 기존 `UPCOMING` 후보 조건은 유지한다. 이 경로들과 수정·삭제의 동시 실행에 남는 위험은 8절에 기록한다.
5. 검증에 통과한 요청만 기존 수정·논리 삭제와 보호자 H03/H04 이벤트를 수행한다. 거부 예외는 트랜잭션을 롤백하며 그 요청으로 인한 H03/H04 푸시·알림센터 행은 0건이다. 독립적으로 예정된 H01 발송은 별개다.

## 6. 예외 / 에러 처리

| 조건 | HTTP / 코드 | 효과 |
| --- | --- | --- |
| 인가된 사용자의 `COMPLETED` 일정 `PATCH` 또는 `DELETE` | 409 / 신규 `HEALTH_SCHEDULE_4090` (`HEALTH_SCHEDULE_EDIT_LOCKED`) | 모든 필드·`status` 불변, 변경 이벤트 없음 |
| 미완료 일정에 `PATCH.progressStatus=COMPLETED` | 400 / 신규 `HEALTH_SCHEDULE_4000` (`HEALTH_SCHEDULE_INVALID_PROGRESS_TRANSITION`) | 다른 요청 필드도 불변, 변경 이벤트 없음 |
| 일정 없음 / 접근 권한 없음 | 기존 400 / 403 등 | 기존 응답 유지. 권한 검사 전에 완료 여부를 노출하지 않음 |

`HealthScheduleDocs`의 `PATCH`·`DELETE`에 409를, `PATCH`에 400을 문서화한다. 신규 `ErrorCode`는 위 400·409 두 종이다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 방문 인증으로 `COMPLETED`가 된 일정의 `PATCH`와 `DELETE`는 각각 HTTP 409 `HEALTH_SCHEDULE_4090`을 반환한다. 이름·장소·좌표·시각·진행 상태·보상 플래그·삭제 상태가 그대로이며 삭제 Repository 호출도 없다.
- [x] AC2. 인증 전 `UPCOMING` 또는 `INCOMPLETE` 일정은 당일에도 기존 권한으로 수정·논리 삭제할 수 있다. 표시용 `INCOMPLETE`만으로 잠기지 않는다.
- [x] AC3. 미완료 일정에 `PATCH.progressStatus=COMPLETED`와 다른 수정 필드를 함께 보내면 HTTP 400 `HEALTH_SCHEDULE_4000`이고 모두 불변이다. 완료는 기존 수동·자동 인증 경로에서만 가능하다.
- [x] AC4. 거부된 보호자 `PATCH`·`DELETE`는 `GuardianGoalChangedEvent`를 발행하지 않으며 그 요청에서 H03/H04 푸시·알림센터 행은 0건이다. 성공한 보호자 H02~H04 동작은 유지한다.
- [x] AC5. **수동** 완료와 수정·삭제 경합에서 수동 완료가 먼저 커밋되면 뒤따른 쓰기는 409이고 완료 행이 유지된다. 수정·삭제가 먼저 커밋되면 수동 완료 경로는 최신 행과 인증 조건을 재검사한다.
- [x] AC6. `HealthScheduleDocs`에 주요 오류 응답이 보이고 `bash scripts/harness/verify.sh`가 통과한다. 테스트는 한글 메서드명·BDDMockito·상태 검증을 사용하고 삭제·이벤트 부수효과에만 `verify`한다.

## 8. 영향 범위 / 마이그레이션

- `widyu-api`: `HealthScheduleService`, `HealthScheduleProgressService`, `HealthScheduleRepository`, `HealthScheduleDocs`와 관련 단위·H2 경합 테스트. Controller·Facade 경로 및 정상 응답은 유지한다.
- `widyu-domain`: `ErrorCode`에 HTTP 400·409 두 종만 추가한다. 엔티티·ERD·Q 클래스 변경은 없다.
- 기존 완료 일정은 저장된 `COMPLETED` 값만으로 잠긴다. 이미 `status=DELETED`인 행은 소급 복구하지 않는다. 물리 삭제 호출이나 데이터 마이그레이션은 없다.
- 위치 기반 자동 완료와 마감 배치는 기존처럼 `UPCOMING` 후보를 읽어 갱신하며 행 잠금을 공유하지 않는다. 편집·삭제와 동시 실행 시 오래된 후보 상태를 사용할 수 있는 잔여 경합 위험이 있다. 이번 범위에서는 세 경로(수정·삭제·수동 완료)의 직렬화만 보장한다.

## 9. 미결정 사항 (Open Questions)

이번 구현 범위의 미결정 사항은 없다. 완료 전이 요청의 오류 코드는 2026-10-11 게이트에서 `HEALTH_SCHEDULE_4000`으로 확정했다. 정본 §4의 H01-C-SENIOR 발송 여부와 복약 기기 알람·B12 추천은 별도 작업의 미결정 사항이며 이 LLD에서 정책을 확정하지 않는다.

## 10. 참고

- 정본(저장소 밖, Git 제외): `/Users/dongkyun/Documents/WIDYU-server/apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1 A12, §4 「함께 정할 것」.
- 분석: `/Users/dongkyun/Documents/WIDYU-server/apiDocs/notification/BE-GAP-2026-10-05.md` §3 #11.
- 전체 계획: `/Users/dongkyun/Documents/WIDYU-server/apiDocs/notification/BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11 W22.
- [LLD-0009](LLD-0009-location-based-health-schedule-verification.md), [LLD-0040](LLD-0040-health-schedule-visit-reward.md), [ERD-0001](../erd/ERD-0001-initial-domain.md), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md), ADR-0039(작성 중).
