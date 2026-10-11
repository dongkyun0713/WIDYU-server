# LLD-0075: 후속 질문 첫 제출 10P 적립

> Low-Level Design. 이 문서는 이슈 #717 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-02 구현·검증 완료, 코디네이터 검수 대기) |
| Issue | #717 |
| 관련 ADR | [ADR-0029](../adr/ADR-0029-payment-point-decoupling.md), ADR-0038, ADR-0037 |
| 선행 | [LLD-0073](LLD-0073-followup-card-and-answers.md)(W14), LLD-0069(W9b) |
| 작성자 | Codex / feature-717 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

시니어가 종료된 사건의 후속 질문에 처음 답하거나 명시적으로 전체 거절을 제출하면 10P를 한 번 적립한다. 카드 제출·포인트 원장·P01 센터 기록을 하나의 DB 트랜잭션으로 묶어 중복 요청이나 저장 실패가 잔액과 제출 기록을 어긋나게 하지 않는다. `sensor.followup.reward-enabled=false`를 기본값으로 배포한다.

## 2. 범위

### In scope

- 변경 모듈은 `widyu-api`다. `FollowupCardService`의 공용 `claim`이 첫 `ANSWERED` 또는 `DECLINED` 전이에 성공한 직후 한 곳에서 `FollowupRewardService`를 호출하고, `SensorProperties.Followup`과 `application-sensor.yml`에 보상 플래그를 추가한다.
- 첫 제출 10P를 기존 `SeniorProfileService.addPointsToMember(memberId, points, description, operationKey)`로 적립한다. W9b 일반 적립 경로가 P01 알림센터 행을 만든다.
- 플래그 ON/OFF, 중복·경합, 롤백, P01 생성에 대한 테스트를 추가한다.

### Out of scope

- `widyu-domain` 엔티티·enum, 새 테이블·컬럼, 새 포인트 적립 오버로드, 새 HTTP API·응답 필드, 별도 보상 원장·outbox·재처리 스케줄러.
- 카드 열람, 미제출 만료, 본인확인 응답, 재제출의 적립. 답변 내용·판독 결과·연구 또는 제품 개선 동의에 따른 차등이나 이미 지급한 포인트의 환수.
- `FcmCategory` 값 추가와 P01 직접 enqueue. 일·월 적립 상한과 사용자군 제한.

## 3. 인터페이스 / API

외부 계약은 LLD-0073의 `POST /api/v1/followups/{id}/answers`와 `POST /api/v1/followups/{id}/decline`을 그대로 쓴다. 첫 답변과 첫 전체 거절은 동일한 10P 보상 대상이다. 성공 응답은 기존 `ApiResponseTemplate<FollowupSubmissionResponse>`이며 포인트·보상 상태 필드를 추가하지 않는다. 보상 플래그 OFF는 제출 저장을 막지 않고 적립만 생략한다. 카드 기능 자체의 `sensor.followup.enabled=false` 동작과 기존 인증·검증·409·410 응답을 유지한다.

| 내부 호출 | 입력 | 결과 |
| --- | --- | --- |
| `FollowupRewardService.rewardIfEnabled` | 첫 `ANSWERED` 또는 `DECLINED` 전이에 성공한 카드 ID·시니어 회원 ID | ON이면 10P 적립, OFF면 적립 없이 반환 |
| `SeniorProfileService.addPointsToMember` 기존 4인자 시그니처 | `seniorId`, `10L`, `"후속 질문 답변"`, `"FOLLOWUP:" + cardId` | 잔액과 `PointHistory(EARN)` 변경. W9b 일반 적립 리스너가 P01 센터 1건 생성 |

10P는 명세가 고정한 코드 상수다. 설정값으로 받지 않는다. P01은 위듀 알림센터의 `전체`·`안 읽음`에만 나타나며 푸시/outbox는 만들지 않는다. W9b의 P01 문구는 제목 `10P를 받았어요.`, 본문은 원장 사유 `후속 질문 답변`, 딥링크는 `widyu://points`다.

## 4. 데이터 모델

신규 스키마와 ERD 변경은 없다. LLD-0073의 `followup_card.state` 조건부 전이와 `followup_answer.card_id` UK를 첫 제출 근거로 쓰고, 기존 `senior_profile.points`·`point_history`·`fcm_notification`을 사용한다. 운영 UK `uk_point_history_operation_key`가 `FOLLOWUP:{cardId}`를 카드별 단일 적립으로 제한한다. 키에 정책 버전이나 재시도 번호를 넣지 않는다. Q1~Q3 원답은 포인트 내역에 복제하지 않는다.

