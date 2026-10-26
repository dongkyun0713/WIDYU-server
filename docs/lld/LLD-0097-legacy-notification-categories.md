# LLD-0097: 상세 알림 enum 제거와 기존 카테고리 복원

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #891 |
| 관련 ADR | ADR-0037 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

이동 경로와 읽음 ID를 따로 받는 앱에 상세 알림 34종이 추가됐다. 사용자 요청에 따라 상세 종류의 등록 enum을 제거하고 기존 카테고리로 돌아간다. 외부 출력만 줄였던 최초 구현은 이 문서의 최종 범위를 충족하지 못한다.

## 2. 범위

- `NotificationType.java`와 소비 코드를 제거한다. 분류는 기존 `FcmCategory`를 사용하고 조회 전용 UNREAD는 유지한다.
- 전달 방식·우선순위·보존·푸시 자격·문구·경로는 일반 값 객체 `NotificationPolicy`로 넘긴다. 34종 이벤트 상수를 다른 enum으로 옮기지 않는다.
- 기존 34개 저장 문자열을 읽는 호환 변환기만 남긴다. 새 이벤트 분류나 신규 저장값 등록에 사용하지 않는다.
- 하나의 enum이 엔티티·전송·서비스를 직접 연결하므로 이를 모두 수정해야 컴파일된다. 15파일 초과 분리를 검토했으나, 중간 PR에서 상세 enum이 남는 단계별 이전 대신 하나의 제거 목적 안에서 API/domain·테스트를 함께 변경한다.

## 3. 인터페이스 / API

- 기존 `GET /api/v1/fcm`의 category·notificationId·isRead·path를 유지한다.
- `GET /api/v1/notifications`에서 상세 type은 생략하고 기존 category를 반환한다. 필터·unreadCounts·읽음·페이지 계약은 유지한다.
- FCM data에서 notificationType과 일반 상세 type을 제거한다. ID·eventId·경로·revision은 유지한다.
- 기능 트리거는 본인확인 EMERGENCY_CONFIRM_REQUEST, 복약 MEDICATION_SCHEDULE_CHANGED, 보호자 심박 위급 HEART_RATE_EMERGENCY를 유지한다. 독립 caller의 알 수 없는 type은 보존한다.
- 시니어 설정 ALBUM/GOAL/HOME/ETC 복원은 이미 머지된 #886을 따른다.

## 4. 데이터 모델

- `FcmNotification.policy`는 기존 type 컬럼에 정책 JSON을 저장한다. `FcmOutbox.notificationPolicy`는 기존 notification_type 컬럼을 재사용한다. 두 컬럼을 nullable TEXT로 바꾼다.
- JPA AttributeConverter가 새 JSON과 이전 상세 문자열을 읽는다. null은 기존 레거시 폴백이다. 깨진 JSON·미등록 이전 문자열은 예외로 드러낸다.
- 센터 필터 `center_filter VARCHAR(16)`을 추가한다. 저장 시 정책의 필터를 스냅샷으로 남기고 목록/안읽은 수가 같은 컬럼을 조회한다. 기존 type NULL 행은 종전 category 폴백을 사용한다.
- 필터 backfill은 이전 enum 매핑을 그대로 따른다. 포인트·가족·본인확인·동기화의 NULL 필터도 유지한다. 정책을 읽기 위한 JSON 쿼리나 새 설정 테이블은 도입하지 않는다.

## 5. 처리 흐름

1. 호출자가 기존 카테고리와 정책 값을 만든다. 문구 코드는 기존 승인된 내용을 선택하는 값이며 앱 분류로 보내지 않는다.
2. enqueue가 센터 저장/푸시 여부·보존을 결정하고 정책 값과 payload를 함께 저장한다.
3. claim/재시도는 저장된 정책 JSON 또는 이전 문자열을 복원해 설정과 전송 우선순위를 판정한다.
4. HTTP 조립 시 상세 분류를 제거하고 필요한 기능 트리거·ID·경로를 보존한다.
5. 복약의 알림·동기화 작업은 각각 문구 코드와 sync 접미사로 eventId를 구분한다. 같은 카테고리로 통합해도 서로 덮어쓰지 않는다. 시간별 알림도 문구 코드를 키에 포함한다.
6. 지표의 type 라벨은 기존 카테고리로 집계한다. 상세 이벤트별 지표 분해는 사라진다.

