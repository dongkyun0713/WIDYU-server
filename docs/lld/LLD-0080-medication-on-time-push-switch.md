# LLD-0080: 정각 복약 서버 푸시 설정 스위치

> Low-Level Design. #738 구현과 검수 기준. B12 추천안 ⓑ는 구현 가정이며 운영 정책 확정이 아니다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-11 승인 후 구현 검수) |
| Issue | #738 |
| 관련 ADR | ADR-0038(S-D7 플래그 운용 방식), ADR-0039(작성 중) |
| 관련 LLD | LLD-0068(복약 알림 정합, PR #718) |
| 작성자 | Codex / feature/738 |
| 작성일 | 2026-10-11 |

## 1. 목적 / 배경

LLD-0068은 정각 서버 푸시를 제거했지만 위듀 앱의 기기 예약 알람은 아직 실기기 확인·배포가 끝나지 않았다. 정본 추가본 §1-A11은 그 전까지 develop의 정각 푸시를 유지하도록 요구한다. #718의 다른 복약 변경을 유지한 채 정시 분기만 운영 설정으로 되살리고, 앱 알람 준비 후 끌 수 있게 한다(B12 추천 ⓑ).

## 2. 범위

### In scope

- 변경 모듈은 `widyu-api`만이다. `MedicineScheduleNotificationListener`의 정시 조회·미인증 시니어 발송 분기, `SensorProperties`, `application-sensor.yml`, 관련 단위·설정 바인딩 테스트를 변경한다.
- 제안 설정 이름은 `sensor.medication.on-time-push`, 제안 환경변수 이름은 `SENSOR_MEDICATION_ON_TIME_PUSH`다. YAML 기본값 **true**로 서버를 올리면 정각 레거시 푸시가 활성화된다.
- ON에서는 정각 레거시 푸시를 복원하고 +10분 M02, +20분 M03, +30분 M04 흐름을 계속 실행한다. OFF에서는 정각 분기를 실행하지 않고 후속 흐름을 그대로 실행한다.

### Out of scope

- 위듀 앱의 기기 예약 알람 구현·실기기 검증, 운영 설정 변경 실행, #718의 M02/M03/M04·M05/M08/M09·`MEDICATION_SCHEDULE_SYNC` 계약 변경.
- 새 HTTP API, `NotificationType`/`FcmCategory` 값, 알림센터 정책·FCM 공통 전송 경로, DB·ERD 변경.

## 3. 인터페이스 / API

새 HTTP 경로와 요청·응답은 없다. 매분 실행하는 기존 스케줄러의 서버 측 설정만 바뀐다.

| 설정 제안 | 기본값 | ON | OFF |
| --- | --- | --- | --- |
| `sensor.medication.on-time-push` (`SENSOR_MEDICATION_ON_TIME_PUSH`) | `true` | 정각 대상 미인증 시니어에게 기존 정시 FCM 발송 | 정각 스케줄 조회·FCM 발송 0건 |

정시 FCM은 `upstream/develop`의 `MedicineScheduleNotificationListener` 1차 분기를 기준으로 복원한다. 제목은 **`약 복용 시간이에요!`**, 본문은 **`지금 약을 복용하고 인증해주세요.`**, 카테고리는 `MEDICINE_SCHEDULE`, 이미지는 `medicine.png`, `scheme`은 빈 문자열이다. data는 `MedicationAlarmPayload.of(revision)`의 `type=MEDICATION_SCHEDULE_CHANGED`, `revision=<수신 시니어의 revision>`을 그대로 쓴다. 이 메시지는 **`NotificationType`이 없는 레거시 메시지**다. `data.notificationType`을 만들거나 정시용 레지스트리 타입을 신설하지 않는다. `복약 알람 변경`은 정시 푸시 문구가 아니며 사용하지 않는다.

현재 `FcmOutboxService`는 타입이 없는 메시지를 `PUSH_AND_CENTER`로 처리하고, `eventId`가 없으면 UUID를 발급한다. 따라서 정시 발송 시 기존 전송 경로에서는 시니어 센터 행도 생성된다. 센터 행은 신규 제품 알림 타입으로 해석하지 않으며, 이 설계는 공통 전송 정책을 바꾸지 않는다. 센터 표시 정책 변경이 필요하면 별도 결정이 필요하다.

## 4. 데이터 모델

테이블·컬럼·엔티티·MySQL ENUM 변경은 없다. `SensorProperties`에 `Medication(boolean onTimePush)` 중첩 설정을 추가하고 `application-sensor.yml`의 `sensor.medication.on-time-push`를 바인딩한다. 기존 `MedicineSchedule`의 유효기간, `MedicationProof`의 당일 인증 여부, 회원의 `medicationAlarmRevision`을 그대로 읽는다. `FcmCategory`에 값을 추가하지 않는다.

## 5. 처리 흐름

1. `@Scheduled` 진입점은 기존처럼 현재 시각을 한 번 잡아 분 단위로 자른다. `SensorProperties.medication().onTimePush()`가 true면 그 시각의 정시 분기를 먼저 실행한다. false면 정시 저장소 조회도 하지 않는다.
2. ON의 정시 분기는 해당 날짜에 유효한 `ACTIVE` 일정을 `findByAlarmTimeAndStatusEffectiveOn`으로 조회한다. 같은 날짜에 이미 인증된 일정은 제외하고, 나머지 일정의 시니어에게 §3의 레거시 `FcmSendDto`를 보낸다. 날짜는 `dueAt.toLocalDate()`를 사용해 자정 근처에도 현재 일정 조회 규칙과 일치시킨다.
3. 이어서 ON/OFF와 무관하게 `minute - 10분`의 M02, `minute - 20분`의 M03, `minute - 30분`의 M04를 기존 순서·대상·문구·typed FCM payload로 처리한다. 정시 발송은 M02/M03의 `NotificationCopy` 경로를 재사용하지 않으므로 레거시 문구·타입을 유지한다.
4. 기존 스케줄러와 `FcmService.sendMessageToUser` → outbox 트랜잭션을 쓴다. 새 이벤트 리스너·Facade·별도 트랜잭션·재시도 정책을 만들지 않는다. 동일 분 재호출 시 정시 레거시 FCM의 중복 가능성은 기존 develop 동작과 같다.
5. 앱의 기기 예약 알람이 iOS·Android 실기기에서 잠금·절전 상태와 `MEDICATION_SCHEDULE_SYNC` 수신 후 재예약까지 확인되고 배포되면, 운영자가 설정을 **false**로 전환한다. ADR-0038 S-D7의 사전 배포·운영 플래그 전환 패턴을 따른다. 전환 전에는 기본 true를 유지한다.

## 6. 예외 / 에러 처리

| 조건 | 동작 |
| --- | --- |
| ON이지만 해당 시각의 유효한 일정이 없거나 당일 인증 완료 | 정각 푸시 없음. 후속 시점은 기존대로 처리. |
| OFF | 정각 일정 조회·푸시 없음. +10/+20/+30은 기존대로 처리. |
| 시니어 비활성·활성 FCM 토큰 없음·FCM 전송 실패 | 기존 outbox/수신 적격성·재시도·실패 기록을 따른다. 스위치가 후속 알림을 막지 않는다. |
| 설정값 누락 | YAML 기본 `true`로 바인딩한다. 잘못된 운영 설정의 배포 검증은 운영 설정 절차에서 다룬다. |

새 HTTP 오류 코드나 클라이언트 오류 응답은 없다.

## 7. 인수조건 (Acceptance Criteria)

- [x] ON: 미인증이고 당일 유효한 정시 일정 1건에 대해 정각 레거시 시니어 알림을 논리적으로 1건 enqueue한다(기기별 outbox 행 수는 토큰 수에 따른다). 제목·본문·카테고리·data `type`·`revision`이 §3과 같고, `notificationType`은 null이다. 이미 인증했거나 유효하지 않으면 0건이다.
- [x] ON: 같은 조건에서 정각 1건과 +10 M02, +20 M03, +30 M04가 각 시점에 기존 대상에게 간다. 후속 3건의 문구·타입·센터/푸시 정책은 #718 그대로다.
- [x] OFF: 정각 조회·발송은 0건이며 +10/+20/+30은 ON과 동일하게 처리된다. 자정 경계에서도 각 `dueAt`의 날짜에 유효한 일정만 대상으로 한다.
- [x] 실제 `application-sensor.yml` 바인딩 테스트에서 환경변수 미지정 시 `onTimePush=true`, `SENSOR_MEDICATION_ON_TIME_PUSH=false` 지정 시 false를 확인한다.
- [x] JUnit 5 + Mockito 테스트는 한글 언더스코어 메서드명, BDDMockito, 상태 검증 우선 규칙을 따른다. 외부 FCM enqueue 같은 부수효과는 호출 검증으로 확인한다. `SensorProperties` 생성자를 직접 호출하는 기존 테스트 fixture도 새 설정을 반영한다.
- [x] `bash scripts/harness/verify.sh`가 통과한다. HTTP 변경이 없으므로 Swagger 수정은 필요 없다.

## 8. 영향 범위 / 마이그레이션

- #718의 `MedicineScheduleNotificationListener`는 정시 호출이 제거되어 있다. 이 한 분기만 설정 조건 아래 복원한다. ON의 정시 메시지는 레거시 타입 없음·문구·data를 유지하며, M02/M03/M04의 현행 typed 경로는 건드리지 않는다.
- `SensorProperties` record 생성자 변경은 직접 생성하는 테스트 fixture에 컴파일 영향을 준다. 설정 기본값과 운영 오버라이드는 실제 YAML 바인딩 테스트로 검증한다.
- DB 마이그레이션·ERD·`NotificationType`·`FcmCategory` 변경은 없다. 운영에서 OFF로 바꾸는 시점은 앱 실기기 확인·배포 후다. true/false 설정은 서버 설정 배포 시 반영된다.

## 9. 미결정 사항 (Open Questions)

추가본 §4 「9. 복약·건강일정 알림」과 §5 B12는 **미정**이다. 아래 값은 구현 가능한 추천안의 가정이며, 제품·운영 합의로 확정하지 않는다.

| 항목 | 이번 LLD의 제안·확인한 사실 | 필요한 합의 |
| --- | --- | --- |
| B12: #718 분리 또는 스위치 | 정본 추천 ⓑ를 가정해 정시 분기를 스위치로 복원한다. | PR 분리 대신 이 스위치를 채택할지. |
| 설정 이름·환경변수 이름 | `sensor.medication.on-time-push`, `SENSOR_MEDICATION_ON_TIME_PUSH`, 기본 true 제안. | 운영 설정 명명과 배포 환경 반영 방식. |
| OFF 전환 시점 | §1-A11대로 앱 알람의 iOS·Android 실기기 잠금·절전·재예약 확인 및 배포 뒤 운영 전환 제안. | 검증 결과, 앱 배포 확인 주체·시점, 서버 OFF 적용 시점. 미확인 상태에서는 true 유지. |
| 정각 레거시 메시지 | develop의 정확한 정시 문구는 `약 복용 시간이에요!` / `지금 약을 복용하고 인증해주세요.`다. `NotificationType` 없음, data `type=MEDICATION_SCHEDULE_CHANGED`; 현재 공통 경로는 센터 행도 만든다. | 레거시 센터 표시를 그대로 수용할지. 별도 정책 변경이면 별도 LLD가 필요하다. `복약 알람 변경` 문구는 쓰지 않는다. |

미결정 항목의 회신이 기존 가정과 다르면 구현 전에 이 LLD의 인터페이스·인수조건을 함께 갱신한다. 이 문서 작성만으로 운영 설정을 전환하지 않는다.

## 10. 참고

- 정본 `apiDocs/notification/04_추가본_2026-10-05/추가본_AI용.md` §1-A11·§4 「9」·§5 B12(저장소 밖), `BE-GAP-2026-10-05.md` §3, `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §11.
- [LLD-0068](LLD-0068-medication-notification-alignment.md), [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md), ADR-0039(작성 중).
- 현재 브랜치의 `MedicineScheduleNotificationListener`, `SensorProperties`, `application-sensor.yml`; `git show upstream/develop:backend/widyu-api/src/main/java/com/widyu/fcm/event/medicineschedule/listener/MedicineScheduleNotificationListener.java`의 정시 분기.