P01의 센터 행과 `eventId`는 LLD-0069의 일반 적립 경로가 생성한 `PointHistory.id`에 따른다. 이 서비스는 별도 P01 `eventId`를 만들지 않는다. `PointHistory.operationKey` UK와 센터의 `UK(recipient_member_id,event_id)`는 각각 적립과 표시의 중복 방어선이다.

## 5. 처리 흐름

1. `FollowupCardService.answer/decline`은 LLD-0073의 플래그·시니어 본인·카드 소유·문항·시각 검증을 수행한다. 공용 `claim`의 `ISSUED` 또는 수락 가능한 `EXPIRED_NO_RESPONSE`에서 `ANSWERED`·`DECLINED`로 조건부 UPDATE가 **1건 성공한 요청만** 다음 단계로 간다. 만료 전 단말 제출이 만료 뒤 서버에 도착해 수락된 첫 제출도 보상한다.
2. 공용 `claim`은 조건부 UPDATE 1건을 확인한 직후 **한 곳에서만** `FollowupRewardService.rewardIfEnabled(cardId, seniorId)`를 호출한다. 두 제출 경로는 같은 `@Transactional` 경계에서 `FollowupAnswer` 원답 행을 저장한다. 보상 플래그 OFF이면 제출만 저장하고 ON이면 `REWARD_POINTS=10L`, `operationKey=FOLLOWUP:{cardId}`로 기존 4인자 `addPointsToMember`를 호출한다. 시니어 ID는 소유 검증을 통과한 카드의 `senior_id`를 사용한다.
3. W9b 일반 적립 경로는 잔액·`PointHistory(EARN)`를 저장하고 같은 트랜잭션의 동기 이벤트 리스너로 P01 센터 행을 만든다. `FollowupRewardService`는 P01을 직접 발행하지 않고 목표 달성 G01 경로도 쓰지 않는다.
4. `decline`은 기존 `ISSUED/EXPIRED_NO_RESPONSE→DECLINED`와 원답 NULL 행을 저장하고, 첫 전이에 성공하면 답변과 동일하게 10P를 적립한다. `ANSWERED`·`DECLINED` 이후 재제출은 기존 409이며 보상 호출까지 가지 않는다. 카드의 조건부 전이, `followup_answer.card_id` UK, `point_history.operation_key` UK로 동시 요청의 승자 한 건만 커밋한다.
5. 카드·원답·포인트·P01 저장 중 하나라도 실패하면 전체 트랜잭션을 롤백한다. 별도 `REQUIRES_NEW`, 비동기 이벤트, `AFTER_COMMIT` 재시도는 쓰지 않는다. 실패한 답변은 클라이언트가 다시 제출할 수 있다. 내부 `addPointsToMember`의 `@RetryOnPointConflict`가 최외곽 제출 트랜잭션 전체를 재시도한다고 가정하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | 응답·저장 결과 |
| --- | --- |
| 카드 기능 OFF, 타인 카드, 유효하지 않은 입력·시각 | LLD-0073의 404/400/410 유지. 답변·포인트·P01 없음 |
| 이미 답변 또는 거절, 동시 제출 패자 | 기존 409 `FOLLOWUP_4090`. 원답·잔액·P01 추가 변경 없음 |
| 보상 플래그 OFF | 답변·전체 거절은 기존 성공 응답, 포인트·P01 0건. 나중에 ON으로 바꿔도 소급 적립하지 않음 |
| 시니어 프로필 없음, 포인트 원장 UK 충돌, P01 저장 실패 등 | DB 트랜잭션 전체 롤백. 보상 실패를 삼키고 성공 응답을 만들지 않음 |

