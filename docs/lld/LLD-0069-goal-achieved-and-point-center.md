# LLD-0069: 목표 달성 G01·포인트 센터 P01/P02

> Low-Level Design. 이 문서는 #704 구현과 PR 본문의 오라클이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #704 |
| 관련 ADR | ADR-0037, ADR-0029 |
| 작성자 | Codex / feature-704 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 목표 달성은 포인트만 적립하고 알림을 만들지 않는다. 포인트 내역도 `PointHistory`에만 남는다. 목표 달성 시 적립 포인트를 보여 주는 G01을 시니어·보호자에게 만들고, 별도 포인트 증감은 시니어 알림센터에 P01/P02로 기록한다. 한 적립을 G01과 P01에 중복 표시하지 않는다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-api`만. `SeniorProfileService` 포인트 증감, `WalkService.updateSteps`, `HealthScheduleProgressService.completeAndReward`(수동 완료·위치 자동 완료 공통), `MedicationProofTransactionService.rewardPoints`, `AlbumUnlockService.unlockAlbum`, 동기 알림 이벤트/리스너, `RetryOnPointConflict` Javadoc 및 관련 테스트를 변경한다.
- `POINT_EARNED`/`POINT_USED`는 시니어에게 `CENTER_ONLY`로 만든다. 목표 보상은 `GOAL_ACHIEVED` `PUSH_AND_CENTER`를 시니어와 같은 가족의 보호자에게 만들고 해당 적립의 P01은 생략한다.
- 기존 `PointHistory.operationKey`와 목표별 1회 지급 상태를 유지하며, 알림센터의 `UK(recipient_member_id,event_id)`를 재시도 방어선으로 사용한다.

### Out of scope

- 새 HTTP API, 포인트 금액·적립 조건·잔액 정책, 포인트 내역 조회 API, `GOALS_ACHIEVED_GROUPED` 발행, 푸시 설정 API, 알림 목록 API, 결제·환불 포인트 연동은 변경하지 않는다.
- `AdminPointGrantService`의 `[관리자 테스트 지급]` 직접 적립과 가입 초기 100P 지급은 이 활동 보상·앨범 사용 발행 범위 밖이다. 이 경로를 일반 P01로 통일하는 작업은 별도 범위로 다룬다.
- 전송 보장 방식과 `FcmCategory` 상수는 변경하지 않는다.

## 3. 인터페이스 / API

HTTP 경로와 기존 요청·응답 JSON은 바뀌지 않는다. 내부 호출 계약은 다음과 같다.

| 호출 | 입력 | 결과 |
| --- | --- | --- |
| `SeniorProfileService.addPointsToMember` 기존 오버로드 | 회원 ID, 양수 포인트, 사유, 선택적 `operationKey` | 잔액·`PointHistory(EARN)` 저장 후 P01 센터 이벤트 1건. |
| `SeniorProfileService.addGoalRewardPoints` | 회원 ID, 양수 포인트, 사유, 선택적 `operationKey` | 같은 적립의 P01을 만들지 않는다. 호출자가 별도 G01 이벤트를 발행한다. 기존 `addPointsToMember` 시그니처는 유지하며, `operationKey` 접두어로 P01을 숨기는 암묵 규칙은 쓰지 않는다. |
| `SeniorProfileService.deductPointsFromMember` | 회원 ID, 양수 포인트, 사유, 선택적 `operationKey` | 잔액·`PointHistory(USE)` 저장 후 P02 센터 이벤트 1건. |
| 목표 달성 동기 이벤트 | 시니어 ID, 목표 종류/표시명, 이번 적립 포인트, 목표 엔티티 ID, 목표 사건 키 | G01-S와 가족 보호자별 G01-C를 같은 트랜잭션에서 enqueue한다. |

알림 문구는 `NotificationCopy.of(type, code, values)`와 문구표 v0.4 §4를 사용한다. G01-S 제목은 `{목표명} 목표를 달성했어요!`, G01-C는 `{시니어 이름} 님이 {목표명} 목표를 달성했어요!`, 공통 본문은 `{포인트}P가 자동으로 적립됐어요.`다. P01 제목은 `{포인트}P를 받았어요.`, P02 제목은 `{포인트}P를 사용했어요.`이며 본문은 각각 저장한 `PointHistory.description` 그대로다. P01/P02의 `deepLink`는 `widyu://points`다. 시니어 G01-S는 `NotificationType.GOAL_ACHIEVED`의 현행 `widyu://goals/{entityId}` 템플릿을 유지한다. 보호자 G01-C는 [LLD-0076](LLD-0076-guardian-deeplink-paths.md)에 따라 `FcmSendDto.deepLink=/goal/medicine?seniorId={seniorId}`를 명시한다. 걷기·건강일정 달성도 이 화면으로 보내며 FE 확인을 기다린다.

