# LLD-0097: 알림 외부 분류를 기존 카테고리로 복원

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #891 |
| 관련 ADR | ADR-0037 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

앱에 노출된 34종 상세 알림 타입을 예전 카테고리 방식으로 돌린다. 이동 경로와 읽음 ID는 따로 전달하므로 일반 알림마다 상세 종류를 앱에서 처리하지 않는다.

## 2. 범위

- `widyu-api`: FCM 최종 data 정규화, 센터 응답 DTO·Swagger·테스트.
- 내부 `NotificationType`은 기존 데이터와 발송·보존·필터 정책용 메타데이터로 유지한다. 앱에 상세 분류로 노출하지 않는다.
- 알림 ID·읽음·프로필 이미지·경로·필터·안 읽은 수·푸시 설정·우선순위는 유지한다.

## 3. 인터페이스 / API

- `GET /api/v1/fcm`의 기존 category·notificationId·isRead·path를 유지한다.
- `GET /api/v1/notifications` 항목에서 상세 `type`을 제거하고 `category`는 저장된 `FcmCategory.name()`으로 반환한다. 저장값이 없으면 타입의 기존 카테고리, 둘 다 없으면 `ALL`이다. 조회용 필터·unreadCounts는 기존 정책을 따른다.
- 일반 FCM data의 상세 `notificationType`과 일반 `type`을 제거한다. 이동은 deepLink로 처리한다.
- 기능 트리거는 유지한다: 본인확인 `EMERGENCY_CONFIRM_REQUEST`, 복약 알람·일정·동기화 `MEDICATION_SCHEDULE_CHANGED`, 보호자 심박 위급 `HEART_RATE_EMERGENCY`.

## 4. 데이터 모델

테이블·컬럼·저장 enum·DDL 변경 없음. 기존 대기 outbox도 HTTP 직전에 정규화한다. 기존 센터 행은 DTO에서 구 카테고리로 응답한다.

## 5. 처리 흐름

1. 내부 타입에 따른 저장·푸시 자격·TTL·우선순위는 유지한다.
2. HTTP data에서 notificationType을 제거한다. 내부 타입 또는 기존 data.type이 상세 종류이면 필요한 구 트리거로 바꾸거나 일반 type을 제거한다.
3. 새/이전 대기 행·TTL 유무·직접 전송에 같은 처리를 적용한다. ID·사건·revision·경로·priority는 보존한다.
4. 센터 DTO의 상세 type 필드를 삭제하고 구 카테고리를 반환한다.

## 6. 예외 / 에러 처리

알 수 없는 기존 caller data.type은 그대로 보존한다. 카테고리 없는 과거 행은 ALL이다. 사건 응답·복약 revision 동기화·Android high를 변경하지 않는다.

## 7. 인수조건

- [x] AC1: 모든 내부 타입의 목록 JSON에 상세 type이 없고 기존 카테고리를 반환한다.
- [x] AC2: 모든 내부 타입의 FCM JSON에 notificationType이 없고 일반 type은 생략한다.
- [x] AC3: 이전 payload·TTL 유무에서 ID·경로·추가 data·필수 트리거를 보존한다.
- [x] AC4: 목록·읽음·필터·안 읽은 수·전송 우선순위 회귀 테스트를 통과한다.
- [x] AC5: Swagger·정적 규칙·컴파일·변경 범위 테스트와 자체 검수를 확인한다. 전체 하네스는 아래 환경 한계를 기록한다.

검증: 로컬 Java 21에서 HTTP payload·outbox·알림센터·심박 사건 지정 테스트와 정적 하네스가 통과했다. 전체 하네스는 정적·컴파일 통과 후 `DegradedEmergencyStreamRedisTest.connect`의 Redis 연결 대기를 확인해 중단했다(exit 130). 전체 테스트 성공을 뜻하지 않으며 CI에서 최종 확인한다. 실제 FCM·앱·운영 검증은 미실행이다.

## 8. 영향 범위 / 마이그레이션

SQL·데이터 삭제는 없다. 상세 type을 읽던 앱은 category와 경로를 사용하도록 맞춰야 한다. 내부 정책 메타데이터 제거는 하지 않는다.

## 9. 미결정 사항

없음. 사용자 요청에 따라 외부 분류를 구 방식으로 복원한다.

## 10. 참고

- [기존 목록](LLD-0094-fcm-legacy-list-response.md)
- [타입 도입 이력](LLD-0060-notification-type-registry.md)
- [경로·필수 트리거](LLD-0076-guardian-deeplink-paths.md)
