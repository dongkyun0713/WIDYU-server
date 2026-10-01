# LLD-0068: 복약 알림 정합

> Low-Level Design. #702 구현과 검수 기준.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #702 |
| 관련 ADR | ADR-0037 (알림센터 모델) |
| 관련 LLD | LLD-0037 (revision·스냅샷), LLD-0061 (방장 권한·다음 날 적용) |
| 작성자 | Codex / feature/702 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 서버는 복약 정시에도 시니어 푸시를 보내고 +10분·+20분 푸시를 알림센터에 저장한다. +30분에는 인증 미확인을 미복용으로 단정하며, 일정 변경은 등록·변경·삭제를 구분하지 않는 옛 문구로 알린다. 정시 기기 알람과 서버 후속 알림을 분리하고, 방장의 일정 변경을 시니어 알림 및 revision 동기화 신호로 전달한다.

## 2. 범위

### In scope

- **구현 선행 조건 충족:** `feature/693` 병합 완료(`cbf151a5`). W8의 `effectiveFromDate`, `scheduleRevision`(=`Member.medicationAlarmRevision`), 방장 전용 403 계약을 그대로 사용한다. PR은 #695·#715 머지 뒤 rebase한다.
- `widyu-api`: `MedicineScheduleNotificationListener`, `MedicineScheduleService.sendAlarmChanged`, `MedicationAlarmPayload`, 관련 테스트. 구 앱 data 호환에 필요한 공통 FCM 조립·전송 경로만 제한적으로 수정한다.
- E7·E8·E9: 정시 서버 푸시 제거, +10/+20 시니어 푸시만, +30 보호자 센터 저장과 `MEDICATION_CHECK`에 따른 푸시.
- E12·E13: 방장의 등록·변경·삭제에 M08·M05·M09 알림과 별도 data-only 동기화 신호. LLD-0061의 `effectiveFromDate`와 `Member.medicationAlarmRevision`을 사용한다.

### Out of scope

- 방장 권한·다음 날 유효기간·쓰기 응답 자체(LLD-0061), FE 기기 알람 구현, 복용 인증 시간창·저장 정책.
- 단말 반영 성공·실패 알림(M06/M07), 보호자별 수신 대상 편집, 새 FCM 카테고리, 알림센터·outbox 스키마 변경.

## 3. 인터페이스 / API

새 HTTP 경로는 없다. 기존 `POST/PUT/DELETE /api/v1/goals/medicine-schedules` 계열과 `GET /api/v1/goals/medicine-schedules/alarm-sync`를 쓴다. LLD-0061의 쓰기 응답 `scheduleRevision`은 시니어의 `Member.medicationAlarmRevision` 값이고 `effectiveFromDate`는 다음 날이다. FCM data의 revision·날짜는 문자열이다.

| 이벤트 | 수신자 | 전달·센터 | 문구 | 딥링크 |
| --- | --- | --- | --- | --- |
| 정시 `MEDICATION_DUE` | 시니어 기기 | 서버 푸시 0·센터 0 | 기기 M01 | 복약 인증 |
| +10 `MEDICATION_REMINDER_10` | 미인증 시니어 | `PUSH_ONLY`·센터 0, `GENERAL` | M02 | `widyu://medication/proof/{entityId}` |
| +20 `MEDICATION_REMINDER_20` | 미인증 시니어 | `PUSH_ONLY`·센터 0, `GENERAL` | M03 | 위와 같음 |
| +30 `MEDICATION_PROOF_MISSING` | 현재 가족 보호자 각각 | `PUSH_AND_CENTER`·보호자별 센터 1, 푸시는 `MEDICATION_CHECK` | M04 | `widyu-care://seniors/{seniorId}/medication` |
| 등록 `MEDICATION_SCHEDULE_CREATED` | 대상 시니어 1명 | `PUSH_AND_CENTER`·`GOAL`·`GENERAL` | M08 | `widyu://medication/schedules` |
| 변경 `MEDICATION_SCHEDULE_CHANGED` | 대상 시니어 1명 | 위와 같음 | M05 | 위와 같음 |
| 삭제 `MEDICATION_SCHEDULE_DELETED` | 대상 시니어 1명 | 위와 같음 | M09 | 위와 같음 |
| 매 쓰기의 `MEDICATION_SCHEDULE_SYNC` | 대상 시니어 기기 | `DATA_ONLY`·센터 0·푸시 설정 무관 | 표시 없음 | 없음 |