G01은 `NotificationType.GOAL_ACHIEVED`(`FcmCategory.TARGET`, `PUSH_AND_CENTER`, `GENERAL`)를 쓴다. P01/P02는 각각 `POINT_EARNED`/`POINT_USED`(`CENTER_ONLY`, `NONE`)를 쓴다. 승인판에서 P01/P02는 위듀 `전체`·`안 읽음`에만 들어가며 `목표` 필터에는 들어가지 않는다. 현재 W3의 `NotificationType`에는 두 타입의 `centerFilter=GOAL`이 남아 있으므로 W4 레지스트리 정정(`null`)을 통합 전 확인한다. 발행자가 별도 필터 예외를 만들지 않는다.

## 4. 데이터 모델

새 테이블·컬럼·엔티티는 없다. 기존 `SeniorProfile.points`, `PointHistory(type, amount, description, operation_key)`와 ADR-0037의 `fcm_notification`/`fcm_outbox`를 사용한다. `widyu-domain` 수정과 ERD·DDL 변경은 없다.

각 실제 포인트 변동의 `PointHistory.id`로 P01/P02의 `eventId`를 만든다(예: `POINT:E:<id>`, `POINT:U:<id>`). 목표 사건은 목표 종류와 엔티티 ID로 짧고 결정적인 키를 만든다(예: `G01:W:<walkId>`, `G01:H:<healthScheduleId>`, `G01:M:<medicationProofId>`). 모든 키는 현재 `FcmOutboxService.enqueue`의 40자 제한 안에 있어야 한다. 같은 목표 사건은 시니어·보호자에게 같은 키를 사용할 수 있다. 유니크 제약은 수신자와 함께 판정한다. 새 `group_key`는 만들지 않는다.

`PointHistory.operationKey`는 적립 멱등 키로 계속 사용한다(`WALK_REWARD:<walkId>`, `HEALTH_SCHEDULE_REWARD:<scheduleId>`, `MEDICATION_PROOF:<proofId>`). 알림 `eventId`와 목적이 다르므로 서로 대체하지 않는다. `operationKey`가 없는 차감은 앨범 해금의 기존 1회 해금 기록·유니크 제약이 중복 실행을 막고, 생성된 `PointHistory.id`가 해당 P02를 식별한다. `operationKey` 없이 독립적으로 호출된 일반 포인트 증감은 각 호출이 별도 거래이며 재요청 멱등을 약속하지 않는다.

## 5. 처리 흐름

1. 기존 포인트 증감 메서드는 양수 검증, 시니어 프로필 조회, 잔액 변경, `PointHistory` 저장을 유지한다. 성공한 실제 증감에만 동기 도메인 이벤트를 발행한다. 일반 적립은 P01, 차감은 P02다. 이름이 다른 `addGoalRewardPoints`는 같은 적립을 수행하고 P01만 생략한다. 이벤트 리스너는 같은 트랜잭션에서 `FcmOutboxService.enqueue`를 호출한다. `@Async`, `AFTER_COMMIT`, 동기 FCM 전송은 쓰지 않는다.
2. `WalkService.updateSteps`는 최초 목표 달성 및 미지급 상태에서만 기존 `walk.getPointRewarded()`를 적립하고 `walk.markRewarded()` 한다. 목표 보상용 적립 뒤 `G01:W:<walkId>` 이벤트를 발행한다. 동일 걸음 기록 재연동은 보상·G01·P01을 다시 만들지 않는다.
3. `HealthScheduleProgressService.completeAndReward`는 수동 방문과 위치 자동 완료의 공통 경로다. 시니어 소유이며 `isReward=false`일 때만 기존 `rewardPoint`를 적립하고 `claimReward()` 한 뒤 `G01:H:<scheduleId>`를 발행한다. 보호자 본인 일정의 완료는 적립·G01 없이 유지한다.
4. `MedicationProofTransactionService.verifyMedication`은 기존 1회 인증 검증과 `MedicationPointPolicy` 계산을 유지한다. 완료 전 인증 1회의 10P는 일반 적립으로 P01만 만든다. 해당 인증으로 그날 유효한 일정을 모두 채우면 기존 계산대로 마지막 인증 10P와 완주 보너스 20P를 합쳐 한 번 적립하고, `하루 복약` 목표의 G01만 만든다. 유효 일정이 하나라 첫 인증이 완주라면 30P G01 한 건이다. 적립액이 0이거나 시니어 프로필이 없으면 알림도 없다. 증빙 S3 업로드는 현행처럼 DB 트랜잭션 밖에 있다.
5. `AlbumUnlockService.unlockAlbum`은 권한·가족·자기 앨범·대상·잔액·중복 해금 검증을 그대로 수행한 뒤, 현재의 직접 `deductPoints`/`PointHistory.use`를 `SeniorProfileService.deductPointsFromMember(..., "앨범 해금", ...)` 호출로 바꾼다. 같은 트랜잭션에서 50P 차감, P02 센터 1건, 해금 기록, 기존 `AlbumUnlockedEvent`가 함께 커밋 또는 롤백된다. 기존 부족 잔액 오류(`ALBUM_UNLOCK_INSUFFICIENT_BALANCE`)와 응답 잔액 계약은 유지한다. 이 내부 호출에는 최외곽 재시도가 적용되지 않는다.
6. G01 리스너는 `SeniorProfile.family`의 현재 보호자 멤버십을 조회한다. 시니어에게 G01-S 1건을 enqueue하고, 같은 가족의 활성 보호자마다 G01-C 1건을 enqueue한다. `relatedMemberId=seniorId`로 가족 검증을 거친다. 푸시 설정이 꺼진 보호자의 센터 행도 남고, `GENERAL`을 켠 보호자만 푸시 대상이다(ADR-0037). 토큰이 없어도 센터 행은 남는다. G01은 적립 포인트를 본문에 담고 P01을 만들지 않는다.
7. `FcmOutboxService`는 센터 저장형 타입을 토큰 루프 전에 `UK(recipient_member_id,event_id)`로 저장한다. `CENTER_ONLY`는 outbox를 만들지 않는다. 트랜잭션 롤백 시 잔액·내역·센터·outbox가 같이 롤백되고, 커밋 뒤 dispatcher 전송은 기존 at-least-once 정책을 따른다.

