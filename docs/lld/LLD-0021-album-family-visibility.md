# LLD-0021: 앨범 가족 전용 공개

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #456, #467, #511, #813 |
| 관련 ADR | ADR-0015 |
| 작성자 | Codex |
| 작성일 | 2026-08-03 |

## 1. 목적 / 배경

앨범 피드가 가족 외 사용자에게도 노출되고, 앨범 ID를 직접 지정하면 상세 조회와 해금으로 접근할 수 있다.
앨범의 공개 범위를 같은 가족 구성원으로 한정한다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api
- `GET /api/v1/albums/feed`와 `GET /api/v1/albums/media`를 현재 사용자와 같은 가족의 ACTIVE 앨범으로 제한한다.
- `GET /api/v1/albums/calendar`의 날짜 목록을 현재 사용자와 같은 가족의 ACTIVE 앨범 기준으로 조회한다.
- `GET /api/v1/albums/{albumId}`와 앨범 해금 요청에서 가족 외 앨범을 차단한다.
- 가족 외 앨범의 좋아요·좋아요 취소·댓글 작성 요청을 차단한다.
- `GET /api/v1/members/seniors/unlocked-albums`에 명시적으로 해금한 앨범과 같은 가족의 시니어가 작성한 활성·동영상 처리 중 앨범을 포함한다.
- 기존 커서·날짜 필터를 유지하고, 같은 가족에서는 보호자가 작성한 앨범만 시니어의 해금 대상으로 판정한다.
- 잠금 전 앨범은 피드, 미디어 피드, 캘린더 앨범 목록, 상세, 시니어 홈 카드 어디에서도 원본 미디어 URL을 내려주지 않고 썸네일만 내려준다 (#813).

### Out of scope

- 앨범·가족 DB 스키마 변경
- 사진 썸네일 생성. 사진은 업로드 때 원본 URL을 썸네일로 저장하므로 잠금 전에도 썸네일로 사진이 보인다 (#813 한계)
- S3, CDN 객체 URL의 접근 제어(서명 URL)
- 기존 해금 기록 삭제 또는 포인트 환불
- 가족 규모 증가 시 피드 쿼리 성능 최적화

## 3. 인터페이스 / API

기존 엔드포인트를 유지한다.

```http
GET  /api/v1/albums/feed
GET  /api/v1/albums/media
GET  /api/v1/albums/calendar
GET  /api/v1/albums/{albumId}
POST /api/v1/albums/{albumId}/unlock
GET  /api/v1/members/seniors/unlocked-albums
```

`GET /api/v1/albums/feed`와 `GET /api/v1/albums/{albumId}` 응답에 `boolean isUnlocked`를 추가한다.

- 보호자가 조회하면 작성자 타입과 관계없이 `true`
- 시니어가 조회하면 시니어 작성 앨범은 `true`
- 시니어가 보호자 작성 앨범을 조회하면 해금 기록이 있을 때 `true`, 없을 때 `false`

잠금 여부 `isUnlocked`는 아래 응답 모두에 같은 이름과 같은 판정으로 담는다 (#813).

| 응답 | 잠금 전 원본 URL | 잠금 전 썸네일 |
| --- | --- | --- |
| `GET /api/v1/albums/feed` (`AlbumFeedResponse`) | `mediaUrls: []` | `thumbnailUrls` 전체 |
| `GET /api/v1/albums/{albumId}` (`AlbumDetailResponse`) | `mediaUrls: []` | `thumbnailUrls` 전체 (필드 추가) |
| `GET /api/v1/albums/calendar/family` (`FamilyAlbumResponse`) | `mediaUrls: []` | `thumbnailUrls` 전체 (필드 추가) |
| `GET /api/v1/albums/media` (`AlbumMediaResponse`) | 원본 필드 없음 | 사진도 저장된 `thumbnailUrls[i]`, 없으면 `null` |
| `GET /api/v1/home/senior/cards` 앨범 카드 | 원본으로 대체하지 않음 | 첫 썸네일, 없으면 `null` |

- 잠금 전 상세 조회는 예외 없이 200으로 응답한다. 조회 기록(`AlbumView`, 조회수, 조회 알림)은 해금 뒤에만 남긴다.
- 원본을 빼도 `mediaCount`, `photoCount`, `videoCount`, `primaryMediaType`, `videoDuration`, 댓글, 좋아요, 조회자 목록은 그대로 담는다. 원본 열람이 아니고 잠금 화면 표시에 쓴다.
- 해금 뒤 응답은 #813 이전과 같다.

가족 외 앨범의 상세 또는 해금 요청은 기존 `FORBIDDEN` 오류로 거부한다.

해금 앨범 ID 목록은 명시적으로 해금한 앨범과 같은 가족의 시니어가 작성한 `ACTIVE`·`PROCESSING` 앨범을 반환한다. 동영상 앨범은 압축·썸네일이 끝날 때까지 `PROCESSING`이라, 업로드 직후 목록에서 빠지면 앱이 잠긴 것으로 표시한다(#908).

## 4. 데이터 모델

DB 스키마 변경이 없다.

가족 구성원은 기존 `SeniorProfile.family`와 `FamilyMembership.family`를 사용해 조회한다.

## 5. 처리 흐름

1. 현재 사용자의 가족 ID를 보호자 `FamilyMembership` 또는 시니어 `SeniorProfile`에서 조회한다.
2. 같은 가족의 시니어와 보호자 회원 ID, 현재 사용자 ID를 구성한다.
3. 앨범·미디어 피드와 캘린더 날짜 조회에 구성원 ID 조건을 적용한다.
4. 앨범 상세, 해금, 좋아요, 댓글 작성은 대상 작성자가 구성원 ID에 없으면 `FORBIDDEN`으로 종료한다.
5. 같은 가족이면 기존 상세 조회·해금 처리 규칙을 수행한다.
5-1. 피드, 미디어 피드, 캘린더, 상세, 시니어 홈은 `isUnlocked`가 false인 앨범의 원본 URL을 응답에서 뺀다. 피드와 미디어 피드는 해금 기록을 한 번에 조회해 판정하고, 캘린더, 상세, 시니어 홈은 `AlbumPermissionService.isUnlockedFor`로 판정한다.
6. 시니어의 해금 앨범 ID 목록은 명시적 해금 기록과 같은 가족의 시니어 작성 `ACTIVE`·`PROCESSING` 앨범 ID를 합쳐 반환한다.

## 6. 예외 / 에러 처리

- 가족 외 앨범 상세·해금 요청: `FORBIDDEN`
- 가족 연결 정보가 없는 사용자: 본인 앨범만 피드에서 조회한다.
- 존재하지 않거나 ACTIVE가 아닌 앨범: 기존 `ALBUM_NOT_FOUND`

## 7. 인수조건 (Acceptance Criteria)

- [x] 앨범 피드와 미디어 피드는 같은 가족의 ACTIVE 앨범만 반환한다.
- [x] 커서 및 날짜 기반 피드 조회에도 가족 필터가 유지된다.
- [x] 앨범 캘린더는 같은 가족의 ACTIVE 앨범이 존재하는 날짜를 반환한다.
- [x] 가족 외 앨범 상세 조회, 해금, 좋아요, 댓글 작성 요청은 `FORBIDDEN`으로 거부된다.
- [x] 같은 가족에서는 보호자가 작성한 앨범만 시니어의 해금 대상으로 판정한다.
- [x] 피드와 상세 응답의 `isUnlocked`는 보호자 조회 및 시니어 작성 앨범이면 `true`이고, 시니어가 보호자 작성 앨범을 조회하면 해금 기록 유무와 일치한다.
- [x] 시니어의 해금 앨범 ID 목록에 같은 가족의 시니어 작성 `ACTIVE`·`PROCESSING` 앨범이 포함되고 `DELETED` 앨범은 제외된다 (#908).
- [x] 잠금 전 앨범의 피드, 미디어 피드, 캘린더, 상세, 시니어 홈 응답에 원본 미디어 URL이 없고 썸네일과 `isUnlocked: false`가 담긴다 (#813).
- [x] 잠금 전 상세 조회는 예외가 아니라 200이며 조회 기록을 남기지 않는다 (#813).
- [x] 해금한 앨범과 잠금 대상이 아닌 앨범의 응답은 #813 이전과 같다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

기존 피드 응답의 앨범 수와 캘린더 날짜 목록이 현재 사용자의 가족 범위로 제한된다.
피드와 상세 응답에 `isUnlocked` 필드가 추가되며 DB 마이그레이션은 없다.

#813: 잠금 전 앨범의 `mediaUrls`가 빈 배열로 바뀐다. 앱이 잠금 화면 미리보기에 `mediaUrls`를 쓰고 있었다면 `thumbnailUrls`로 바꿔야 한다. 상세, 캘린더, 미디어 피드, 시니어 홈 카드에 `isUnlocked`가, 상세와 캘린더에 `thumbnailUrls`가 추가된다.

## 9. 미결정 사항 (Open Questions)

없음.

## 10. 참고

- ADR-0002
- ADR-0015
- #456
- #467
- #511
- #813
