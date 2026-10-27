# LLD-0095: 시니어 푸시 설정 네 항목

> #881의 API, 발송 자격, 기존 데이터 이행을 검증하는 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #881 |
| 관련 ADR | ADR-0037 결정 5 |
| 작성자 | Codex / feature/881 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

시니어 앱은 앨범·목표·홈·기타 푸시를 각각 켜고 끄는 화면을 제공한다. 서버는 현재 `GENERAL` 하나만 반환하고 나머지 변경을 거부한다. 시니어 설정을 화면과 맞추면서 보호자 네 그룹 및 필수 안전 알림 계약을 유지한다.

## 2. 범위

### In scope

- `widyu-domain`: 시니어용 `ALBUM`, `GOAL`, `HOME`, `ETC` 설정 그룹.
- `widyu-api`: 본인 설정 GET/PATCH, 수신자 타입별 발송 전 판정, Swagger, 테스트.
- `scripts/mysql`: 기존 시니어 `GENERAL` 값 이행 SQL. 운영에서 자동 실행하지 않는다.

### Out of scope

- 보호자 네 그룹, 다른 회원의 설정 조회·변경, OS 권한. 공통 문자는 FCM이 아닌 SMS 채널이므로 #881에서 전송 정책을 바꾸지 않는다.

## 3. 인터페이스 / API

`GET /api/v1/fcm/settings`는 시니어에게 `ALBUM`, `GOAL`, `HOME`, `ETC` 네 항목을 순서대로 반환한다. 기존 응답 필드와 `FCM_2010` 래퍼를 유지한다. `PATCH /api/v1/fcm/settings`는 `{"group":"ALBUM","enabled":false,"policyRevision":4}`처럼 본인 항목 하나를 바꾸고 `FCM_2011`로 반환한다. 시니어가 보호자 그룹 또는 `GENERAL`을 PATCH하면 `FCM_4001`이다. 보호자 계약은 LLD-0065와 같다.

## 4. 데이터 모델

`member_notification_setting.category`는 `VARCHAR(32)`이며 새 enum 값을 저장한다. 새 컬럼과 테이블은 없다. 회원×그룹 유일성과 기본 켜짐, `member.notification_policy_revision`을 유지한다. 기존 시니어 `GENERAL=false`는 네 그룹 모두 `false`로 옮겨 전체 푸시 거부를 보존한다. `GENERAL=true`는 기본 켜짐과 같아 새 행을 만들지 않는다. 보호자 `GENERAL`은 유지한다. 과거 개별 OFF 행은 LLD-0065 마이그레이션에서 삭제돼 복원할 수 없다.

## 5. 처리 흐름

1. GET은 인증 본인의 회원 타입으로 표시 그룹을 고른다. 행이 없으면 켜짐이며 모든 행이 같은 현재 revision을 갖는다.
2. PATCH는 회원 행을 잠그고 revision과 역할별 허용 그룹을 검사한다. 실제 값이 바뀔 때만 본인 설정을 저장하고 revision을 올린다.
3. typed outbox는 타입과 수신자 역할로 설정 그룹을 정한다. 보호자는 `NotificationType.settingGroup()`을 유지한다. 시니어는 `ALBUM→ALBUM`, `TARGET/HEALTH_SCHEDULE/WALK/MEDICINE_SCHEDULE→GOAL`, `HEART_MESSAGE→HOME`, `ETC→ETC`로 분기한다. 나머지 카테고리는 설정 그룹이 없고, `NONE`·`DATA_ONLY`는 설정을 건너뛴다. type 없는 레거시 outbox도 같은 역할별 카테고리 매핑을 쓴다. 긴급 본인확인 `SAFETY_SELF_CHECK`는 기존 안전 예외를 유지해 HOME OFF에도 발송한다.
4. claim과 preflight는 같은 자격 판정을 사용한다. OFF는 푸시만 취소하고, 센터 저장 타입의 행은 남긴다.

## 6. 예외 / 에러 처리

| 조건 | 결과 |
| --- | --- |
| 잘못된 그룹·시니어의 보호자 그룹·`GENERAL` 변경 | `FCM_4001` / 400, 상태 불변 |
| 낡은 revision | `FCM_4091` / 409, 상태 불변 |
| 방장 필수 안전 그룹 끄기 | 기존 `FCM_4090` / 409 유지 |
| OFF·토큰 없음 | 해당 푸시 전송 없음. 센터 행은 타입 정책대로 유지 |

## 7. 인수조건

- [x] AC1: 시니어 GET은 네 독립 값을, 보호자 GET은 기존 네 그룹을 반환한다.
- [x] AC2: 시니어 PATCH는 본인 한 값과 revision만 바꾸며 잘못된 그룹·낡은 revision을 거부한다.
- [x] AC3: 시니어 typed·legacy outbox는 해당 그룹을 claim·preflight에서 따르고, 보호자·필수 안전·data 전용 흐름은 유지한다.
- [x] AC4: 푸시 OFF에도 센터 저장 타입의 행은 남는다.
- [ ] AC5: 제안한 데이터 이행 SQL의 H2 매핑·재실행 테스트는 통과했다. 제품 확인과 운영 MySQL 검증은 남았다.
- [ ] AC6: Swagger와 변경 모듈 지정 테스트·정적 하네스는 통과했다. 전체 하네스는 Redis 가능한 환경에서 재실행해야 한다.

## 8. 영향 범위 / 마이그레이션

운영의 `member_notification_setting.category`는 선행 LLD-0065에 따라 `VARCHAR(32)`이어야 한다. `scripts/mysql/migrate_senior_notification_settings.sql`은 시니어 `GENERAL=false`만 네 OFF 행으로 복사한 뒤 시니어 `GENERAL`을 삭제한다. 새 그룹 행이 이미 있으면 사전 검사에서 중지한다. 구 앱 설정 쓰기 중지→백업·사전 검사→데이터 이행→새 서버·앱 계약 전환 순서다. 운영 SQL은 자동 실행하지 않는다. 이행 중 구 앱의 `GENERAL` 쓰기가 계속되면 상태가 갈라진다.

## 9. 미결정 사항 (Open Questions)

- 제품 확인 대기: HOME OFF에도 긴급 본인확인 푸시를 보내는 기존 안전 예외를 유지할지 확인한다. 현재 구현은 기존 예외를 보존한다.
- 제품 확인 대기: 공통 문자 스위치가 제어할 SMS 대상과 수신 설정의 저장·적용 범위를 정한다. #881의 FCM 구현에는 포함하지 않았다.
- 데이터 이행 제안: 기존 시니어 `GENERAL=false`를 네 OFF 항목으로 옮긴다. 과거 개별 OFF 상태 복원은 불가능하므로 배포 전 제품 확인이 필요하다.

## 10. 참고

- [기존 설정 설계](LLD-0065-notification-setting-groups.md)
- [알림센터 결정](../adr/ADR-0037-notification-center-model.md)
- [이슈 #881](https://github.com/GB-able/WIDYU-server/issues/881)
