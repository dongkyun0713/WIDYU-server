# LLD-0002: FCM 푸시 알림 발송 구조

> #608 이후 발송 트랜잭션·재시도·고정 수신자·응원 접수 계약은 [LLD-0036](LLD-0036-fcm-durable-delivery.md)을 따른다. 아래 직접 HTTP/재시도 없음 설명은 변경 전 구조를 기록한 것이다.

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | - |
| 관련 ADR | ADR-0003 (DB/엔티티 설계), ADR-0005 (알림 목록 커서 페이징) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-05 |

## 1. 목적 / 배경

앨범 생성·댓글·좋아요·잠금해제, 안전구역 이탈, 건강 스케줄, 복약 등 다양한 도메인 이벤트가 발생할 때
대상 회원의 기기에 FCM 푸시 알림을 전달해야 한다.
서비스 간 직접 의존 대신 `@EventListener` 패턴으로 도메인 결합을 제거한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (fcm 패키지 전체)
- FCM v1 API 호출 (GoogleCredentials 서비스 계정 인증)
- 카테고리별 알림 ON/OFF 설정 (`MemberNotificationSetting`)
- 다중 기기 지원 (`MemberFcmToken`, active 상태만 발송)
- 로그인/로그아웃 시 FCM 토큰 활성화·비활성화
- 알림 DB 저장 (`FcmNotification`) 및 읽음 처리
- 커서 기반 알림 목록 조회 (id 단일 커서, pageSize=10)
- 이벤트 리스너: 앨범(5종), 안전구역 이탈, 건강 스케줄, 걷기, 복약, 심박수
- 앨범 생성 완료 시 업로더에게 ALBUM 알림 발송
- 비활성 유저 스케줄 알림 (매일 오전 10시, 3/5/7일)
- 응원 알림 직접 발송 API

### Out of scope
- 트랜잭셔널 아웃박스/재시도 큐 (현재 미구현)
- FCM 토큰 물리 삭제 (현재는 active=false 비활성화)
- WebSocket 실시간 push (LLD-0001 참고)

## 3. 인터페이스 / API

```http
GET /api/v1/fcm?category=ALL&cursor=42
```
카테고리별 알림 목록 (cursor 미전달 시 최신부터).

```json
{
  "isSuccess": true,
  "result": {
    "notifications": [
      {
        "id": 42,
        "title": "홍길동님이 새로운 소식을 전했어요!",
        "body": "새로운 앨범을 확인해보세요.",
        "fcmCategory": "ALBUM",
        "isRead": false,
        "image": "https://...",
        "createdAt": "2026-07-05T14:30:00"
      }
    ],
    "hasNext": true,
    "nextCursor": 32
  }
}
```

```http
PATCH /api/v1/fcm/{notificationId}
```
단건 읽음 처리. 이미 읽은 경우 "이미 읽은 알림입니다" 반환.

```http
GET /api/v1/fcm/categories
```
카테고리별 읽지 않은 알림 수 반환.

```json
{
  "isSuccess": true,
  "result": [
    { "category": "ALL", "unreadCount": 5 },
    { "category": "ALBUM", "unreadCount": 3 }
  ]
}
```

```http
GET /api/v1/fcm/toast
```
보호자용 토스트 모달 메시지 (시니어 미확인 앨범 수 기반). 없으면 null.

```http
POST /api/v1/fcm/send
```
응원 알림 직접 발송.

```json
{
  "receiverId": 2,
  "content": "오늘도 화이팅!"
}
```

```http
POST /api/v1/fcm/token/login
```
로그인 시 기기 FCM 토큰 저장 또는 재활성화.

```json
{
  "token": "fcm-token",
  "deviceInfo": "iOS 17"
}
```

```http
POST /api/v1/fcm/token/logout
```
로그아웃 시 FCM 토큰 비활성화.

```json
{
  "token": "fcm-token"
}
```

```http
GET /api/v1/fcm/settings
PATCH /api/v1/fcm/settings
```
알림 설정 그룹 조회·변경. 그룹 단위(`GOAL`, `ALBUM`, `HOME`, `ETC`)로 요청하고, 내부적으로 여러 `FcmCategory`에 반영한다.

## 4. 데이터 모델

### 엔티티 (widyu-domain)