포인트 `@Version` 낙관적 잠금 충돌이 상위 `FollowupCardService` 트랜잭션의 flush/commit 시점에 발생하면 내부 `addPointsToMember`의 `@RetryOnPointConflict`는 재시도하지 못한다. 예외는 `GlobalExceptionHandler`의 409 `POINT_CONCURRENT_UPDATE`로 응답하고 카드 상태·원답·포인트·P01은 함께 롤백된다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 두 플래그가 ON일 때 유효 카드의 첫 `ANSWERED` 또는 `DECLINED` 제출은 원답 1건, 잔액 +10P, `PointHistory(EARN, amount=10, operation_key=FOLLOWUP:{cardId})` 1건, 시니어 P01 센터 행 1건을 함께 커밋한다. P01 푸시/outbox는 0건이다.
- [x] AC2. 같은 카드의 재제출·동시 제출 패자는 기존 409이며 추가 적립·원장·P01은 모두 0건이다. Q1/Q2 값과 관계없이 첫 유효 답변의 적립액은 10P다.
- [x] AC3. 첫 `DECLINED` 제출도 답변 내용 없는 원답 행과 10P·P01을 남긴다. 카드 열람·만료·본인확인 응답·재제출·동시 패자는 포인트와 P01을 만들지 않는다.
- [x] AC4. `sensor.followup.reward-enabled=false`가 기본값이며 OFF에서도 카드 답변 저장은 성공하고 적립·P01은 0건이다. OFF 기간 제출은 ON 전환 후에도 소급 적립하지 않는다.
- [x] AC5. 만료 전 단말 제출이 만료 뒤 도착해 LLD-0073 규칙으로 수락된 첫 `ANSWERED` 또는 `DECLINED`도 10P·P01을 받는다. 일·월 상한, 사용자군·동의·답변 내용·판독 결과에 따른 차등은 없다.
- [x] AC6. 포인트 저장 또는 P01 생성 실패는 카드 `ANSWERED`·`DECLINED` 전이와 원답을 포함해 모두 롤백한다. 재시도 후 커밋된 잔액·원장·P01은 각각 한 번만 증가한다.
- [x] AC7. JUnit 5+Mockito 테스트는 한글 언더스코어 메서드명, `@DisplayName`의 「<행위>하면 <결과>한다」 형식, BDDMockito `given(...).willReturn(...)`을 따른다. 상태 검증을 우선하고 외부 부수효과에는 `verify`를 쓴다. `bash scripts/harness/verify.sh`가 통과한다. 새 HTTP 계약이 없어 Swagger 변경은 N/A이며 H2 통과로 운영 MySQL ENUM 호환을 추정하지 않는다.

## 8. 영향 범위 / 마이그레이션

`FollowupCardService`의 공용 `claim`에 보상 호출 한 곳, 신규 `FollowupRewardService`, `SensorProperties.Followup`, `application-sensor.yml`과 관련 테스트만 변경한다. 제출 응답 DTO는 유지한다. 운영 DDL·ERD·MySQL ENUM ALTER는 없다. W9b의 일반 적립 P01 경로는 merge 커밋 `262eed25`로 이 브랜치에 반영했다. PR은 #713·#722 머지 뒤 rebase한다. 기존 포인트 메서드에 새 오버로드를 추가하거나 `FcmCategory`를 확장하지 않는다.

`sensor.followup.reward-enabled`는 `SENSOR_FOLLOWUP_REWARD_ENABLED:false`로 설정한다. 카드 기능과 보상 플래그는 독립적이며 활성화는 IRB 기재 확인 뒤 운영에서 결정한다. 기본 OFF 배포에서는 신규 원장·센터 행이 생기지 않는다.

## 9. 미결정 사항 (Open Questions)

| 항목 | 이 LLD의 구현 계약 | 활성화 전 확인 |
| --- | --- | --- |
| 전체 거절 보상 | `DECLINED` 첫 제출도 10P. A §5·2026-09-28 CHECK-BUNDLE §2·§3의 확정값 | 추가 구현 결정 없음 |
| 보상 고지·IRB | 플래그 OFF로 배포 | A §5와 전체 계획 §5에 따라 고지 시점과 IRB 기재 확인이 닫히기 전 ON 금지. 운영 활성화 조건이며 OFF 구현을 막지 않음 |

A §5의 별도 지급 감사·`PENDING_RETRY` 제안은 이번 이슈의 같은 트랜잭션·기존 원장 재사용 지시와 범위가 다르다. 이 LLD는 동기 원자성으로 보상 실패 시 제출도 실패하게 정한다. 별도 보상 재처리 정책을 채택하면 추후 LLD로 트랜잭션·응답 계약을 다시 설계한다.

## 10. 참고

- [LLD-0073](LLD-0073-followup-card-and-answers.md) §5.2·§7, [LLD-0069](LLD-0069-goal-achieved-and-point-center.md) §3~§5. W9b는 merge 커밋 `262eed25`로 반영했고 PR은 #713·#722 머지 뒤 rebase한다.
- [ADR-0029](../adr/ADR-0029-payment-point-decoupling.md): 활동 보상 포인트와 결제의 분리. ADR-0037·0038 원문은 현재 브랜치에 없어 `feature-691/docs/adr/`에서 확인했다.
- Git 제외 명세 `A-LABEL-QUALITY-SPEC-v0.1` §5, `DEVTEAM-CHECK-BUNDLE-2026-09-28` §2·§3, `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` A5.
- 알림센터 승인판 `HEMLO-REVIEW-v0.5`(우선), `FE-HANDOFF-v0.4`, `FE-DELIVERY-PACKAGE-v1.2`, `NOTIFICATION-CENTER-UX-SPEC-v0.3`, 문구 정본 `NOTIFICATION-COPY-CATALOG-v0.4` P01.
