# LLD-0094: 기존 FCM 알림 목록 응답 수정

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #882 |
| 관련 ADR | ADR-0037 |
| 작성일 | 2026-10-26 |

## 1. 목적 / 배경

`GET /api/v1/fcm`은 읽음 상태를 내지 않고 이동 경로를 `scheme`으로 부른다. 일부 앨범 알림은 회원 프로필 이미지 대신 `album.png`를 저장한다. 현재 앱 목록 계약에 맞춰 응답과 새 알림 생성값을 수정한다.

## 2. 범위

- 기존 FCM 목록 응답 DTO·Swagger 및 기본 PNG 파일명을 넣던 알림 발행값을 수정한다.
- `category`, 커서, `hasNext` 및 새 알림센터 API는 유지한다.

## 3. 인터페이스 / API

`GET /api/v1/fcm`의 `notifications[]`는 `notificationId`, `image`, `category`, `title`, `content`, `createdAt`, `isRead`, `path`를 반환한다. `scheme`은 제거한다. `isRead`는 저장된 읽음 상태이고 `path`는 저장된 딥링크다. 딥링크가 NULL이면 빈 문자열을 반환한다.

## 4. 데이터 모델

스키마 변경 없음. 새로 발행하는 알림의 `image`에 관련 회원의 `Member.profileImage` URL을 저장한다. 사진이 없으면 NULL이다.

## 5. 처리 흐름

1. DTO가 `FcmNotification.isRead`와 `deepLink`를 직접 투영한다.
2. 앨범 모두 읽음은 읽은 시니어, 보호자 활동 독촉은 해당 보호자, 업로드 완료는 작성자의 프로필 이미지를 사용한다. 기존에 `medicine.png`·`health_schedule.png`·`walk.png`를 넣던 본인 알림도 대상 시니어의 프로필 이미지를 사용한다.
3. 보호자 활동 독촉 알림의 목록 경로는 `/album`으로 저장한다. 다른 이벤트의 기존 경로는 유지한다.

## 6. 예외 / 에러 처리

기존 센터 행의 `album.png`와 빈 딥링크는 소급 수정하지 않는다. 저장된 값이 없으면 빈 경로 또는 NULL 이미지를 응답한다.

## 7. 인수조건

- 읽기 전후 목록 응답 `isRead`가 각각 false/true다. JSON에 `path`가 있고 `scheme`은 없다.
- 새 보호자 활동 독촉의 `image`는 해당 보호자 프로필 URL이고 `path`는 `/album`이다. 새 앨범 모두 읽음의 `image`는 시니어 프로필 URL이다.
- 복약·건강일정·걷기 본인 알림의 새 `image`는 해당 시니어 프로필 URL이다.
- `category`, `hasNext`, `nextCursor`, 별도 카테고리 API는 기존 동작을 유지한다.
- Swagger 예시는 새 필드를 보인다.

## 8. 영향 범위 / 마이그레이션

DB 변경 없음. 이전 앱이 `scheme`을 읽고 있으면 앱 계약을 동시에 전환해야 한다. 기존 `album.png` 행은 과거 인물 ID가 센터 행에 없어 소급 변경하지 않는다.

## 미결정 사항(Open Questions)

없음.
