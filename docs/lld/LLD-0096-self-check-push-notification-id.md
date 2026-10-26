# LLD-0096: 긴급 본인확인 푸시의 읽음 처리 ID

> #884의 서버 계약과 센터 저장 변경을 검증하는 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #884 |
| 관련 ADR | ADR-0037 결정 2·3 |
| 작성자 | Codex / feature/884 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

시니어 긴급 본인확인 푸시 `SAFETY_SELF_CHECK`는 센터 행을 만들지 않아 FCM `data.notificationId`가 없다. 앱이 푸시를 탭해도 기존 읽음 처리 API에 보낼 ID가 없다. 수신자×사건의 센터 행을 만들고 같은 ID를 푸시에 넣는다.

## 2. 범위

### In scope

- `widyu-domain`: `SAFETY_SELF_CHECK` 전달 방식을 `PUSH_AND_CENTER`로 바꾼다. 긴급 우선순위·설정 예외·문구·`data.type`·푸시 TTL은 유지한다.
- `widyu-api`: 기존 outbox의 센터 저장·FCM payload·읽음 처리를 연결하고 테스트한다. 공통 발송 코드가 이미 지원하면 별도 분기를 추가하지 않는다.
- 본인확인 알림은 목록에도 나타나며 `FcmCategory.INCIDENT_SELF_CHECK`, `ROUTINE_90D` 보존을 사용한다.

### Out of scope

- 다른 `PUSH_ONLY` 타입, 사건 확인·취소 응답 API, 보호자 알림 조건, 시니어 설정 #881.

## 3. 인터페이스 / API

FCM `message.data.notificationId`는 십진 문자열 센터 행 ID다. `type=EMERGENCY_CONFIRM_REQUEST`, `notificationType=SAFETY_SELF_CHECK`, `entityId=incident_ref`는 유지한다. 앱은 탭 시 `PATCH /api/v1/fcm/{notificationId}` 또는 현 알림센터 읽음 API에 ID를 전달한다. 같은 수신자·사건의 모든 토큰과 재시도에 동일한 ID를 쓴다.

## 4. 데이터 모델

기존 `fcm_notification`의 수신자·eventId 유일 인덱스와 `fcm_outbox.notification_id`를 사용한다. 새 컬럼·DDL은 없다.

## 5. 처리 흐름

1. `FcmOutboxService.enqueue`가 센터 행을 토큰 반복 전에 한 번 저장한다. 토큰이 0개여도 행을 남긴다.
2. outbox가 센터 행 ID를 저장하고 `FcmHttpTransport`가 문자열 `data.notificationId`로 넣는다.
3. 기존 읽음 API는 로그인 수신자의 센터 행만 읽음 처리한다. 읽음은 사건 확인 응답을 대신하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | 결과 |
| --- | --- |
| 다른 회원의 ID로 읽음 요청 | 기존 not found 응답, 상태 불변 |
| 동일 사건 재전송 | 기존 센터 행 재사용 |
| 토큰 없음·FCM 실패 | 센터 행 유지, 전송 결과는 별도 |

## 7. 인수조건

- [x] AC1: 수신자×사건당 센터 행 1건과 outbox가 생기고 outbox `notification_id`가 센터 ID다.
- [x] AC2: FCM HTTP 요청의 `data.notificationId`가 문자열 센터 ID이며 기존 type, Android high·안전 채널, APNs 표시가 유지된다.
- [x] AC3: 받은 ID로 본인 읽음 처리 가능하고 다른 회원은 읽을 수 없다. 사건 확인 응답은 별도다.
- [x] AC4: 0개·여러 토큰, 중복 enqueue, 재시도에서 ID가 일관되고 다른 PUSH_ONLY 타입은 유지된다. 배포 전 outbox의 NULL ID도 유지한다.
- [ ] AC5: 목록·미읽음 수의 지정 테스트와 문서는 확인했다. 전체 하네스는 로컬 Redis 6379 연결 대기에서 중단되어 CI 또는 Redis 가능한 환경에서 재실행해야 한다.

## 8. 영향 범위 / 마이그레이션

과거 푸시에는 센터 행을 소급 생성하지 않는다. 배포 전 `PUSH_ONLY`로 큐에 남은 `SAFETY_SELF_CHECK` outbox는 `notification_id=NULL`이다. 전달 방식 enum을 새 값으로 읽어도 이 행에는 outbox ID를 가짜 센터 ID로 넣지 않는다. 새 발송부터 90일 센터 보존과 목록 노출이 적용된다. DB 변경은 없으며 앱은 본인확인 알림의 목록 표시를 처리해야 한다.

## 9. 미결정 사항 (Open Questions)

제품 확인 대기: 본인확인 알림의 센터 목록·미읽음 수 노출을 허용할지 확인한다. 사용자는 푸시 탭 후 기존 읽음 처리용 ID를 요청했고, 현 읽음 API는 센터 행을 대상으로 하므로 구현은 센터 저장을 적용했다. 목록 노출이 허용되지 않으면 숨김 정책과 읽음 API 계약을 함께 설계해야 한다.

## 10. 참고

- [타입 레지스트리](LLD-0060-notification-type-registry.md)
- [본인확인 흐름](LLD-0070-self-check-first-heart.md)
- [이슈 #884](https://github.com/GB-able/WIDYU-server/issues/884)
