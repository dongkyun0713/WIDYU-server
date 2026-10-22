# LLD-0001: WebSocket 실시간 위치 추적

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | - |
| 관련 ADR | ADR-0002 (JWT 인증), ADR-0003 (Redis TTL 저장) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-05 |

## 1. 목적 / 배경

보호자(guardian)가 시니어의 실시간 GPS 좌표를 지도에서 확인할 수 있어야 한다.
폴링 방식은 서버 부하와 지연이 크므로 STOMP WebSocket으로 위치를 push한다.
안전구역 이탈 감지와 15분 이동 경로 재생 기능을 포함한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (컨트롤러·서비스·설정), widyu-domain (SeniorLocation @RedisHash)
- WebSocket 연결 인증 (WsTokenController + WsTokenService + JwtHandshakeInterceptor + JwtChannelInterceptor)
- WebSocket 전용 30초 TTL 1회용 토큰 발급 및 Redis `GETDEL` 원자 소비
- 시니어 위치 업데이트 → Redis 저장 → 보호자 구독 채널 브로드캐스트
- 15분 이동 경로(trail) Redis List 저장 및 조회
- 24시간 체류 정보(stay) Redis 저장 (위치 타입: HOME/OTHER/null)
- 안전구역 이탈 감지 및 FCM 알림 이벤트 발행 (30분 중복 방지)
- TTL 만료 방지 스케줄러 (4분 주기)
- REST 보조 API: 추적 목록 / 마지막 위치 / 이동 경로

### Out of scope
- 안전구역 등록·수정 (parentlocation 도메인 별도 관리)
- 심박수 WebSocket (`/app/heart-rate/send` — heart 도메인)
- 위치 기반 알림 내용 구성 (SafeZoneNotificationListener 담당)

## 3. 인터페이스 / API

### WebSocket STOMP

```
연결 엔드포인트: /ws/location (SockJS 폴백 포함)
핸드셰이크 인증: ?token={wsToken} 쿼리 파라미터 (권장) OR Authorization: Bearer {accessToken} 헤더 (하위 호환)
STOMP CONNECT 인증: Authorization: Bearer {accessToken} native header
```

> 2026-10-17 변경(LLD-0088): 핸드셰이크는 `?token={wsToken}`만 허용하고 `Authorization` JWT 경로를 제거했다. CONNECT는 `Authorization` 헤더를 보지 않고 핸드셰이크 회원을 Principal로 등록한다. 아래 JWT 경로 서술은 변경 전 기록이다.

```http
POST /api/v1/ws/token
Authorization: Bearer {accessToken}
```

WebSocket 연결에만 사용할 30초 TTL 1회용 토큰을 발급한다.

```json
{
  "isSuccess": true,
  "code": "WS_2001",
  "message": "WebSocket 연결 토큰 발급 성공",
  "data": "0b586be4-4c5b-48fd-9e33-0d835f51e427"
}
```

| 방향 | STOMP destination | 설명 |
|------|-------------------|------|
| 시니어 → 서버 | `/app/location/update` | GPS 좌표 전송 |
| 서버 → 시니어 (ACK) | `/user/queue/location/ack` | 업데이트 결과 반환 |
| 서버 → 보호자 | `/topic/location/senior/{memberId}` | 위치 브로드캐스트 |

**요청 페이로드** (`/app/location/update`):
```json
{
  "memberId": 1,
  "latitude": 37.5665,
  "longitude": 126.9780
}
```

**응답 / 브로드캐스트** (`LocationUpdateResponse`):
```json
{
  "memberId": 1,
  "name": "홍길동",
  "profileImage": "https://...",
  "latitude": 37.5665,
  "longitude": 126.9780,
  "stayStartTime": "2026-07-05T14:30:00",
  "locationType": "HOME",
  "locationName": "집"
}
```
- `locationType`: `"HOME"` / `"OTHER"` / `null` (안전구역 밖)
- `locationName`: 등록된 안전구역 이름, 안전구역 밖이면 `null`