## 6. 예외 / 에러 처리

- 기존 포인트 부족·회원/프로필 없음·방문/복약 인증 실패는 기존 오류를 유지하고 알림은 0건이다. 무효 포인트(`null` 또는 0 이하)는 현행 조기 반환을 유지하며 알림도 만들지 않는다.
- 목표 보상 저장, 보호자 조회, 문구 생성, enqueue 중 예외가 나면 해당 DB 트랜잭션 전체를 롤백한다. 알림 실패를 삼키고 포인트만 커밋하지 않는다. 이후 기존 최외곽 진입점의 재시도·재요청이 같은 멱등 가드로 처리한다.
- `@RetryOnPointConflict`는 최외곽 트랜잭션에 붙은 경우에만 커밋 충돌을 재시도한다. 새 enqueue와 앨범의 동기 알림 이벤트가 저장하는 센터/outbox 행은 같은 DB 트랜잭션의 내부 저장이므로 실패한 시도와 함께 롤백된다. `RetryOnPointConflict` Javadoc의 「외부 부수효과」 설명은 이 차이를 명확히 하고, 복약의 선행 S3 업로드처럼 트랜잭션 밖에서 이미 실행된 작업에는 재시도를 도입하지 않는다고 갱신한다. 앨범 해금의 최외곽 재시도 도입은 이 범위에서 하지 않는다.
- `PointHistory.operationKey` 유니크 충돌이나 앨범 해금 중복 요청은 기존 거래를 다시 적립·차감하거나 새 알림을 만들지 않는다. 외부 FCM 전송 자체는 at-least-once이며 기기에서 같은 메시지를 두 번 받지 않는다는 보장은 이 LLD의 인수조건이 아니다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 걷기 목표 최초 달성은 기존 포인트를 한 번 적립하고 시니어 G01-S 센터 1건과 가족의 `일반 알림` ON 보호자별 G01-C 센터 1건·푸시 대상을 만든다. 같은 적립의 P01은 0건이다. OFF 보호자는 푸시 없이 센터 1건을 볼 수 있다.
- [x] 건강 일정의 수동 방문·위치 자동 완료는 각각 최초 보상에만 G01을 만들며, 보호자 본인 일정과 반복 완료에는 포인트·G01·P01을 만들지 않는다.
- [x] 복약 인증의 미완주 10P 적립은 P01 센터 1건·G01 0건이다. 하루 완주 적립은 실제 증분(10P+20P)을 본문에 넣은 G01 센터 1건·P01 0건이다. 일정 하나인 날의 첫 인증도 완주 경로다.
- [x] 앨범 해금 50P 차감은 시니어 P02 센터 1건·푸시/outbox 0건을 만들고, 본문은 `앨범 해금`, 딥링크는 포인트 내역이다. 중복 해금·잔액 부족은 추가 차감과 P02가 0건이다.
- [ ] 별도 일반 적립은 P01 센터 1건·푸시/outbox 0건을 만들고 `PointHistory.description`을 본문에 사용한다. P01/P02는 위듀 `전체`·`안 읽음`에만 나타난다.
- [x] 동일 걸음 연동·건강 일정 재완료·복약 인증 재요청·앨범 해금 재요청 및 포인트 낙관적 락 재시도에서 커밋된 잔액/내역/센터 행은 한 번만 증가한다. 롤백된 시도의 센터/outbox 행은 0건이다.
- [ ] 수신자에게 활성 토큰이 없거나 보호자의 `GENERAL` 푸시가 꺼져도 센터 행이 생성되며, 다른 가족·비활성 회원에게 G01을 보내지 않는다.
- [x] JUnit 5+Mockito 테스트는 한글 언더스코어 메서드명, `@DisplayName`의 「<행위>하면 <결과>한다」 형식, BDDMockito `given(...).willReturn(...)`을 따른다. 잔액·내역·센터 행 상태를 우선 검증하고 enqueue 같은 부수효과는 `verify`로 검증한다. H2로 MySQL native ENUM 동작을 추정하지 않는다.
- [x] 새 HTTP 계약이 없으므로 Swagger 변경은 N/A다. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