**MemberFcmToken**: 회원당 다수 기기 토큰 관리
```
member_fcm_token
├── id (PK)
├── member_id (FK, ManyToOne)
├── token (String, 기기 FCM 토큰)
├── active (Boolean) ← false면 발송 제외
├── device_info (String)
├── registered_at
├── expired_at
└── last_used_at
```

**FcmNotification**: 발송된 알림 기록
```
fcm_notification
├── id (PK)
├── member_fcm_token_id (FK)  ← 특정 기기 토큰에 귀속
├── title, body, image (String)
├── fcm_category (ENUM STRING)
├── is_read (Boolean, default false)
└── createdAt, updatedAt
```

**MemberNotificationSetting**: 카테고리별 알림 ON/OFF (회원당 카테고리 unique)
```
member_notification_setting
├── id (PK)
├── member_id (FK)
├── category (ENUM STRING)
├── enabled (Boolean)
└── (member_id, category) UNIQUE
```

**FcmCategory** enum:
`ALL, ALBUM, TARGET, HEALTH_SCHEDULE, WALK, MEDICINE_SCHEDULE, HEART_MESSAGE, SAFE_ZONE, ETC`

### DTO (widyu-api)

**FcmSendDto**: 내부 발송 커맨드 (record)
```java
record FcmSendDto(String title, String content, FcmCategory fcmCategory, String scheme, String image)
```

**FcmMessageDto**: FCM API 전송 형식 (Builder, 외부 직렬화용)

## 5. 처리 흐름

### 5-1. FCM 발송 (`sendMessageToUser`)

```
1. NotificationSettingService.isNotificationEnabled(memberId, fcmCategory)
   └─ OFF이면 바로 return (알림 미발송)
2. MemberFcmTokenRepository.findAllByMemberIdAndActiveTrue(memberId)
3. 토큰별 반복:
   a. makeMessage(token, fcmSendDto) → JSON 직렬화
   b. GoogleCredentials.fromStream(firebase.config-path) → refreshIfExpired()
   c. RestTemplate POST https://fcm.googleapis.com/v1/projects/widyu-d384f/messages:send
   d. HTTP 200 → FcmNotification 저장 (fcmNotificationRepository.save)
   e. IOException 발생 시 → log.error 후 종료 (재시도 없음)
   f. RestTemplate 런타임 예외 발생 시 → 현재 코드에서는 catch하지 않고 전파 가능
```

트랜잭션: `@Transactional` (sendMessageToUser 전체)

### 5-2. 이벤트 기반 알림 발송

```
도메인 서비스 (AlbumService 등)
  └─ applicationEventPublisher.publishEvent(AlbumCreatedEvent(authorId, albumId))

AlbumNotificationListener (@EventListener, 동기)
  ├─ handleAlbumCreated → 업로더에게 완료 알림 + 가족 구성원에게 새 앨범 ALBUM 알림
  ├─ handleAlbumViewed  → 보호자가 시니어의 모든 앨범을 다 봤을 때 시니어에게 알림
  ├─ handleAlbumCommented → 앨범 작성자에게 ALBUM 알림 (자기 댓글 제외)
  ├─ handleAlbumLiked    → 앨범 작성자에게 ALBUM 알림 (자기 좋아요 제외)
  └─ handleAlbumUnlocked → 잠금해제된 앨범 작성자에게 ALBUM 알림
```

`sendNotificationToFamilyMembers()` 내부 분기:
- 시니어가 발신 → 가족 내 보호자 전원
- 보호자가 발신 → 가족 내 시니어 전원

### 5-3. 이벤트 리스너 전체 목록

| 리스너 클래스 | 처리 이벤트 | 카테고리 |
|--------------|------------|----------|
| AlbumNotificationListener | AlbumCreated/Viewed/Commented/Liked/Unlocked | ALBUM |
| SafeZoneNotificationListener | SafeZoneExitEvent | SAFE_ZONE |
| HealthScheduleNotificationListener | HealthSchedule 이벤트 | HEALTH_SCHEDULE |
| WalkNotificationListener | Walk 이벤트 | WALK |
| MedicineScheduleNotificationListener | MedicineSchedule 이벤트 | MEDICINE_SCHEDULE |
| HeartRateEmergencyNotificationService | HeartRateEmergencyEvent | HEART_MESSAGE |

### 5-4. 비활성 유저 스케줄 알림