### REST API

```http
GET /api/location/seniors
```
보호자가 추적 가능한 시니어 목록 + 현재 위치 반환.

```json
{
  "isSuccess": true,
  "data": [
    {
      "memberId": 1,
      "name": "홍길동",
      "profileImage": "https://...",
      "latitude": 37.5665,
      "longitude": 126.9780
    }
  ]
}
```
- 위치 없으면 `latitude`, `longitude` null

```http
GET /api/location/seniors/{memberId}
```
특정 시니어의 마지막 위치 조회. SeniorLocation(5분 TTL) 우선, 만료 시 StayInfo(24시간 TTL) fallback.

```http
GET /api/location/seniors/{memberId}/trail
```
최근 15분 이동 경로 반환.

```json
{
  "isSuccess": true,
  "data": {
    "memberId": 1,
    "name": "홍길동",
    "profileImage": "https://...",
    "trail": [
      { "latitude": 37.5665, "longitude": 126.9780, "timestamp": "2026-07-05T14:30:00" }
    ]
  }
}
```

## 4. 데이터 모델

### Redis 저장소 (widyu-domain)

| 키 패턴 | 타입 | TTL | 내용 |
|---------|------|-----|------|
| `ws-token:{uuid}` | Redis String | 30s | WebSocket 핸드셰이크 전용 1회용 토큰. 값은 memberId |
| `senior_location:{memberId}` | @RedisHash | 300s (5분) | 최신 위치. `@RedisHash(timeToLive = 300)`로 자동 만료 |
| `location:trail:{memberId}` | Redis List | 900s (15분) | LocationPoint 목록. leftPush, range(0,-1)로 조회 후 reverse |
| `location:stay:{memberId}` | Redis String | 86400s (24시간) | StayInfo (lat, lng, startTime, locationType, locationName) |
| `safezone:alert:{memberId}` | Redis String | 1800s (30분) | 안전구역 이탈 알림 중복 방지 플래그 |

> 2026-10-22 변경(LLD-0098): 위치마다 TTL을 다시 걸어 키가 만료되지 않으므로 `leftPush` 뒤 `LTRIM 0 899`로 최근 900점만 남기고, 조회도 `range(0, 899)`로 읽는다. 위 행의 `range(0,-1)`은 변경 전 기록이다.

**SeniorLocation** (widyu-domain, @RedisHash):
```java
@RedisHash(value = "senior_location", timeToLive = 300)
public class SeniorLocation {
    @Id private Long seniorId;     // memberId
    private Double latitude;
    private Double longitude;
    private LocalDateTime updatedAt;
}
```

**LocationPoint** (DTO, widyu-api):
```java
public record LocationPoint(Double latitude, Double longitude, LocalDateTime timestamp) {}
```

**StayInfo** (DTO, widyu-api):
```java
public record StayInfo(Double latitude, Double longitude, LocalDateTime startTime,
                        String locationType, String locationName) {}
```

## 5. 처리 흐름

### 5-1. WebSocket 연결·인증

```
클라이언트 → POST /api/v1/ws/token
  └─ WsTokenService.issueToken()
       ├─ MemberUtil.getCurrentMember()
       ├─ UUID tokenId 생성
       └─ Redis SET ws-token:{tokenId} = memberId EX 30s

클라이언트 → /ws/location?token={wsToken} (SockJS 핸드셰이크)
  └─ JwtHandshakeInterceptor
       ├─ Authorization 헤더가 없고 ?token= 쿼리 파라미터가 있으면
       │    ├─ URL decode
       │    ├─ WsTokenService.validateAndConsume()
       │    │    └─ Redis GETDEL ws-token:{tokenId}
       │    ├─ 성공 → sessionAttributes에 memberId, memberRole=USER 저장
       │    └─ 실패 → handshake 거부 (false 반환)
       └─ Authorization 헤더가 있으면 하위 호환으로 JWT 검증
            ├─ JwtTokenProvider.retrieveAccessToken() 검증
            ├─ 성공 → sessionAttributes에 memberId, memberRole 저장
            └─ 실패 → handshake 거부 (false 반환)

STOMP CONNECT 시
  └─ JwtChannelInterceptor
       ├─ native Authorization 헤더에서 Bearer 토큰 추출
       ├─ JwtTokenProvider.retrieveAccessToken() 검증
       └─ 성공 → StompHeaderAccessor.user에 PrincipalDetails 주입
```