`NotificationCopy`는 문구표 v0.4 §3의 제목·본문을 그대로 렌더링한다. 특히 M04는 **`{시니어 이름} 님의 약 복용 인증이 아직 확인되지 않았어요.` / `약을 드셨는지 직접 확인해보세요.`**이다. M02·M03·M08·M05·M09에도 각각 지정된 문구만 쓰며 옛 `복약 알람 변경` 문구나 미복용 단정 문구를 쓰지 않는다. 잠금화면에는 약 이름·복용량을 싣지 않는다.

모든 typed FCM의 `data.notificationType`은 논리 `NotificationType.name()`이다. `data.type`은 레지스트리의 nullable `legacyDataType`이 있으면 그 값이고, 없으면 논리 타입 이름이다. M08/M05/M09·SYNC만 `legacyDataType=MEDICATION_SCHEDULE_CHANGED`로 두어 구 앱의 `type`·`revision` 재조회 계약을 지킨다. M08/M05/M09에는 `revision`, `effectiveFromDate`, `actorDisplayName`을, SYNC에는 `revision`을 싣는다. 모든 FCM에 `eventId`가 있고 센터 미저장 이벤트에는 `notificationId`가 없다. 새 앱은 `notificationType`을 우선 파싱한다. 이 이중 키는 계획 §5의 호환 가정을 실현하는 LLD 제안이다.

## 4. 데이터 모델

새 테이블·컬럼·MySQL ENUM 값은 없다. `Member.medicationAlarmRevision`이 유일한 revision이다. `widyu-domain`의 기존 `NotificationType`에 +10/+20·+30·M08/M05/M09·SYNC와 각 `DeliveryMode`·설정 그룹·딥링크가 있다. `FcmCategory`에는 값을 추가하지 않는다. ADR-0037대로 센터 행은 토큰 수·푸시 설정과 무관하게 enqueue 시점에 수신자×이벤트당 하나, outbox는 기기 토큰별로 만든다.

`MedicationAlarmPayload`를 revision·적용일의 공유 생성 경로로 확장한다. M08/M05의 `entityId`는 적용 버전 schedule ID, M09는 삭제 대상 schedule ID이며 딥링크는 모두 일정 목록이다. +10/+20의 `entityId`는 인증 대상 schedule ID, +30의 `seniorId`는 해당 시니어 ID다. `actorDisplayName`은 현재 방장의 이름이고 조회할 수 없으면 문구표대로 `가족`을 쓴다.

## 5. 처리 흐름

