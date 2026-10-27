# LLD-0104: 앨범 미업로드 알림을 3·5·7일째에 한 번씩만 보낸다

| 항목 | 값 |
| --- | --- |
| 상태 | Review (코드 리뷰 지적 기반 구현, 2026-10-22) |
| Issue | #816 |
| 관련 ADR | ADR-0028 |
| 작성자 | Claude / feature-816 |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

매일 10시 스케줄러 `AlbumNotificationListener.checkInactiveUsersAndSendNotification`은 보호자가 앨범을 올리지 않은 지 3·5·7일이 되면 "N일 간 소식이 뜸했어요" 푸시를 보낸다([LLD-0002](LLD-0002-fcm-notification.md) §5-4). #883(PR #885)이 수신자를 가족 시니어에서 보호자 본인으로, 조건을 날짜 기준 정확히 3·5·7일째로 바꿨다. 이 문서는 그 위에서 대상 조회·트랜잭션·재실행 중복을 다룬다.

같은 메서드는 `memberRepository.findAll()` 뒤 보호자마다 마지막 업로드·가족 소속 조회를 반복하고, 알림에 `eventId`가 없어 재실행 시 알림센터 행이 중복되며, 전체가 한 트랜잭션이라 한 건의 저장 실패가 그날 알림 전체를 롤백한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 판정은 날짜 단위 "정확히 N일 전"(#885와 같음). 보호자 한 명은 하루에 최대 1종(3·5·7 중 하나)만 해당한다.
- 보호자별 마지막 업로드 시각을 집계 쿼리 1개로 읽는다.
- 수신자×일수×날짜로 결정되는 `eventId`를 붙인다.
- 스케줄러의 바깥 트랜잭션을 없애 수신자마다 따로 커밋한다.

### Out of scope
- 새 `NotificationType`·문구 코드·보관 기간. 승인된 알림 정책 v0.6이 장기 미업로드 권유(`ALBUM_INACTIVITY_NUDGE`)를 이번 개발에서 보내지 않기로 했고, 기존 알림의 지속 여부는 회신 대기다([LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md) §2·§9). 이 알림은 type 없는 레거시 경로를 유지한다.
- 문구 변경. 기존 제목·본문을 그대로 쓴다.

## 3. 인터페이스 / API

HTTP 변경 없음. 스케줄 `0 0 10 * * *`와 FCM 수신자(보호자 본인)·제목·본문·카테고리(`ALBUM`)·이미지(보호자 프로필)·data `deepLink=/album`은 #885와 같다.

## 4. 데이터 모델

테이블 변경 없음.

- `AlbumRepository.findGuardianLastActivityBetween(from, to)`: `FamilyMembership`에서 ACTIVE 보호자를 읽어 ACTIVE 앨범과 LEFT JOIN하고, 보호자마다 `COALESCE(MAX(album.createdAt), guardian.createdAt)`을 집계해 그 값이 `[from, to)`인 행만 돌려준다. 가족에 속하지 않은 보호자는 나오지 않는다. 결과는 `GuardianLastActivityRow(guardianId, guardianName, profileImage, lastActivityAt)`(widyu-api `album/repository`).
- 기존 `findLastUploadDateByMember`는 이 스케줄러만 쓰던 쿼리라 삭제한다.

## 5. 처리 흐름

1. 10시에 `today = LocalDate.now()`(서버 시간대 Asia/Seoul, `-Duser.timezone`)로 실행한다. 바깥 트랜잭션은 없다.
2. `from = today-7일 00:00`, `to = today-2일 00:00`으로 후보를 조회한다(마지막 활동 날짜가 today-7 ~ today-3).
3. 후보마다 `days = 마지막 활동 날짜와 today 사이의 일수`를 구해 3·5·7일 때만 보낸다.
4. 보호자 본인에게 `FcmService.sendMessageToUser`를 호출한다. 이 호출이 자기 쓰기 트랜잭션으로 센터 행·outbox를 커밋한다([ADR-0028](../adr/ADR-0028-fcm-durable-delivery.md)).
5. `eventId = inactive:{guardianId}:{days}:{today}`. enqueue 전에 (보호자, eventId) 센터 행이 있으면 건너뛴다. `FcmOutboxService.enqueue`는 같은 eventId 재enqueue에도 기기별 outbox를 다시 만들기 때문이다([LLD-0062](LLD-0062-notification-center-row-per-event.md) §6, at-least-once 유지). 두 인스턴스가 동시에 실행되면 센터 행 UK(recipient_member_id, event_id) 충돌로 늦은 쪽 트랜잭션이 outbox째 롤백된다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
| --- | --- |
| 보호자 한 명의 enqueue 실패(DB 오류, 길이 초과 등) | 그 호출의 트랜잭션만 롤백, ERROR 로그(guardianId) 후 다음 보호자로 진행 |
| 가족에 속하지 않은 보호자 | 조회 대상이 아니라 보내지 않음 |
| 같은 날 재실행 | 센터 행이 있는 보호자는 건너뜀 |
| 수신자·보호자 비활성, 가족 불일치 | 기존 `FcmOutboxService.enqueue` 규칙대로 저장하지 않음 |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 마지막 업로드가 정확히 3·5·7일 전이면 보호자 본인에게 알림 1건, 4·8일 전이면 0건이다. 날짜 경계(전날 23:50, 7일 전 00:00, 8일 전 23:59)도 날짜로 판정한다.
- [x] AC2: 집계 쿼리는 보호자별 마지막 ACTIVE 업로드 시각을 쓰고 DELETED 앨범은 무시하며, 업로드가 없으면 가입 시각으로 범위를 판정한다.
- [x] AC3: 같은 날 같은 보호자·일수의 알림은 `inactive:{guardianId}:{days}:{date}` eventId를 가지며, 그 센터 행이 이미 있는 보호자에게는 다시 enqueue하지 않는다.
- [x] AC4: 한 보호자의 enqueue가 실패해도 같은 실행의 다음 보호자에게 보낸다.
- [x] AC5: `AlbumNotificationListenerTest`, `AlbumGuardianLastActivityQueryTest`가 통과한다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 발송 대상·시점은 #885와 같고, 회원 전체 조회가 집계 쿼리 1개로 바뀌며 같은 날 재실행해도 푸시가 중복되지 않는다. 신규 ADR N/A: 기존 알림의 의도(3·5·7일째)를 코드로 맞추는 수정이다.

## 9. 미결정 사항 (Open Questions)

- 기존 3·5·7일 알림을 계속 보낼지(정책 v0.6은 `ALBUM_INACTIVITY_NUDGE` 미발송, 회신 대기 — LLD-0066 §9). 유지하면 type·문구 코드·보관 기간을, 중단하면 스케줄러 제거를 별도 PR에서 정한다.
- type이 없어 이 알림의 센터 행은 만료 시각(`expiresAt`)이 없다. 위 결정에 따른다.

## 10. 참고

- [LLD-0002](LLD-0002-fcm-notification.md) §5-4, [LLD-0062](LLD-0062-notification-center-row-per-event.md)(수신자×이벤트 센터 행), [LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md)