브라우저 WebSocket 제약 때문에 쿼리 파라미터를 사용하되, URL에 장기 access token을 넣지 않는다.
쿼리 파라미터에는 30초 TTL의 1회용 UUID 토큰만 전달하며, Redis `GETDEL`로 조회와 삭제를 단일 원자 명령으로 처리한다.
`Authorization` 헤더 기반 JWT 핸드셰이크는 하위 호환 경로로 유지한다.
(2026-10-17 LLD-0088에서 이 하위 호환 경로와 CONNECT `Authorization` 처리를 제거했다.)

### 5-2. 위치 업데이트 (`updateAndBroadcast`)

```
1. @MessageMapping("/location/update") 수신
2. resolveMemberId(): principal 또는 sessionAttributes에서 authenticatedMemberId 조회
3. request.memberId()와 authenticatedMemberId 일치 검증 (FORBIDDEN)
4. SeniorProfileRepository.findByMemberId() → SeniorProfile 조회
5. SeniorLocation.of(memberId, lat, lng) 생성 → seniorLocationRepository.save()  [Redis, 5분 TTL]
6. Redis List leftPush: location:trail:{memberId} ← LocationPoint  [15분 TTL]
7. calculateStayInfo():
   a. location:stay:{memberId}에서 이전 StayInfo 조회
   b. GeoUtils.isWithinRadius(이전 위치, 새 위치, 30m) → 같으면 기존 StayInfo 반환
   c. 이동 시: ParentLocation 목록에서 75m 반경 내 안전구역 탐색
   d. 안전구역 이탈 감지: checkAndSendSafeZoneExitAlert() → SafeZoneExitEvent 발행
   e. 새 StayInfo 생성 → opsForValue.set(stayKey, newStay, 24h)
8. LocationUpdateResponse.of(...) 생성
9. messagingTemplate.convertAndSend("/topic/location/senior/{memberId}", response)  [보호자 브로드캐스트]
10. @SendToUser("/queue/location/ack") → 발신자(시니어)에게 ACK 반환
```

트랜잭션 경계: `@Transactional` (updateAndBroadcast 전체)

### 5-3. TTL 갱신 스케줄러

```
@Scheduled(fixedRate = 240_000)  ← 4분마다 (5분 TTL 만료 전)
  └─ SeniorLocation 전체 순회 → save() → TTL 갱신
  └─ location:stay:{memberId} → expire(24h) 갱신
```

시니어가 정지 상태일 때 Redis TTL이 만료되지 않도록 유지.

### 5-4. 안전구역 이탈 감지

```
이전 locationType ≠ null (안전구역 내)
현재 locationType == null (안전구역 밖)
  └─ safezone:alert:{memberId} 존재? → 스킵 (30분 중복 방지)
  └─ 없으면: safezone:alert:{memberId} = true (30분 TTL) 저장
            eventPublisher.publishEvent(SafeZoneExitEvent(memberId))
             └─ SafeZoneNotificationListener → 보호자에게 FCM 발송

안전구역 재진입 시:
  └─ redisTemplate.delete(safezone:alert:{memberId})  ← 플래그 초기화
```

## 6. 예외 / 에러 처리