```
@Scheduled(cron = "0 0 10 * * *")  ← 매일 오전 10시
  └─ [3일, 5일, 7일] 각 임계값에 대해:
       └─ 전체 회원 조회
       └─ 마지막 앨범 업로드일 계산
       └─ 임계값 이전이면 보호자의 가족 내 시니어들에게 ALBUM 알림
```

### 5-5. 알림 목록 조회

```
GET /api/v1/fcm?category=ALL&cursor=42

1. getCurrentMember() (SecurityContext)
2. category == "ALL": findNotificationsWithCursor(memberId, cursor, pageable)
   category 기타: FcmCategory.valueOf(category) → findNotificationsByCategoryWithCursor
3. fetchSize = pageSize + 1 = 11개 조회
4. hasNext = size > 10
5. nextCursor = 10번째 항목의 id (hasNext=true일 때만)
6. FcmNotificationResponses.of(notifications, hasNext, nextCursor)
```

## 6. 예외 / 에러 처리

| 상황 | 처리 |
|------|------|
| 알림 설정 OFF | return (예외 없이 silently 무시) |
| FCM credential/message 생성 IOException | log.error, 예외 전파 안 함 |
| FCM HTTP 호출 런타임 예외 | 현재 catch하지 않음. 동기 `@EventListener` 흐름에서는 호출자 트랜잭션에 영향 가능 |
| notificationId 없음 | FCM_NOTIFICATION_NOT_FOUND BusinessException |
| 잘못된 category 문자열 | catch IllegalArgumentException → 전체 조회로 처리 |
| 수신자 memberId 없음 | MEMBER_NOT_FOUND BusinessException |
| 잘못된 설정 group | INVALID_FCM_CATEGORY BusinessException |

## 7. 인수조건 (Acceptance Criteria)

- [x] 앨범 생성 시 가족 보호자 전원에게 ALBUM 카테고리 알림이 발송된다
- [x] 사진 앨범 저장 및 영상 앨범 ACTIVE 전환 완료 시 업로더에게 ALBUM 카테고리 알림이 발송된다
- [x] 카테고리 알림 설정 OFF 시 해당 카테고리 알림이 발송되지 않는다
- [x] 다중 기기 보유 회원은 active 토큰 기기 전체에 알림이 발송된다
- [x] 로그인 시 기존 토큰은 재활성화하고, 신규 토큰은 저장한다
- [x] 로그아웃 시 토큰은 active=false로 비활성화된다
- [x] 알림 발송 후 FcmNotification이 DB에 저장된다
- [x] 알림 목록 API가 id 커서 기반으로 페이징된다 (pageSize=10)
- [x] 읽음 처리 후 isRead=true가 된다
- [x] 알림 설정 그룹 변경 시 그룹에 속한 모든 FcmCategory 설정이 저장 또는 갱신된다
- [x] 자기 댓글·좋아요는 알림이 발송되지 않는다
- [x] Swagger에 알림 목록 / 읽음 처리 응답이 반영된다
- [x] `./gradlew :backend:widyu-api:test`가 통과한다

## 8. 영향 범위 / 마이그레이션

- 기존 구현 완료 상태, 스키마 변경 없음
- `member_notification_setting`의 `(member_id, category)` UNIQUE 제약 주의: 중복 설정 저장 시 DB 예외
- 현재 발송 실패 재시도와 outbox가 없으므로, FCM 장애 시 알림 유실 가능성이 있다. 또한 HTTP 런타임 예외를 별도로 삼키지 않기 때문에 동기 이벤트 호출자에 영향을 줄 수 있다. 재시도·격리 보장은 별도 ADR/LLD로 분리한다.

## 9. 미결정 사항 (Open Questions)

없음. (백필 문서, 구현 완료 상태)

## 10. 참고

- `FcmService.java`: sendMessageToUser, getNotificationsForCurrentUser
- `AlbumNotificationListener.java`: @EventListener 5종, @Scheduled 비활성 알림
- `NotificationSettingService.java`: 카테고리별 ON/OFF 확인
- `MemberFcmTokenService.java`: 토큰 등록·비활성화
- `FcmCategory.java` enum, `FcmSendDto.java` record
- Firebase FCM v1 API: `https://fcm.googleapis.com/v1/projects/widyu-d384f/messages:send`
