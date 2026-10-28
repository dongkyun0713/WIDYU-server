# LLD-0099: 앨범 조회수를 원자 UPDATE로 저장한다

| 항목 | 값 |
| --- | --- |
| 상태 | Review (구현 완료, 기존 데이터 보정 여부는 Open Question) |
| Issue | #809 |
| 관련 ADR | - |
| 작성자 | Claude |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

앨범 조회수(`album.view_count`)가 DB에 한 번도 저장되지 않는다. 첫 조회 응답에만 1이 보이고 피드·관리자 화면·다음 상세 조회는 0이다.

상세 조회 `AlbumService.getAlbumDetail`은 `@Transactional(readOnly = true)`에서 앨범을 읽고, 다른 회원의 첫 조회면 `AlbumViewService.recordView`(`REQUIRES_NEW`)를 부른다. `recordView`는 조회 기록(`AlbumView`)을 저장하고 넘겨받은 `album`에 `incrementViewCount()`를 호출했다. 이 `album`은 바깥 영속성 컨텍스트 소속이라 새 트랜잭션의 변경 감지 대상이 아니고, 바깥 트랜잭션은 읽기 전용이라 flush하지 않는다. 그래서 어느 쪽도 `view_count` UPDATE를 실행하지 않았다. 기존 단위 테스트는 mock `Album`의 메서드 호출만 확인해 이 결함을 잡지 못했다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `AlbumRepository.incrementViewCount(albumId)`: `UPDATE Album a SET a.viewCount = a.viewCount + 1 WHERE a.id = :albumId`
- `AlbumViewService.recordView`가 조회 기록을 저장한 같은 `REQUIRES_NEW` 트랜잭션에서 위 UPDATE를 실행한다
- 응답 `viewCount`는 넘겨받은 엔티티의 메모리 값을 1 올려 맞춘다(바깥 트랜잭션은 읽기 전용이라 이 값은 flush되지 않는다)
- H2 통합 테스트로 상세 조회 뒤 DB 값을 확인한다

### Out of scope
- 좋아요·댓글 카운터의 같은 read-modify-write 문제(동시 삭제 앨범 부활, 증가 유실) → #811. 같은 원자 UPDATE 방식이 후보이며 이 LLD의 후속으로 다룬다
- 같은 회원의 동시 첫 조회(연속 탭)가 `album_view` UK에 걸려 상세 조회가 실패하는 기존 동작. 이 변경에서는 UK 위반 시 UPDATE 전에 트랜잭션이 롤백되어 조회수가 두 번 오르지 않는다는 점만 보장한다
- 이미 쌓인 `album_view` 행으로 기존 `view_count`를 보정하는 운영 데이터 변경(9절 Open Question)

## 3. 인터페이스 / API

HTTP 계약 변경 없음. `GET /api/v1/albums/{albumId}` 응답의 `viewCount` 의미는 그대로다(가족 회원별 첫 조회 수, 작성자 본인 제외).

## 4. 데이터 모델

스키마 변경 없음. `AlbumRepository`에 `@Modifying` JPQL 메서드 1개를 추가한다.

## 5. 처리 흐름

1. `getAlbumDetail`(readOnly): 앨범 조회 → 권한 검사 → 작성자가 아니면 `recordView` 호출
2. `recordView`(`REQUIRES_NEW`): `album_view` 존재 확인 → 없으면
   1. `album_view` INSERT
   2. `UPDATE album SET view_count = view_count + 1 WHERE album_id = ?`
   3. 메모리 `album.viewCount` + 1 (응답용)
   4. `AlbumViewedEvent` 발행 → `AlbumNotificationListener.handleAlbumViewed`(`@EventListener @Transactional`)가 같은 트랜잭션에서 알림 outbox를 저장한다(LLD-0036)
3. `recordView` 커밋 → 바깥으로 돌아와 댓글·조회자 조회 후 응답

