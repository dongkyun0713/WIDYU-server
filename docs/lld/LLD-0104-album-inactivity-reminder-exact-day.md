# LLD-0104: 앨범 미업로드 알림을 3·5·7일째에 한 번씩만 보낸다

| 항목 | 값 |
| --- | --- |
| 상태 | Review (코드 리뷰 지적 기반 구현, 2026-10-22) |
| Issue | #816 |
| 관련 ADR | ADR-0028 |
| 작성자 | Claude / feature-816 |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

매일 10시 스케줄러 `AlbumNotificationListener.checkInactiveUsersAndSendNotification`은 보호자가 앨범을 올리지 않은 지 3·5·7일이 되면 가족 시니어에게 "N일 간 소식이 뜸했어요" 푸시를 보낸다([LLD-0002](LLD-0002-fcm-notification.md) §5-4). 조건이 "마지막 업로드(없으면 가입) 시각이 지금보다 N일 이전"이라 누적으로 참이 된다. 8일 이상 올리지 않은 보호자의 시니어는 3·5·7일 푸시 3건을 한꺼번에, 그리고 보호자가 다시 올릴 때까지 매일 받는다.

같은 메서드는 `memberRepository.findAll()` 뒤 회원마다 조회를 3번(임계값마다) 반복하고, 알림에 `eventId`가 없어 재실행 시 알림센터 행이 중복되며, 전체가 한 트랜잭션이라 한 건의 저장 실패가 그날 알림 전체를 롤백한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 판정을 날짜 단위 "정확히 N일 전"으로 바꾼다. 보호자 한 명은 하루에 최대 1종(3·5·7 중 하나)만 해당한다.
- 보호자별 마지막 업로드 시각을 집계 쿼리 1개로 읽는다.
- 수신자×일수×날짜로 결정되는 `eventId`를 붙인다.
- 스케줄러의 바깥 트랜잭션을 없애 수신자마다 따로 커밋한다.

### Out of scope
- 새 `NotificationType`·문구 코드·보관 기간. 승인된 알림 정책 v0.6이 장기 미업로드 권유(`ALBUM_INACTIVITY_NUDGE`)를 이번 개발에서 보내지 않기로 했고, 기존 알림의 지속 여부는 회신 대기다([LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md) §2·§9). 이 알림은 type 없는 레거시 경로를 유지한다.
- 문구 변경. 기존 제목·본문을 그대로 쓴다.

## 3. 인터페이스 / API

HTTP 변경 없음. 스케줄 `0 0 10 * * *`와 FCM 제목·본문·카테고리(`ALBUM`)·이미지·`relatedMemberId`(보호자)는 그대로다.

## 4. 데이터 모델

테이블 변경 없음.

- `AlbumRepository.findGuardianLastActivityBetween(from, to)`: `FamilyMembership`에서 ACTIVE 보호자를 읽어 ACTIVE 앨범과 LEFT JOIN하고, 보호자마다 `COALESCE(MAX(album.createdAt), guardian.createdAt)`을 집계해 그 값이 `[from, to)`인 행만 돌려준다. 결과는 `GuardianLastActivityRow(guardianId, guardianName, familyId, lastActivityAt)`(widyu-api `album/repository`).
- 기존 `findLastUploadDateByMember`는 이 스케줄러만 쓰던 쿼리라 삭제한다.

## 5. 처리 흐름

1. 10시에 `today = LocalDate.now()`(서버 시간대 Asia/Seoul, `-Duser.timezone`)로 실행한다. 바깥 트랜잭션은 없다.
2. `from = today-7일 00:00`, `to = today-2일 00:00`으로 후보를 조회한다(마지막 활동 날짜가 today-7 ~ today-3).
3. 후보마다 `days = 마지막 활동 날짜와 today 사이의 일수`를 구해 3·5·7일 때만 보낸다.
4. 보호자 가족의 시니어 목록을 읽고 시니어마다 `FcmService.sendMessageToUser`를 호출한다. 이 호출이 자기 쓰기 트랜잭션으로 센터 행·outbox를 커밋한다([ADR-0028](../adr/ADR-0028-fcm-durable-delivery.md)).
5. `eventId = inactive:{guardianId}:{days}:{today}`. 알림센터 행은 (수신자, eventId)가 유일하므로 같은 날 재실행돼도 센터 행이 늘지 않는다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
| --- | --- |
| 시니어 한 명의 enqueue 실패(DB 오류, 길이 초과 등) | 그 호출의 트랜잭션만 롤백, ERROR 로그(guardianId·seniorId) 후 다음 시니어로 진행 |
| 보호자 가족에 시니어가 없음 | 보내지 않음 |
| 수신자·보호자 비활성, 가족 불일치 | 기존 `FcmOutboxService.enqueue` 규칙대로 저장하지 않음 |

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 마지막 업로드가 정확히 3·5·7일 전이면 가족 시니어마다 알림 1건, 4·8일 전이면 0건이다. 날짜 경계(전날 23:50, 7일 전 00:00, 8일 전 23:59)도 날짜로 판정한다.
- [x] AC2: 집계 쿼리는 보호자별 마지막 ACTIVE 업로드 시각을 쓰고 DELETED 앨범은 무시하며, 업로드가 없으면 가입 시각으로 범위를 판정한다.
- [x] AC3: 같은 날 같은 보호자·일수의 알림은 `inactive:{guardianId}:{days}:{date}` eventId를 가진다.
- [x] AC4: 한 시니어의 enqueue가 실패해도 같은 실행의 다음 시니어에게 보낸다.
- [x] AC5: `AlbumNotificationListenerTest`, `AlbumGuardianLastActivityQueryTest`가 통과한다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 배포 직후부터 8일 이상 미업로드 보호자의 시니어는 매일 받던 3건을 더 받지 않는다. 같은 보호자가 다시 올리지 않으면 이후로는 알림이 없다(7일째가 마지막). 신규 ADR N/A: 기존 알림의 의도(3·5·7일째)를 코드로 맞추는 수정이다.

## 9. 미결정 사항 (Open Questions)

- 기존 3·5·7일 알림을 계속 보낼지(정책 v0.6은 `ALBUM_INACTIVITY_NUDGE` 미발송, 회신 대기 — LLD-0066 §9). 유지하면 type·문구 코드·보관 기간을, 중단하면 스케줄러 제거를 별도 PR에서 정한다.
- type이 없어 이 알림의 센터 행은 만료 시각(`expiresAt`)이 없다. 위 결정에 따른다.

## 10. 참고

- [LLD-0002](LLD-0002-fcm-notification.md) §5-4, [LLD-0062](LLD-0062-notification-center-row-per-event.md)(수신자×이벤트 센터 행), [LLD-0066](LLD-0066-delivery-mode-alignment-album-health-walk.md)
