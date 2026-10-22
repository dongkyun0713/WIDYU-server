# LLD-0105: 건강 일정 1시간 전 알림이 모든 일정을 한 번씩 조회하게 한다

| 항목 | 값 |
| --- | --- |
| 상태 | Review (코드 리뷰 지적 기반 구현, 2026-10-22) |
| Issue | #833 |
| 관련 ADR | ADR-0028 |
| 작성자 | Claude / feature-833 |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

`HealthScheduleNotificationListener.sendHealthScheduleReminder`는 매시 정각(`0 0 * * * *`)에 실행되며 `[now+1h, now+1h10m)`에 시작하는 `UPCOMING` 일정에 H01을 보낸다([LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md)). 한 시간에 10분 창 하나만 보므로 분 10~59 일정은 어느 회차에도 들지 않는다. 정각 일정도 `now`의 밀리초 때문에 `scheduledAt >= 15:00:00.0xx`가 거짓이라 빠진다. 실제로 알림을 받는 일정은 분 01~09 시작뿐이다.

스케줄러 전체가 한 트랜잭션이고 `FcmService.sendMessageToUser`가 그 트랜잭션에 참여한다. 건별 try/catch가 있어도 한 일정의 enqueue가 실패하면 트랜잭션이 롤백 전용이 되어 그 회차의 다른 일정 알림도 커밋되지 않는다.

같은 "한 건 때문에 나머지 리마인더가 사라지는" 문제로, 복약 스케줄러의 보호자 미인증 알림(M04)은 방장 탈퇴로 가족이 해제된(`SeniorProfile.family = null`) 시니어에서 NPE로 반복문이 끊긴다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- H01 스케줄을 10분 주기로 바꾸고 조회 창을 실행 시각의 10분 내림 기준으로 계산한다.
- H01 스케줄러의 바깥 트랜잭션을 없애고, 트랜잭션 밖에서 수신자를 읽도록 조회 쿼리에서 회원을 함께 가져온다.
- `MedicineScheduleNotificationListener.sendGuardianNotification`에서 가족이 없는 시니어를 건너뛴다.

### Out of scope
- `WalkNotificationListener`의 같은 바깥 트랜잭션 구조. 진행 중인 PR #803이 같은 파일을 바꾸므로 그 PR 이후 별도로 다룬다.
- H01 문구·대상(시니어 소유 일정만)·전달 방식. LLD-0066 그대로다.
- 여러 인스턴스가 같은 회차를 동시에 실행할 때의 중복. H01은 기존처럼 수신자별 UUID eventId를 쓴다.
- 복약 스케줄러 전체의 건별 예외 격리. 이번에는 확인된 NPE만 막는다.

## 3. 인터페이스 / API

HTTP 변경 없음. 스케줄 `0 0 * * * *` → `0 */10 * * * *`. 테스트와 수동 실행을 위해 `sendHealthScheduleReminderAt(LocalDateTime runAt)`을 둔다. `@Scheduled` 메서드는 `LocalDateTime.now()`로 이 메서드를 부른다.

## 4. 데이터 모델

테이블 변경 없음. `HealthScheduleRepository.findUpcomingSchedulesInTimeRange`에 `JOIN FETCH h.member`를 더한다(이 스케줄러만 쓰는 쿼리).

## 5. 처리 흐름

1. `minute = runAt`을 분 단위로 자른 값(초·나노초 버림).
2. `windowStart = minute - (minute.분 % 10) + 1시간`, `windowEnd = windowStart + 10분`. 예: 14:20:00.04 실행 → `[15:20:00, 15:30:00)`.
3. 회차 창은 서로 겹치지 않고 빈틈없이 이어지므로, 모든 회차가 실행되면 각 일정은 정확히 한 회차에 든다. 알림은 일정 60~70분 전에 간다.
4. 일정마다 기존 판정(필수 값 누락·보호자 소유 제외) 후 `sendMessageToUser`를 부른다. 이 호출이 자기 쓰기 트랜잭션으로 센터 행·outbox를 커밋한다. 실패하면 그 호출만 롤백되고 ERROR 로그 후 다음 일정으로 간다.
5. 복약 M04: 시니어 프로필이 없거나 가족이 null이면 debug 로그 후 그 일정만 건너뛴다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
| --- | --- |
| 일정 한 건 enqueue·커밋 실패 | 그 일정만 롤백, ERROR 로그(scheduleId·memberId), 다음 일정 계속 |
| 서버 중단·지연으로 회차를 건너뜀 | 그 10분 구간 일정은 알림 없음(기존과 같은 성질) |
| 복약 시니어의 가족 없음 | 보호자 알림 건너뜀, 다음 일정 계속 |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 14:00~14:50 여섯 회차(실행 시각에 밀리초 포함)에서 15:00·15:09:59·15:10·15:20·15:30 일정이 각각 정확히 한 번 알림되고, 16:00 일정은 알림되지 않는다(15시대 회차 몫).
- [x] AC2: 14:20:00.037 실행은 15:25 일정의 outbox를 쓰기 트랜잭션으로 커밋하고 송신한다(H2).
- [x] AC3: 한 일정의 커밋이 실패하면 그 일정의 outbox·센터 행·송신이 남지 않고 스케줄러는 예외 없이 끝난다. 같은 회차의 다른 일정은 커밋·송신된다(H2). 바깥 트랜잭션을 되돌리면 이 두 테스트가 실패함을 확인했다.
- [x] AC4: 가족이 해제된 시니어의 복약 일정이 앞에 있어도 다음 시니어의 보호자에게 M04를 보낸다.
- [x] AC5: `HealthScheduleNotificationListenerTest`, `FcmSchedulerOutboxIntegrationTest`, `MedicineScheduleNotificationListenerTest`가 통과한다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 스케줄러 실행이 시간당 1회에서 6회로 늘고 회차마다 10분 창 조회 1번이다(`scheduled_at` 범위 조건). 배포 직후부터 분 00·10~59 일정도 알림을 받는다. 신규 ADR N/A: 기존 "1시간 전 알림" 의도를 맞추는 수정이다.

## 9. 미결정 사항 (Open Questions)

- 없음. 10분 주기(알림 60~70분 전)와 정각 실행 + 1시간 창(60~120분 전) 중 "1시간 전" 의미에 가까운 10분 주기를 택했다.

## 10. 참고

- [LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md), [LLD-0068](LLD-0068-medication-notification-alignment.md), [ADR-0028](../adr/ADR-0028-fcm-durable-delivery.md)
