# LLD-0096: FCM data의 알림 목록 ID 전달

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #884 |
| 관련 ADR | ADR-0037 결정 2·3 |
| 작성자 | Codex / feature/884 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

앱이 FCM 푸시를 탭했을 때 알림 목록의 같은 항목을 읽음 처리하려면 `message.data.notificationId`가 목록의 `notificationId`와 같아야 한다. 센터 저장형 outbox는 이미 이 ID를 전송하지만, 공통 전송기가 만료 시각을 받지 않는 경우 전달된 ID 대신 오래된 data 값을 보낼 수 있다. ID 직렬화를 TTL 계산과 분리하고 일반 알림의 목록·outbox·FCM 연결을 검증한다.

## 2. 범위

- `widyu-api`: FCM data의 공통 ID 직렬화와 일반 알림의 ID 연결·읽음 권한 테스트, Swagger.
- `widyu-domain`: 본인확인 전달 방식을 기존 `PUSH_ONLY`로 유지하는 회귀 검증. 알림 종류별 저장·표시 정책을 바꾸지 않는다.
- 목록 미저장 푸시에 새 읽음 저장소를 만들거나 목록 행을 추가하는 설계는 포함하지 않는다.

## 3. 인터페이스 / API

목록에 저장되는 알림은 FCM `message.data.notificationId`에 십진 문자열 ID를 보낸다. 값은 `GET /api/v1/fcm`의 `notifications[].notificationId` 및 알림센터 ID와 같다. 앱은 기존 `PATCH /api/v1/fcm/{notificationId}` 또는 알림센터 읽음 API에 이 값을 사용한다. 이 ID는 사건 ID나 기기별 outbox ID가 아니다.

## 4. 데이터 모델

기존 `fcm_notification`과 `fcm_outbox.notification_id`를 사용한다. 테이블·컬럼·보존 기간·센터 생성 정책은 변경하지 않는다.

## 5. 처리 흐름

1. 기존 enqueue가 수신자×이벤트의 센터 행을 만들고 각 기기 outbox에 같은 센터 ID를 저장한다.
2. `FcmDelivery.notificationId`를 공통 FCM data에 넣는다. ID가 있으면 호출자 data에 남은 값보다 우선하며, 만료 시각 유무에 관계없이 문자열로 직렬화한다.
3. 센터 ID가 없는 전달은 임의의 읽음 ID를 만들지 않는다. 본인확인은 기존처럼 푸시 전용이며 사건 응답은 기존 API로 처리한다.
4. 수신자는 전달된 센터 ID로 본인 알림만 읽음 처리한다. 여러 토큰·같은 이벤트 재전송은 기존 센터 ID를 공유한다.

## 6. 예외 / 에러 처리

- 다른 회원의 센터 ID로 읽음 요청 시 기존 not found 동작을 유지한다.
- ID 없는 푸시는 `notificationId`를 생략한다. caller data에 남은 잘못된 ID를 대신 사용하지 않는다.
- FCM 실패·0개 토큰·일반 푸시 OFF 시 센터 저장은 기존 타입 정책을 따른다.

## 7. 인수조건

- [x] AC1: 일반 목록 알림의 여러 기기·중복 enqueue·claim이 같은 센터 ID를 유지한다.
- [x] AC2: TTL 유무에 관계없이 실제 HTTP JSON의 `data.notificationId`가 전달된 센터 ID의 문자열이고, 오래된 data 값을 덮어쓴다.
- [x] AC3: 알림 ID로 본인 읽음 처리가 가능하고 다른 수신자는 변경할 수 없다.
- [x] AC4: 본인확인의 PUSH_ONLY·목록 미저장·미읽음 수와 Android high·안전 채널 정책이 유지된다.
- [ ] AC5: Swagger·지정 테스트·정적 검수 및 전체 CI 결과를 확인한다.

## 8. 영향 범위 / 마이그레이션

새 SQL이나 기존 데이터 백필은 없다. 정상 outbox 경로는 기존처럼 ID를 보내고, 만료 시각 없는 전달에서도 제공된 ID를 정확히 보낸다. 목록에 없는 푸시를 목록에 추가하지 않는다. 과거에 발송한 FCM payload는 수정하지 않는다.

## 9. 미결정 사항 (Open Questions)

없음. 이번 변경은 기존 저장 정책을 보존하고 목록 알림의 동일 ID 전달을 보장한다. 목록 미저장 푸시에 별도 읽음 ID를 만드는 기능은 별도 범위다.

## 10. 참고

- [알림센터 모델](../adr/ADR-0037-notification-center-model.md)
- [이슈 #884](https://github.com/GB-able/WIDYU-server/issues/884)
