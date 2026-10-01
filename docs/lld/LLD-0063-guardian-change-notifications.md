# LLD-0063: 보호자의 건강일정·걷기 목표 변경 알림

> Low-Level Design. 이 문서는 #697 구현과 PR 검수의 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #697 |
| 관련 ADR | ADR-0037, ADR-0028 |
| 작성자 | Codex / feature/697 |
| 작성일 | 2026-10-01 |

## 1. 목적 / 배경

보호자가 시니어의 건강일정을 등록·변경·삭제하거나 걷기 목표를 저장해도 시니어에게 변경 사실이 전달되지 않는다(E15·E16). 승인된 H02·H03·H04·W02를 해당 시니어 한 명에게 푸시와 알림센터 `목표` 항목으로 전달한다. 시니어 본인 변경과 다른 보호자에게는 이 알림을 만들지 않는다.

## 2. 범위

### In scope

- 변경 모듈: `backend/widyu-api`. `HealthScheduleService.createHealthScheduleForSenior/updateHealthSchedule/deleteHealthSchedule`, `WalkService.setOrUpdateGoal`의 성공 경로에서 알림 이벤트를 발행한다.
- W2의 `NotificationType` 네 상수, `NotificationCopy`, typed `FcmSendDto`와 W3의 수신자×이벤트 센터 저장 계약을 사용한다. 호출자는 수신자와 type·문구 변수·대상 정보만 정한다.
- 서비스 단위 테스트와 이벤트·outbox 결합 테스트로 수신자, type, 문구, 트랜잭션 경계를 검증한다.

### Out of scope

- 건강일정·걷기 목표 수정 권한, 요청·응답 API, 건강일정 임박 H01 스케줄러, 걷기 목표 적용일을 바꾸지 않는다.
- 복약 변경 M08·M05·M09, 시니어 본인 변경 알림, 다른 보호자용 변경 알림, FE 라우터 구현은 별도 범위다.
- 센터 행 생성·설정 판정·중복 방지·다중 기기 전달 방식은 ADR-0037/W3의 책임이다. 이 LLD는 `fcm_notification`을 직접 저장하지 않는다.

## 3. 인터페이스 / API

기존 HTTP 계약을 유지한다.

| 메서드·경로 | 기존 처리 | 새 부수효과 |
| --- | --- | --- |
| `POST /api/v1/goals/health-schedules/guardians` | `memberId` 대상 생성, `HLTH_2001` | 보호자→시니어 성공 시 H02 |
| `PATCH /api/v1/goals/health-schedules/{healthScheduleId}` | 수정, `HLTH_2002` | 보호자→시니어 성공 시 H03 |
| `DELETE /api/v1/goals/health-schedules/{healthScheduleId}` | 삭제, `HLTH_2003` | 보호자→시니어 성공 시 H04 |
| `POST /api/v1/goals/walks/goal?memberId={seniorId}` | 목표 설정·수정, `WALK_2003` | 보호자→시니어 성공 시 W02 |

기존 `ApiResponseTemplate` 응답과 오류 코드는 그대로다. 내부 알림 계약은 아래와 같다. `entityId`는 건강일정 ID의 문자열이며 삭제 전에 확보한다. W02에는 일정 ID가 없으므로 `entityId`를 싣지 않는다. `actorDisplayName`은 보호자의 현재 이름, null·빈 문자열·공백이면 `가족`이다.

| type / 문구 | title / body (문구표 v0.4) | data·딥링크 |
| --- | --- | --- |
| `HEALTH_SCHEDULE_CREATED` / H02 | `{보호자 이름} 님이 새 건강 일정을 등록했어요.` / `앱에서 일정을 확인해보세요.` | `entityId={scheduleId}`, `widyu://health/schedules/{entityId}` |
| `HEALTH_SCHEDULE_UPDATED` / H03 | `{보호자 이름} 님이 건강 일정을 변경했어요.` / `앱에서 바뀐 일정을 확인해보세요.` | `entityId={scheduleId}`, `widyu://health/schedules/{entityId}` |
| `HEALTH_SCHEDULE_DELETED` / H04 | `{보호자 이름} 님이 건강 일정을 삭제했어요.` / `앱에서 남은 일정을 확인해보세요.` | `entityId={scheduleId}`, `widyu://goals` |
| `WALK_GOAL_CHANGED` / W02 | `{보호자 이름} 님이 걷기 목표를 변경했어요.` / `새 목표는 하루 {목표 걸음 수}걸음이에요.` | `widyu://walk/goal`; 새 `request.steps()`를 사용 |