선택 이유:
- **원자 UPDATE(채택)**: DB가 `view_count + 1`을 계산하므로 동시 조회에서도 증가가 유실되지 않고, `status`·`content` 같은 다른 컬럼을 읽은 값으로 덮어쓰지 않는다. 조회 기록과 같은 트랜잭션이라 둘 중 하나만 남지 않는다.
- **새 트랜잭션에서 `findById`로 다시 읽어 증가(기각)**: 변경 감지가 행 전체를 UPDATE해(`@DynamicUpdate` 없음) 동시 조회끼리 증가가 유실되고, 그 사이 커밋된 삭제(`DELETED`)를 `ACTIVE`로 되돌릴 수 있다(#811과 같은 문제).
- **상세 조회를 쓰기 트랜잭션으로 바꿈(기각)**: 읽기 경로 전체가 쓰기 트랜잭션이 되고 위와 같은 read-modify-write가 남는다. 조회 기록을 독립 업무 경계로 둔 LLD-0036의 구성도 바뀐다.

JPQL 벌크 UPDATE는 영속성 컨텍스트와 감사 필드를 거치지 않으므로 `album.updated_at`은 바뀌지 않는다. 조회는 앨범 수정이 아니고 `Album.updatedAt`을 읽는 코드도 없다.

## 6. 예외 / 에러 처리

- 새 에러 코드 없음
- `album_view` INSERT가 UK 위반으로 실패하면 기존처럼 예외가 전파되고 `REQUIRES_NEW` 트랜잭션이 롤백된다. UPDATE는 INSERT 뒤에 실행되므로 조회수만 오르는 경우는 없다
- 리스너 예외로 `recordView`가 롤백되면 조회 기록·조회수·outbox가 함께 롤백된다(기존 원자성 유지)

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 다른 가족 회원이 앨범을 처음 상세 조회하면 DB의 `view_count`가 1 늘고 응답 `viewCount`도 1이다 (`AlbumViewCountPersistenceTest`)
- [x] AC2: 같은 회원이 다시 상세 조회하면 DB의 `view_count`가 늘지 않는다 (`AlbumViewCountPersistenceTest`)
- [x] AC3: 작성자 본인이 상세 조회하면 DB의 `view_count`가 늘지 않는다 (`AlbumViewCountPersistenceTest`)
- [x] AC4: 조회 기록 저장과 `AlbumViewedEvent` 발행 흐름이 유지된다 (`AlbumViewServiceTest`)
- [x] AC5: 수정 전 코드에서 AC1·AC2 테스트가 실패한다(DB 값 0)
- [ ] AC6: `./gradlew :backend:widyu-api:test`가 통과한다 (로컬 미실행, CI 확인)

2026-10-22 로컬 검증: `./gradlew :backend:widyu-api:test --tests "com.widyu.album.*" --tests "com.widyu.fcm.event.album.listener.AlbumNotificationListenerTest"` 108건 통과. 수정 전 `AlbumViewService`로 되돌리면 `AlbumViewCountPersistenceTest` 2건이 `expected: 1 but was: 0`으로 실패했다. H2 기준이며 운영 MySQL에서의 확인은 하지 않았다.

## 8. 영향 범위 / 마이그레이션

- DB 마이그레이션 없음
- 배포 뒤 새 조회부터 `view_count`가 오른다. 기존 앨범의 값은 그동안 쌓인 조회 기록과 맞지 않은 채(대부분 0) 남는다
- 피드·캘린더·관리자 목록의 `viewCount`가 실제 값을 보이기 시작한다

## 9. 미결정 사항 (Open Questions)

- [ ] 기존 앨범의 `view_count`를 `album_view` 행 수로 보정할지. 보정한다면 운영에서 1회 실행할 SQL 후보는 `UPDATE album a SET a.view_count = (SELECT COUNT(*) FROM album_view av WHERE av.album_id = a.album_id);`이며 실행 시점·백업 여부를 정해야 한다. 이 PR은 코드만 바꾸고 데이터는 건드리지 않는다

## 10. 참고

- [LLD-0036](LLD-0036-fcm-durable-delivery.md) 앨범 조회 행: 외부 `getAlbumDetail` readOnly → `recordView` REQUIRES_NEW에서 조회 기록·outbox 함께 저장
- [LLD-0013](LLD-0013-album-notification-side-effect-isolation.md)
- #811 좋아요·댓글 카운터 read-modify-write (후속)
