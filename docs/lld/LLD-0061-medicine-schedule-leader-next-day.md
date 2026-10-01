# LLD-0061: 복약 일정 방장 전용·다음 날 적용

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.
> LLD 하나 = PR 하나가 원칙. "하나의 PR에 넣기엔 diff가 너무 많다(파일 15개 이상)"면 LLD를 분리한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #693 |
| 관련 ADR | ADR-0037(알림센터 배경), ADR-0002(가족 접근) |
| 관련 LLD | LLD-0008, LLD-0037 |
| 작성자 | Codex / feature/693 |
| 작성일 | 2026-10-01 |

## 1. 목적 / 배경

현재 같은 가족 보호자 누구나 또는 시니어 본인이 복약 일정을 쓸 수 있고, 수정·삭제는 당일 일정까지 바꾼다. 승인된 정책은 **현재 가족 방장만 등록·수정·삭제**하고, 세 동작 모두 **다음 날 일정부터 적용**해 당일 알람과 인증을 유지하는 것이다. 쓰기 응답은 기존 복약 알람 revision과 적용 시작일을 반환한다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-api`(권한 검사, 복약 일정 명령·조회, 리포지토리, 응답 DTO, Swagger, 테스트) + `widyu-domain`(`MedicineSchedule.create(member, alarmTime, effectiveFrom)` 팩토리 오버로드만 추가). `effectiveFrom`·`effectiveTo` 컬럼은 이미 있으므로 스키마 변경은 없다. 이 범위는 코디네이터가 2026-10-01 확인했다.
- POST/PUT/DELETE의 방장 권한, 다음 날 유효기간, 응답 `scheduleRevision`·`effectiveFromDate` 계약.
- 오늘 날짜 조회·홈·알람 스냅샷·스케줄러가 실제 오늘 유효한 버전을 사용하도록 정합성 확인. 현행 `/home`의 `effectiveTo IS NULL` 조회는 내일 시작 버전을 오늘 보여 주므로 날짜 기준으로 고친다.

### Out of scope

- M08/M05/M09 알림 문구·센터 저장·전달 방식과 `MEDICATION_SCHEDULE_SYNC` data-only 동기화 신호. 이는 W7에서 구현한다. W8은 기존 `sendAlarmChanged` 호출과 revision 증가를 유지한다.
- 다른 목표의 쓰기 권한, 복약 인증 시간창·포인트 규칙, FE 로컬 알람 구현, 데이터/DDL 마이그레이션.

## 3. 인터페이스 / API

기존 요청 본문(`alarmTime`, `categories`)과 성공 코드·메시지는 유지한다. 쓰기 요청의 `memberId`는 대상 시니어 ID를 명시해야 한다. GET `/daily`, `/monthly`, `/home`, `/{scheduleId}`, `/alarm-sync`의 접근 권한과 응답 필드 계약은 유지한다.

```http
POST   /api/v1/goals/medicine-schedules?memberId={seniorId}
PUT    /api/v1/goals/medicine-schedules/{scheduleId}?memberId={seniorId}
DELETE /api/v1/goals/medicine-schedules/{scheduleId}?memberId={seniorId}
```

POST/PUT 요청 예시(기존 요청 DTO 구조):

```json
{"alarmTime":"08:00","categories":[{"name":"아침 약","medicines":[{"itemName":"등록된 약품명","dose":1}]}]}
```

`data.scheduleRevision`은 해당 시니어의 `Member.medicationAlarmRevision`을 이번 명령에서 1 증가시킨 **그 값 그대로**이다. 새 revision 체계나 별도 증가는 없다. `data.effectiveFromDate`는 서버 기본 시간대(스케줄러와 동일 기준) 기준 내일(`YYYY-MM-DD`)이며 null이 아니다. `traceId`는 기존 래퍼 규칙을 따른다.

| 메서드 | 성공 코드 | `data` (모든 필드 non-null) |
| --- | --- | --- |
| POST | `MEDICINE_2005` | `medicineScheduleId: number`, `scheduleRevision: number`, `effectiveFromDate: string` |
| PUT | `MEDICINE_2006` | `medicineScheduleId: number`(적용될 버전 ID), `scheduleRevision: number`, `effectiveFromDate: string` |
| DELETE | `MEDICINE_2007` | `scheduleRevision: number`, `effectiveFromDate: string` |

```json
{"code":"MEDICINE_2006","message":"약 복용 스케줄 수정 성공","data":{"medicineScheduleId":42,"scheduleRevision":7,"effectiveFromDate":"2026-10-02"},"traceId":null}
```

비방장 보호자·시니어 본인의 쓰기는 403(`AUTH_4030`)이고 방장 쓰기는 200이다. POST의 `MedicineScheduleIdResponse`에 두 필드를 추가하고 PUT/DELETE용 응답 DTO를 `widyu-api/.../dto/response`에 둔다. DTO는 `of()`/`from()` 팩토리로 만든다. PUT이 새 버전을 만들면 새 ID를, 내일 시작 버전을 같은 날 재수정하면 같은 ID를 반환한다. 이 ID가 있어야 클라이언트가 당일 `/home`의 옛 버전 ID에 의존하지 않고 다음 변경을 보낼 수 있다.

## 4. 데이터 모델

새 테이블·컬럼·enum은 없다. `widyu-domain`의 `MedicineSchedule.effectiveFrom/effectiveTo`와 `Member.medicationAlarmRevision`을 그대로 쓴다. 버전 경계는 양끝 포함이다: `effectiveFrom <= D <= effectiveTo`(종료일 null이면 상한 없음). 기존 `create(member, alarmTime)`의 오늘 시작 동작은 보존하고, W8 경로만 시작일을 명시하는 오버로드를 사용한다.

| 변경 대상 | 변경 |
| --- | --- |
| `MedicineSchedule` (`widyu-domain`) | `create(member, alarmTime, effectiveFrom)` 오버로드. 컬럼·`isEffectiveOn()`·`startedOn()` 의미 유지 |
| 복약 응답 DTO (`widyu-api`) | `scheduleRevision: long`, `effectiveFromDate: LocalDate` 추가. POST/PUT은 적용될 `medicineScheduleId`도 반환 |
| `MedicineScheduleRepository` (`widyu-api`) | 오늘 `/home` 조회를 날짜 유효기간 조건으로 맞춤. 스케줄러의 날짜 조건 쿼리는 유지·검증 |

## 5. 처리 흐름

1. 컨트롤러가 현재 회원을 확인한다. 시니어 쓰기는 `FORBIDDEN`; 보호자 쓰기에서 대상 `memberId`가 없으면 `BAD_REQUEST`로 거부한다. 이후 세 명령 모두 `FamilyAccessService.verifyLeaderAccess(guardianId, targetMemberId)`를 호출한다. `@ValidateFamilyAccess`의 본인/`memberId` null 통과만으로 쓰기 권한을 판정하지 않는다. `verifyLeaderAccess`는 가족 연결과 `isLeader`를 검사하며, 시니어가 자신의 ID를 `guardianId`로 넘겨도 보호자 membership이 없어 `FORBIDDEN`이다. 명시적 역할 검사로 시니어의 `memberId` 생략 요청도 403이 된다. 조회의 기존 `@ValidateFamilyAccess` 정책은 유지한다.
2. 서비스의 각 쓰기 메서드는 기존 `@Transactional`과 대상 `Member`의 `PESSIMISTIC_WRITE` 잠금을 유지한다. `today = LocalDate.now()`, `tomorrow = today.plusDays(1)`을 한 명령에서 한 번 계산한다. 소유자·현재 버전 검증과 기존 400/403 경계는 유지한다.
3. 등록: `MedicineSchedule.create(targetMember, alarmTime, tomorrow)`로 저장한다. 오늘 조회·알람에는 없고 내일 처음 유효해진다.
4. 수정: 내일 시작하는 현재 버전(`startedOn(tomorrow)`)이면 시간·약 조합을 in-place로 바꾼다. 그 외 현재 버전은 **오늘까지** `closeAsOf(today)`로 마감하고 내일 시작하는 새 버전을 저장한다. 현재 `startedOn(today)` 분기는 내일 시작 버전에 대해 false이므로 그대로 두면 같은 날 재수정 때 빈 유효기간 버전을 만들고, 오늘 시작 버전을 in-place 수정하면 당일 알람을 바꾼다. 따라서 분기를 내일 시작 여부로 바꾼다. `isEffectiveOn(date)`과 `startedOn(date)`의 도메인 의미는 변경하지 않는다.
5. 삭제: 오늘 유효한 현재 버전은 `closeAsOf(today)`로 마감한다. 아직 시작하지 않은 내일 버전도 `closeAsOf(today)`로 마감해 유효한 날짜가 없게 한다. 오늘 알람은 남고 내일은 중단된다. 날짜 조건 조회는 이 빈 유효기간 버전을 제외한다.
6. 당일 `MedicationProof`는 오늘까지 유효한 **옛 버전 ID에 그대로 둔다**. 기존 `moveTodayProofsToNewSchedule(...)`은 새 버전이 내일 시작하므로 W8 수정 경로에서 호출하지 않는다. 오늘 인증 완료 여부·일별/월별 통계·오늘 `alarm-sync.completed`가 옛 버전과 일치해야 한다.
7. 쓰기 성공 시 기존 `sendAlarmChanged` 경로에서 잠긴 대상 `Member.medicationAlarmRevision`을 1회 증가시키고, 그 값을 응답에 쓴다. 일정 저장·revision·기존 변경 신호 enqueue는 같은 트랜잭션에 둔다. W7이 알림 구현을 바꾸더라도 동일 revision 계약을 사용한다. 이 LLD는 M08/M05/M09의 발송 구현을 정의하지 않는다.
8. 날짜별 조회·오늘 알람 스냅샷·스케줄러는 `effectiveFrom <= date <= effectiveTo`인 버전을 사용한다. `/home`도 오늘 기준으로 바꿔 내일 버전이 오늘 카드에 나타나지 않게 한다. 내일 시작 버전은 `/daily?date=내일`에서 조회할 수 있다.

## 6. 예외 / 에러 처리

| 상황 | HTTP / 코드 | 처리 |
| --- | --- | --- |
| 비방장 보호자, 가족 밖 보호자, 시니어 본인의 POST/PUT/DELETE | 403 / 기존 `FORBIDDEN` (`AUTH_4030`) | 저장·revision 증가·변경 신호 없음. 새 에러 코드 없음 |
| 보호자 쓰기 요청의 대상 `memberId` 누락, 대상/스케줄 부재, 종료된 옛 버전 수정·삭제, 잘못된 시간·약품 | 400 / 기존 `BAD_REQUEST` | 기존 세부 메시지 유지. 대상 ID 누락은 명시적으로 400 |
| 대상 시니어와 스케줄 소유자 불일치 | 403 / 기존 `FORBIDDEN` (`AUTH_4030`) | 저장·revision 증가 없음 |
| 인증 실패 | 401 / 기존 인증 오류 | 기존 전역 처리 사용 |

## 7. 인수조건 (Acceptance Criteria)

- [x] E10: POST/PUT/DELETE 모두 비방장 보호자 403(`AUTH_4030`), 시니어 본인 403(`AUTH_4030`), 현재 가족 방장 200이다. 거부된 명령은 일정과 revision을 바꾸지 않는다.
- [x] E11: 등록한 일정은 오늘 조회·오늘 알람에 없고 내일 날짜 조회·알람에는 나타난다.
- [x] E11: 오늘 유효한 일정을 수정하면 당일 알람 시간·약 조합·인증 상태는 기존 내용이고, 내일은 새 버전의 시간·약 조합이다. 오늘 시작 버전도 in-place로 바꾸지 않는다.
- [x] E11: 내일 시작 버전을 같은 날 다시 수정하면 그 버전 안에서 수정되고 중복 버전이 생기지 않는다.
- [x] E11: 오늘 유효한 일정을 삭제해도 당일 알람은 유지되고 내일부터 중단된다. 내일 시작 버전의 삭제는 오늘·내일 모두에 알람을 만들지 않는다.
- [x] POST/PUT/DELETE 성공 응답은 `scheduleRevision`과 다음 날 `effectiveFromDate`를 non-null로 반환한다. POST/PUT은 적용될 `medicineScheduleId`를 반환한다. revision은 해당 시니어 `Member.medicationAlarmRevision`과 같고 명령당 한 번만 증가한다.
- [x] 오늘의 `MedicationProof`는 기존 버전에 남고, 오늘 일별 조회·월별 통계·`alarm-sync.completed`에 계속 반영된다.
- [x] `findByAlarmTimeAndStatusEffectiveOn`은 오늘에는 옛 버전, 내일에는 새 버전을 고른다. 등록·삭제 사례도 날짜 경계에서 검증한다. `/home`과 `alarm-sync`는 오늘 유효한 버전을 보여 준다.
- [x] `MedicineScheduleDocs`의 권한 설명과 세 성공 응답 예시·주요 400/403 응답을 새 계약으로 갱신한다.
- [x] JUnit 5 + Mockito(`given(...).willReturn(...)`)와 필요한 리포지토리 통합 테스트가 위 경계를 검증한다. 한글 언더스코어 메서드명, `@DisplayName("<행위>하면 <결과>한다")`, 상태 검증 우선 규칙을 따른다.
- [x] `./gradlew compileJava`와 `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **LLD-0008 변경:** 기존 "수정·삭제는 오늘부터" 규칙을 #693의 복약 일정 쓰기에서는 "내일부터"로 대체한다. 날짜별 유효기간 모델과 과거 버전 보존은 유지한다. 당일 생성/수정분 in-place 규칙은 "내일 시작 버전의 같은 날 재수정"으로 좁힌다. `/home`의 `effectiveTo IS NULL` 조회는 내일 버전을 오늘 표시하므로 오늘 유효기간 조회로 교체한다.
- **LLD-0037 영향:** `Member.medicationAlarmRevision` 잠금·증가와 `alarm-sync` 형식은 유지한다. 오늘 인증을 새 버전으로 옮기던 규칙은 새 버전이 내일 시작하므로 제거한다. 오늘 스냅샷은 옛 버전 ID와 그날 인증 키를 유지해야 한다. W7의 M08/M05/M09·동기화 신호는 동일 revision 및 `effectiveFromDate`를 이어받는다.
- 시간대는 기존 운영 설정(JVM 기본)을 따르며 스케줄러·조회와 같은 시계를 쓴다.
- 구현 착수 전 worktree에서 `closeAsOf(today.minusDays(1))`는 `MedicineScheduleService`의 **수정 1곳·삭제 1곳, 총 2곳**이었다. 작업 지시의 "수정 2곳·삭제 1곳"과 달라 원본 소스 기준으로 기록한다.
- `MedicineSchedule`의 **엔티티 코드에는 팩토리 오버로드를 추가하지만 DB/DDL/ERD 변경은 없다**. 엔티티 변경이므로 `./gradlew compileJava`로 Q 클래스 생성을 확인한다. MySQL ENUM·`FcmCategory`는 변경하지 않는다.
- API 응답 필드 추가와 PUT/DELETE의 `data: null`→객체 변경은 FE 계약 변경이다. FE는 `effectiveFromDate`까지 기존 당일 알람을 유지하고 revision 기준 재조회에 사용한다. 조회 접근 권한은 현행 유지한다.

## 9. 미결정 사항 (Open Questions)

- W8 미결정 사항 없음(복약 일정 권한·다음 날 적용은 50·54차에서 확정). 전체 계획 §5 가정표 중 W8 구현을 막는 항목은 없다. §5의 관련 가정은 아래와 같으며 W7 범위다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
| --- | --- | --- | --- |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |

## 10. 참고

- Issue #693; [LLD-0008](LLD-0008-medicine-schedule-versioning.md); [LLD-0037](LLD-0037-medication-alarm-sync.md); [ADR-0002](../adr/ADR-0002-auth-jwt-family-access.md); ADR-0037(알림센터 모델, W1 설계).
- 알림센터 최종 승인판: `HEMLO-REVIEW-v0.5` §6.2·§10(우선), `FE-HANDOFF-v0.4` §1-9·§8, `FE-DELIVERY-PACKAGE-v1.2` §8, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §11, `NOTIFICATION-COPY-CATALOG-v0.4` §3.2·§11. 코드 변경점 표 E10·E11.
- `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §1·§2 W8·§4.6·§5; 코드 기준 `MedicineScheduleController`, `MedicineScheduleService`, `MedicineScheduleRepository`, `FamilyAccessService`, `FamilyAccessAspect`.