`SeniorProfileService`, 세 목표 경로, `AlbumUnlockService`, 동기 알림 이벤트/리스너, 관련 단위·통합 테스트와 `RetryOnPointConflict` Javadoc이 영향을 받는다. 기존 `PointHistory` 행은 소급 알림을 만들지 않는다. 스키마 마이그레이션과 MySQL ENUM ALTER는 이 작업에 없다. `NotificationType`과 W3의 센터 저장 모델을 선행 사용한다. 보호자 `GENERAL` 푸시 판정은 W5 설정 그룹 전환이 통합된 뒤 최종 검증한다. W4에서 P01/P02의 센터 필터 정정이 반영되어야 승인판의 `전체`·`안 읽음` 계약을 충족한다.

## 9. 미결정 사항 (Open Questions)

아래는 전체 계획 §5의 이 작업 관련 가정표를 그대로 옮긴 것이다. 회신이 가정을 바꾸면 구현 전에 코디네이터 결정 게이트로 확인한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
|---|---|---|---|
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |
| G02 동시 달성 묶음 | 단일 트리거가 목표 2개를 동시에 적립하는 경로가 없어 G01만 발생 | G01만 | — |

2026-10-02 구현은 W2 제안값을 사용했다. 이후 [LLD-0076](LLD-0076-guardian-deeplink-paths.md)이 보호자 G01-C를 `/goal/medicine?seniorId={seniorId}`로 교체했다. 위듀 경로와 걷기·건강일정 달성의 보호자 목적 화면은 FE 확인 대기다. G02는 레지스트리에 남지만 이 범위에서 발행하지 않는다. W4의 P01/P02 필터 정정과 W5의 `GENERAL` 설정 판정은 각 작업이 통합된 뒤 검증해야 한다.

코디네이터 게이트에서 이 설계를 Approved로 확인한 뒤 구현했다. P01 생략은 `addGoalRewardPoints`로 분리했고, 앨범은 직접 차감·내역 저장만 포인트 서비스 호출로 교체했다. 동기 record 이벤트와 typed DTO를 사용하며 보호자 G01에는 `relatedMemberId=seniorId`를 지정한다. P01/P02 문구는 `type.copyCode()`를 쓰고, G01은 역할별 `G01-S`/`G01-C`를 사용한다. eventId 다섯 형식의 40자 제한을 `Long.MAX_VALUE`로 검증했다. 구현·전체 테스트 후 상태를 Review로 전환했다.

## 10. 참고

- ADR-0037: 수신자×사건 센터 1건, 전달 방식, 설정과 센터 저장의 분리. 이 worktree에 없으므로 `../feature-691/docs/adr/ADR-0037-notification-center-model.md` 원문을 기준으로 삼았다. ADR-0038은 안전 흐름의 설계 근거이며 이 작업의 발행 대상이 아니다.
- [ADR-0029](../adr/ADR-0029-payment-point-decoupling.md): 활동 보상 전용 포인트, 결제 비연동.
- 승인판 `FE-HANDOFF-v0.4` §1-10·§4 14~15, `FE-DELIVERY-PACKAGE-v1.2` §8·§15, `NOTIFICATION-CENTER-UX-SPEC-v0.3`, `HEMLO-REVIEW-v0.5` §10, `NOTIFICATION-COPY-CATALOG-v0.4` §4, 코드 변경점 E18·E19.
- `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5. 원문은 `/Users/dongkyun/Documents/WIDYU-server/apiDocs/notification/`에 있으며 저장소 Git 관리 대상이 아니다.