네 type 모두 W2 레지스트리의 `PUSH_AND_CENTER`·센터 필터 `GOAL`·`INTERACTION`·`GENERAL`·`BANNER`·`ROUTINE_90D`를 쓴다. `FcmCategory`는 각각 기존 `HEALTH_SCHEDULE`·`WALK`이고 값을 추가하지 않는다. FCM data는 typed 계약의 `eventId`, `type`, `priority`, `notificationId`, `deepLink`, `foregroundPresentation`와 `actorDisplayName`, 일정의 `entityId`를 담는다. 정확한 딥링크 문자열은 FE 합의 전 제안값이다(9절).

## 4. 데이터 모델

새 테이블·컬럼·엔티티는 없다. 기존 `HealthSchedule.id`와 대상 `Member.id`, `SeniorProfile.defaultWalkGoal`을 사용한다. `NotificationType`과 outbox/센터 스키마는 W2·W3가 제공한다. W3에서 센터 행은 토큰 수나 푸시 설정과 관계없이 수신자×이벤트 한 건이며, 본 기능은 수신자 ID 하나에 typed enqueue 한 번만 요청한다. `FcmCategory` enum이나 MySQL native ENUM DDL은 바꾸지 않는다.

## 5. 처리 흐름

1. 기존 인증·가족 접근 검사를 그대로 거친다. 생성은 연결된 시니어 조회 뒤, 수정·삭제는 `validateHealthScheduleAccess` 뒤, 걷기는 컨트롤러의 `@ValidateFamilyAccess`와 서비스의 `getMember` 뒤에서 알림을 판단한다. **행위자 `MemberType.GUARDIAN`이고 대상 `MemberType.SENIOR`이며 서로 다른 회원**일 때만 발행한다. 알림 분기는 권한을 새로 허용하지 않는다.
2. `createHealthScheduleForSenior`는 저장된 ID를 얻은 뒤 H02 이벤트를 한 번 발행한다. `updateHealthSchedule`은 `healthSchedule.update` 성공 뒤 H03, `deleteHealthSchedule`은 삭제 대상 ID·소유자 ID를 보존한 뒤 삭제 요청 성공 시 H04를 한 번 발행한다. 수정된 일정은 즉시 기존 H01 조회 대상이 되며 H01 스케줄러는 수정하지 않는다.
3. `setOrUpdateGoal`은 초기 설정과 이후 수정 양쪽에서 정상 저장 뒤 W02 이벤트를 한 번 발행한다. 현재 처음 설정 분기의 early return 전에도 발행한다. 본문 변수는 이전 목표가 아닌 새 `request.steps()`다. 오늘/내일 적용 로직은 기존대로다.
4. 서비스는 대상 ID·type·일정 ID 또는 새 목표 걸음 수·보호자 이름의 불변 값만 이벤트에 싣는다. `widyu-api`의 동기 `@EventListener`가 `NotificationCopy.of(type, type.copyCode(), values)`로 승인 문구를 만들고 typed `FcmSendDto`를 조립해 `FcmOutboxService.enqueue(seniorId, dto)`를 **업무 트랜잭션 안에서 한 번** 호출한다. `@TransactionalEventListener(AFTER_COMMIT)`·`@Async`·독립 `REQUIRES_NEW`로 분리하지 않는다. 리스너는 삭제된 엔티티를 다시 조회하지 않는다.
5. enqueue 실패는 업무 저장을 함께 롤백한다. 커밋 후 dispatcher가 트랜잭션 밖에서 FCM HTTP를 보내고 실패를 outbox 재시도 정책으로 처리한다(ADR-0028). W3가 센터 행을 enqueue에서 생성한다. 센터 생성·outbox 생성은 업무와 함께 커밋되며, 실제 FCM 성공은 API 성공 조건이 아니다. W3 이전 브랜치에서는 기존 센터 저장 방식이 남아 있으므로 센터 1건 조건은 W3 결합 검증에서 확정한다.