## 6. 예외 / 에러 처리

- 정책을 저장한 기존 행은 이전 문자열로 읽을 수 있으나 신앱이 쓴 JSON을 구 enum 앱이 읽을 수는 없다.
- 신앱의 일반 정책에 type을 임의로 넣어도 전송 직전에 제거한다.
- TTL 없는 직접 전송·저하 전송에도 동일 정규화를 적용한다. 본인확인 PUSH_ONLY 등 기존 저장 예외는 유지한다.

## 7. 인수조건

- AC1: 상세 enum 파일·Java 참조가 없고 분류는 기존 FcmCategory를 사용한다.
- AC2: 이전 34종의 전달·우선순위·보존·설정·표시·경로·문구 속성이 유지되며 JSON 왕복과 손상 입력을 검증한다.
- AC3: FCM/목록 JSON에 상세 타입이 없고 기능 트리거·읽음 ID·경로를 보존한다.
- AC4: H2 저장/claim/재시도·센터 필터/count·읽음·복약 알림/동기화 분리·위급 경로 회귀 검증을 통과한다.
- AC5: DDL·backfill·배포 순서·롤백 한계를 기록하고 ERD·Swagger·컴파일·정적 규칙·자체 검수를 확인한다.

검증(2026-10-26): domain 전체 113건 실패 0, 그중 정책 변환·속성 보존 71건. API 지정 657건 실패 0·skip 5(H2/loopback HTTP·Mockito, FCM/센터/목표/사건/심박/포인트·저하 경로). 최초 지정 528건 중 5건, 다음 실행 1건이 상세 타입 기대값 때문에 실패했고 최종 계약에 맞춰 수정 후 위 범위를 통과했다. 전체 로컬 하네스는 정적 규칙·compileJava·domain 통과 뒤 DegradedEmergencyStreamRedisTest.connect의 Redis 연결 대기를 jcmd로 확인하고 중단(exit 130)했다. 해당 wrapper/worker 종료를 확인했다. 실제 MySQL DDL·실기기 FCM은 미실행이다. CI는 최종 PR HEAD에서 별도 확인한다.

자체 검수: AC1~AC5 대응 구현·테스트 확인. 상세 enum 삭제와 Java 참조 부재, 이전 34종 정책 속성, JPA JSON 저장/복원·이전 문자열 읽기·필터/count, 복약의 표시/동기화 eventId 분리, 위급 저하 경로·설정/인가·읽음 가드·HTTP 우선순위를 검토했다. ERD·DDL·Swagger 계약과 정적 규칙·diff check를 확인해 APPROVE한다. SQL 실행은 배포 전 별도 검증 대상으로 남기며 로컬 Redis 중단을 전체 테스트 성공으로 기록하지 않는다.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/alter_fcm_notification_policy_snapshot.sql`을 따른다. 애플리케이션과 워커를 중지하고 대상 두 테이블을 백업한 뒤, type/notification_type TEXT 전환 → center_filter 추가 → 기존 문자열 backfill → 집계 대조 → 신앱 배포 순서다. 구앱이 쓰는 동안 backfill하면 새 행의 필터가 누락되므로 혼합 쓰기를 허용하지 않는다. 스크립트는 1회 실행용이며 실제 실행은 이 PR에 포함하지 않는다.

MySQL DDL은 자동 커밋된다. 신앱이 JSON을 쓴 후 구앱으로 단순 롤백할 수 없으며 쓰기 중지·백업 복구를 계획해야 한다. 본 변경에 데이터 삭제는 없다. 정책 JSON 반복 저장으로 행 크기가 증가하며 설정 테이블·외래키를 추가하는 대안보다 읽기와 재시도 복원을 단순하게 유지하는 쪽을 선택했다. 매 배포에서 이전 정책 JSON 필드와 의미의 호환성을 유지해야 한다.

## 9. 미결정 사항

없음. 머지·배포는 별도 작업이다.

## 10. 참고

- [기존 목록](LLD-0094-fcm-legacy-list-response.md)
- [상세 타입 도입 이력](LLD-0060-notification-type-registry.md) — 레지스트리 설계는 이 문서가 대체한다.
- [경로·필수 트리거](LLD-0076-guardian-deeplink-paths.md)