| 상황 | 처리 |
|------|------|
| WS 토큰 없음 / 만료 / 이미 사용됨 | 핸드셰이크 거부 (연결 불가) |
| Authorization JWT 없음 / 만료 | 핸드셰이크 거부 (연결 불가) |
| STOMP CONNECT Authorization 없음 | CONNECT user 미설정. 이후 메시지 처리에서 세션 memberId fallback 사용 |
| request.memberId ≠ authenticatedMemberId | FORBIDDEN BusinessException |
| 시니어 프로필 없음 | BAD_REQUEST "존재하지 않는 시니어입니다." |
| 보호자가 권한 없는 시니어 조회 | FORBIDDEN "해당 시니어의 위치를 조회할 권한이 없습니다." |
| 최근 위치 없음 (SeniorLocation 만료 + StayInfo 없음) | NOT_FOUND "최근 위치 정보가 없습니다." |

## 7. 인수조건 (Acceptance Criteria)

- [x] 시니어가 JWT 토큰으로 `/ws/location`에 연결할 수 있다
- [x] 인증된 사용자가 `POST /api/v1/ws/token`으로 30초 TTL WS 토큰을 발급받을 수 있다
- [x] `/ws/location?token={wsToken}` 핸드셰이크 시 Redis `GETDEL`로 토큰이 1회만 소비된다
- [x] 같은 WS 토큰을 재사용하면 핸드셰이크가 거부된다
- [x] STOMP CONNECT Authorization 헤더가 있으면 PrincipalDetails가 accessor.user에 설정된다
- [x] 시니어가 `/app/location/update`로 좌표 전송 시 `/topic/location/senior/{memberId}`에 브로드캐스트된다
- [x] 본인이 아닌 memberId로 위치 업데이트 시 FORBIDDEN 에러가 반환된다
- [x] Redis `SeniorLocation`에 최신 좌표가 저장된다 (5분 TTL)
- [x] Redis List `location:trail:{memberId}`에 최근 15분 경로가 누적된다
- [x] 30m 이내 이동 시 체류 시작 시간이 유지된다
- [x] 안전구역 이탈 시 SafeZoneExitEvent가 발행되고 30분 내 중복 발행되지 않는다
- [x] 보호자가 `/api/location/seniors`로 추적 목록을 조회할 수 있다
- [x] 보호자가 `/api/location/seniors/{memberId}/trail`로 이동 경로를 조회할 수 있다
- [x] Swagger에 REST API 엔드포인트 응답이 반영된다
- [x] `./gradlew :backend:widyu-api:test`가 통과한다

## 8. 영향 범위 / 마이그레이션

- 신규 Redis 키 패턴 (기존 DB 스키마 변경 없음)
- `ws-token:*` 키: 30초 TTL, `StringRedisTemplate.opsForValue().getAndDelete()`로 소비
- `SeniorLocation` @RedisHash: widyu-domain 기존 엔티티
- `safezone:alert:*` 키: 기존 없는 패턴, 신규 추가
- access token 쿼리 파라미터 방식은 제거하고, 쿼리 파라미터에는 WS 전용 UUID 토큰만 허용한다.

## 9. 미결정 사항 (Open Questions)

없음. (백필 문서, 구현 완료 상태)

## 10. 참고

- `WebSocketConfig.java`: `/ws/location` 엔드포인트, `/app` prefix, `/topic` + `/queue` broker
- `WsTokenController.java`: `/api/v1/ws/token` 토큰 발급 API
- `WsTokenService.java`: Redis 30초 TTL 토큰 발급, `getAndDelete()` 원자 소비
- `JwtHandshakeInterceptor.java`: WS 토큰/Authorization 헤더 이중 인증
- `JwtChannelInterceptor.java`: STOMP CONNECT Authorization 검증
- `RealtimeLocationService.java`: updateAndBroadcast, calculateStayInfo, refreshLocationTtl
- `RealtimeLocationController.java`: @MessageMapping("/location/update"), @SendToUser
- `SeniorLocation.java` (widyu-domain): `@RedisHash(timeToLive = 300)`
- `GeoUtils.java`: Haversine 공식 거리 계산