## 6. 예외 / 에러 처리

- 기존 회원·프로필·일정 미존재, 가족 접근 실패, 요청 검증 실패는 현재 오류 코드와 응답을 유지한다. 이때 변경 이벤트·outbox·센터 행은 0건이다.
- 보호자 이름이 없거나 공백이면 `actorDisplayName=가족`으로 만들고 제목도 `가족 님이 …`로 렌더링한다.
- 문구 렌더링 또는 enqueue 저장이 실패하면 같은 업무 트랜잭션을 롤백한다. HTTP 전송 실패는 outbox 상태·재시도로 다루며 건강일정/걷기 HTTP 오류로 되돌리지 않는다.
- H02/H03의 일정이 나중에 삭제되어 라우팅할 수 없으면 FE가 문구표 §9의 안내 후 목표 화면으로 이동한다. H04는 처음부터 목표 화면으로 보낸다. 잠금화면 제목·본문에는 일정명, 병원 이름, 주소를 넣지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] E15/H02: 보호자가 연결된 시니어 일정 하나를 등록하면 해당 시니어 ID로 `HEALTH_SCHEDULE_CREATED`가 정확히 한 번 enqueue되고, 센터 `GOAL` 항목 1건과 일정 상세 딥링크·일정 `entityId`·보호자 이름을 갖는다.
- [ ] E15/H03: 보호자가 시니어 일정을 변경하면 `HEALTH_SCHEDULE_UPDATED` 한 건이다. 변경이 저장 즉시 조회되고 기존 H01이 변경된 일정 기준으로 동작한다.
- [ ] E15/H04: 보호자가 시니어 일정을 삭제하면 `HEALTH_SCHEDULE_DELETED` 한 건이다. 삭제 전 ID가 data에 남고 딥링크는 `widyu://goals`다.
- [ ] E15/H02~H04: 시니어 본인이 자기 일정을 생성·변경·삭제하면 변경 알림 0건이다. 다른 보호자 대상 enqueue·센터 항목도 0건이다. 미인가 변경이나 업무 롤백 시에도 0건이다.
- [ ] E16/W02: 보호자가 시니어 걷기 목표를 처음 설정하거나 수정해 저장하면 해당 시니어에게 `WALK_GOAL_CHANGED` 한 건이다. 본문은 `새 목표는 하루 {새 걸음 수}걸음이에요.`이고 딥링크는 걷기 목표다. 시니어 본인 설정과 다른 보호자 대상 알림은 0건이다.
- [ ] H02~H04/W02: 이름 null·빈 문자열·공백이면 제목과 `actorDisplayName`에 `가족`을 사용한다. 잠금화면 문구에 일정명·병원 이름·주소를 넣지 않는다. 기존 권한·HTTP 응답·걷기 적용일은 그대로다.
- [ ] 같은 업무 트랜잭션에서 동기 리스너가 enqueue한다. 이 브랜치에서는 enqueue 1회·수신자 1명·type·data 키를 검증한다. 롤백 시 outbox가 남지 않고 토큰 0개 또는 푸시 OFF여도 센터 행 한 건이 남는 조건은 **W3(#696) 결합 뒤 검증**한다. 실제 푸시 실패는 outbox 재시도 정책을 따른다.
- [ ] JUnit 5·Mockito 테스트는 `given(...).willReturn(...)`, 한글 언더스코어 메서드명과 행위형 `@DisplayName`을 쓰고 저장 상태를 우선 검증한다. 이벤트·enqueue 같은 부수효과는 `verify`로 수신자 1명과 호출 1회를 검증한다. `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

`HealthScheduleService`, `WalkService`와 새 변경 이벤트·동기 리스너·테스트가 변경 대상이다. 기존 H01·W01 스케줄러와 권한 코드는 유지한다. 새 엔티티·컬럼·운영 DDL·ERD 변경은 없다. W2 레지스트리가 선행되고 W3 센터 저장 전환이 합쳐진 상태에서 최종 인수조건을 검증한다. H2는 MySQL native ENUM을 재현하지 못하므로 운영 DDL 검증 근거로 삼지 않는다.

## 9. 미결정 사항 (Open Questions)

계획 `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §5의 회신 대기 항목과 작업 가정을 그대로 옮긴다. 이 LLD의 W02는 현행 「다음 날」을 유지하고 문구에 적용 시점을 넣지 않는다. 아래 가정을 바꾸는 회신이 오면 구현 전 담당 Task의 결정 게이트에서 반영한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
|---|---|---|---|
| T1-① 1분 기준 시각·경계 | 기준=서버 사건 생성 시각, 마감=+60s, 정확히 마감=초과(B §3 제안 수용) | 그대로 | — |
| T1-② HELP 값 유지 | 유지 제안(워치 호환, ESCALATED → 스케줄러가 INITIAL_ALERT) | 유지 | — |
| T1-③ FCM 전체 실패 시 동작·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, **INITIAL_ALERT는 그대로 1분에 발송**(시니어 미수신이 보호자 알림을 미루지 않음) | 그대로 | — |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속) | 그대로 | W12 |
| S10 표시 시간 | FE·제품 몫 | — | — |
| T2 Incident kind ↔ 제품 이벤트 | `HR_ANOMALY↔HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXIT↔SAFE_ZONE_EXITED`, `FALL_SUSPECTED`는 범위 밖(AI 입구 꺼짐) | 그대로 | — |
| T2 run 없는 경로 | `decision_id` null 허용, `incidents.jsonl`에는 run 귀속 사건만 | 그대로 | — |
| T3 자동전화 제공자·콜백·푸시 OFF 비방장 | 미정 → 구현 보류 | — | **W12 전면** |
| BE 발송본 5 「같은 사건 묶는 범위」 | 열린 사건 + 마지막 감지 5분 창, 안심구역은 재진입으로 종료 | ADR-0038 S-D6 | — |
| BE 발송본 6 「판단 단위 저장」 | 이미 저장됨(`heart_rate_event.status` 샘플별) | — | — |
| ADR-0035 결정 3 「알림=FCM 성공」 | INITIAL_ALERT 기준으로 좁힘, 60초 안 OK는 `alert_delivered=false`가 정상 | S-D10 | — |
| W02 적용 시작일 | 현행 「다음 날」 유지, 문구에 넣지 않음 | 그대로 | — |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |
| 시니어 푸시 설정 항목 | `GENERAL` 하나 | 그대로 | — |
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |
| G02 동시 달성 묶음 | 단일 트리거가 목표 2개를 동시에 적립하는 경로가 없어 G01만 발생 | G01만 | — |
| A 활성화 조건 | 카드·10p는 플래그 OFF로 머지, IRB 확인 뒤 ON | — | — |

## 10. 참고

- ADR-0037(알림센터 모델), ADR-0028(업무 트랜잭션 내 outbox), ADR-0038(안전 흐름의 별도 경계), LLD-0060(NotificationType 레지스트리), `docs/erd/ERD-0001-initial-domain.md`.
- 55차 승인판: `FE-HANDOFF-v0.4` §1-19·§4 25~28, `FE-DELIVERY-PACKAGE-v1.2` §8·§12, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §7·§11, 우선 기준 `HEMLO-REVIEW-v0.5` §6.3·§13.4, 문구 정본 `NOTIFICATION-COPY-CATALOG-v0.4` §4·§9.
- `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29.md` E15·E16, `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §1·§4.6·§5.