1. 매분 스케줄러에서 정시 분기·정시 FCM 호출을 제거한다. `today`와 현재 시각을 한 번 잡고 날짜 유효기간 조건(`findByAlarmTimeAndStatusEffectiveOn`)으로 +10/+20/+30 스케줄을 조회한다. 각 시점에 인증된 스케줄은 건너뛴다. 내일 시작 버전은 오늘 조회되지 않는다.
2. +10/+20 미인증은 해당 시니어에게 M02/M03 `PUSH_ONLY`를 enqueue하고 센터 행을 만들지 않는다. +30 미인증은 현재 가족의 보호자 각각에게 M04를 enqueue한다. 보호자별 센터 행은 1건이며 `MEDICATION_CHECK` OFF는 outbox preflight에서 푸시만 제외한다. 가족·활성 상태 검사는 기존 경로를 따른다. M04의 `relatedMemberId`·`seniorId`는 대상 시니어다.
3. 동일 시니어·스케줄·날짜·시점 이벤트는 같은 `eventId`를 사용한다. 현행 `VARCHAR(40)` 제한을 지키는 결정적 UUID 문자열로 만든다. +30에는 보호자 ID도 키 재료에 포함한다. M04 센터 행은 `UK(recipient_member_id,event_id)`로 중복을 막는다. +10/+20 `PUSH_ONLY`는 매분 스케줄러 1회 실행을 전제하며 재시작으로 같은 분 재호출 시 중복 enqueue 가능성은 현행과 동일하다. 새 테이블·UK·Redis 키는 만들지 않는다.
4. LLD-0061의 방장 전용 쓰기 트랜잭션은 일정 저장 후 잠근 대상 `Member`의 `incrementMedicationAlarmRevision()`을 명령당 **한 번** 호출한다. 등록/변경(시간·약 조합)/삭제에 M08/M05/M09를 선택해 방장 이름·같은 revision·`effectiveFromDate`를 전달한다. 일정·revision·센터·outbox enqueue는 같은 트랜잭션이다. 거부·롤백된 명령은 알림·SYNC가 없다.
5. 매 쓰기 성공마다 시니어에게 같은 revision의 `MEDICATION_SCHEDULE_SYNC`를 **항상 하나 더** enqueue한다. `DATA_ONLY`는 표시용 notification·센터 행·설정 그룹 판정이 없다. 시니어가 `GENERAL`을 꺼도 M08/M05/M09 센터 행과 SYNC는 남고 보이는 푸시만 제외된다. 앱은 더 높은 revision 수신 또는 앱 열기 때 `alarm-sync`를 재조회하고 당일 알람은 유지한다.
6. `NotificationType`의 nullable `legacyDataType`과 공통 `dataTypeValue()`를 사용한다. `FcmSendDto.dataForEnqueue()`와 `FcmHttpTransport.addTypeData()`는 모든 typed 이벤트에 `data.notificationType=name()`을 넣고 `data.type=dataTypeValue()`를 적용한다. 복약 전용 분기는 공통 조립 코드에 두지 않는다. outbox `notification_type`에는 논리 타입, `data_payload`에는 실제 data를 저장한다. `FcmDelivery.from(row)`의 재시도 복원까지 확인한다.

## 6. 예외 / 에러 처리

| 조건 | 동작 |
| --- | --- |
| 인증 완료, 오늘 유효한 스케줄 없음, 시니어 프로필·연결 보호자 없음 | 해당 시점 알림 없음. 기존 스케줄러 로그 유지. |
| 방장 아닌 쓰기·소유자 불일치·유효하지 않은 일정 | LLD-0061의 403/400. revision·센터·outbox 변경 없음. |
| 보호자 `MEDICATION_CHECK` OFF, 시니어 `GENERAL` OFF, 활성 토큰 없음 | 센터 대상 행은 저장. OFF는 보이는 푸시만 막고 SYNC는 설정과 무관하게 enqueue. 토큰이 없으면 실제 FCM 전송은 없음. |
| FCM 실패·토큰 해제 | 기존 outbox 재시도·실패 기록 사용. 센터 행을 지우거나 M04를 미복용으로 바꾸지 않음. 데이터 전달 오류는 운영 로그에서 추적. |

## 7. 인수조건 (Acceptance Criteria)

- [x] E7: 정시 서버 푸시·센터는 0건이고 정시 M01은 기기 알람 계약으로만 남는다.
- [x] E8: +10/+20 미인증 시 시니어에게 M02/M03 `PUSH_ONLY`가 각각 가고 센터 행은 0건이다. 인증 완료 시 보내지 않는다.
- [x] E9: +30 미인증은 현재 가족 보호자 **각각**의 센터에 1건씩 남는다. `MEDICATION_CHECK` ON에게만 푸시하고 OFF·토큰 없음에도 센터 행은 남는다. 정확한 M04 문구, `seniorId`와 복약 화면 딥링크가 있으며 미복용 단정이 없다.
- [x] E9: 다중 기기·재시도·같은 시점 재호출에도 보호자의 같은 이벤트 센터 행은 1건이고 가족 밖 수신자는 0건이다.
- [x] E12: 방장의 등록/변경(시간·약 조합)/삭제는 대상 시니어 **한 명**에게 각각 M08/M05/M09 `PUSH_AND_CENTER`·`GOAL` 알림을 1건씩 만든다. 방장·다른 보호자에게 결과 알림은 없다.
- [x] E12: 제목에 방장 이름(`actorDisplayName`, 없으면 `가족`)을 넣고 문구표 §3의 정확한 제목·본문을 쓴다. 약 이름·복용량은 잠금화면에 없고 `revision`은 쓰기 응답·`Member` 값과 같으며 `effectiveFromDate`는 다음 날이다.
- [x] E13: 매 쓰기에 같은 revision의 `MEDICATION_SCHEDULE_SYNC` `DATA_ONLY`를 1건 동반한다. 시니어가 `GENERAL`을 꺼도 M08/M05/M09 센터와 SYNC는 남고 보이는 푸시만 제외된다. SYNC는 화면 알림·센터 행이 없다.
- [x] E13: 실제 FCM HTTP payload와 outbox 재시도 data에서 `type=MEDICATION_SCHEDULE_CHANGED`, `notificationType=<논리 타입>`, `revision`, M08/M05/M09의 `effectiveFromDate`를 유지한다. 다른 typed 이벤트 `type`은 바뀌지 않는다.
- [x] 거부·롤백된 쓰기는 revision 증가·센터 행·SYNC가 0건이다. 당일 버전과 다음 날 적용일은 LLD-0061을 따른다.
- [x] JUnit 5 + Mockito(`given(...).willReturn(...)`)와 필요한 outbox/전송 통합 테스트가 위 상태·부수효과·payload를 검증한다. 한글 언더스코어 메서드명, `@DisplayName("<행위>하면 <결과>한다")`, 상태 검증 우선 규칙을 따른다.
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- `MedicineScheduleNotificationListener`의 정시 호출·옛 문구·센터 저장 경로와 `MedicineScheduleService.sendAlarmChanged`의 단일 옛 푸시를 교체한다. 기존 테스트의 시니어 정시 포함 발송 3건 기대값을 갱신한다.
- LLD-0037의 revision·`alarm-sync`·회원 잠금은 유지한다. 일정 쓰기 알림 네 타입의 구 앱 재조회용 legacy `type`·`revision`을 보존한다. +10/+20/+30은 논리 타입 이름을 `data.type`으로 보낸다. LLD-0061의 방장 권한·내일 적용일이 입력 계약이다.
- DDL·ERD 변경은 없다. `FcmCategory`·MySQL ENUM 값은 추가하지 않는다. H2 통과를 운영 ENUM 호환의 증거로 보지 않는다. 공통 FCM 조립 경로의 타 타입 회귀를 검증한다.
- `feature/693` 병합 완료(`cbf151a5`). 방장 전용 403·다음 날 유효기간·쓰기 응답은 그대로 유지한다. PR은 #695·#715 머지 뒤 rebase한다. 새 DB 모델이나 ALTER TABLE은 없다.

## 9. 미결정 사항 (Open Questions)

승인 검수본 v0.5가 제품 동작을 확정했다. 다음은 전체 계획 `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §5의 작업 가정이며 FE 회신이 바뀌면 이 계약을 갱신한다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
| --- | --- | --- | --- |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |
| 시니어 푸시 설정 항목 | `GENERAL` 하나 | 그대로 | — |
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |

위 가정대로 구현했다. 새 앱 구별용 `data.notificationType`은 이 LLD의 계약 제안이며 FE 회신에서 다른 키가 확정되면 payload 계약과 인수조건을 함께 갱신해야 한다.

+10/+20 `PUSH_ONLY`는 매분 스케줄러 1회 실행이 전제다. 재시작 등으로 같은 분에 다시 호출하면 outbox에 동일 이벤트가 중복 enqueue될 수 있으며 현행과 동일한 한계다. M04 센터 행은 수신자·eventId 유니크 제약으로 중복 저장을 막는다.

## 10. 참고

- [LLD-0037](LLD-0037-medication-alarm-sync.md), ADR-0037(승인본은 feature-691), LLD-0061(작성본은 feature-693).
- 알림센터 최종 승인판 `HEMLO-REVIEW-v0.5` §6.2·§13.4·§13.4-7(충돌 시 우선), `FE-HANDOFF-v0.4` §1-8·§1-9·§4 7~11·22~24, `FE-DELIVERY-PACKAGE-v1.2` §8·§11, `NOTIFICATION-CENTER-UX-SPEC-v0.3` §11, `NOTIFICATION-COPY-CATALOG-v0.4` §3.
- `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29.md` E7·E8·E9·E12·E13, `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §1·§4.6·§5.
