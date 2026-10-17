# LLD-0088: WebSocket 인증을 일회용 토큰으로 통일하고 ACK를 요청 세션에만 보낸다

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 #775 범위로 승인, 2026-10-17) |
| Issue | #775 |
| 관련 ADR | ADR-0017, ADR-0020 |
| 작성자 | Claude |
| 작성일 | 2026-10-17 |

## 1. 목적 / 배경

앱은 WebSocket을 30초 일회용 토큰(`/ws/location?token=`)으로만 연결한다. 이 경로는 핸드셰이크에서 회원 ID를 세션 속성에만 넣고, Spring 사용자(Principal)는 CONNECT에 `Authorization` 헤더가 있을 때만 등록한다. Principal이 없으면 `convertAndSendToUser(memberId, ...)`의 전달 대상이 0개라 **시니어 앱이 심박 처리 결과 ACK를 받지 못한다**(Spring 6.1.14 사용자 목적지 변환으로 재현). 같은 회원의 다른 Principal 세션이 있으면 ACK가 그 세션으로 간다.

인증 주체가 두 갈래(세션 속성 / Principal)라서 사용자 키·세션 키·ACK 목적지가 경로마다 달라졌고, 2026-09-08 심박 부하 측정에서도 ACK 대상 키 문제로 ACK를 받지 못했다. 인증 경로를 일회용 토큰 하나로 줄이고, 그 결과를 Principal 하나로 표현하며, 요청 응답은 요청한 세션에만 보낸다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- 핸드셰이크: `Authorization` JWT 인증 경로 제거. `token` 쿼리의 일회용 토큰만 허용
- CONNECT: CONNECT 헤더의 `Authorization` JWT 처리 제거. 핸드셰이크가 세션 속성에 넣은 회원으로 Principal을 등록
- 요청 응답 라우팅: 위치 ACK·오류 응답의 `@SendToUser`를 `broadcast = false`로. 심박 ACK는 기존 session 헤더 방식 유지(Principal 등록으로 발신 세션 1개로 해석됨). 센서는 이미 `broadcast = false`
- 컨트롤러·인터셉터의 회원 식별을 Principal 기준으로 정리(세션 속성 폴백 제거)
- 실제 STOMP 클라이언트 통합 테스트 추가, 변경 전후 ACK 수신 수·교차 수신 수 측정
- `docs/architecture/websocket.md`의 인증 서술 갱신

### Out of scope
- 핸드셰이크 Origin allowlist (#636)
- 심박 최신값 역전 방지(측정 시각 비교 갱신) — 설계 검토의 두 번째 후보, 별도 이슈
- 다중 노드 브로커 중계, heartbeat 변경, inbound 풀 격리
- 일회용 토큰 발급 API(`WsTokenService`)·GETDEL 소비 로직 변경
- 모바일 앱 코드, 저장소 밖 부하 측정 스크립트

## 3. 인터페이스 / API

HTTP·DTO 변경 없음. STOMP 계약은 아래만 바뀐다.

| 경계 | 변경 전 | 변경 후 |
| --- | --- | --- |
| 핸드셰이크 인증 | HTTP `Authorization: Bearer <JWT>` **또는** `?token=<일회용 토큰>` | `?token=<일회용 토큰>`만. 없거나 무효·만료·소비됨·비활성 회원이면 거부 |
| CONNECT | `Authorization` 네이티브 헤더가 유효하면 Principal 등록, 없으면 Principal 없음 | 헤더와 무관하게 핸드셰이크 회원으로 Principal 등록. 세션 속성에 회원이 없으면 CONNECT 폐기 |
| `/user/queue/location/ack` | Principal 있으면 같은 회원의 모든 구독 세션 | 발신 세션만 |
| `/user/queue/heart-rate/result` | Principal 없으면 0개, 있으면 발신 세션 | 발신 세션만 |
| `/user/queue/sensor/result` | 발신 세션 | 변경 없음 |
| `/user/queue/errors` | Principal 있으면 같은 회원의 모든 구독 세션 | 발신 세션만 |

Principal 이름은 `PrincipalDetails.getUsername()` = 회원 ID 문자열이다. 역할은 기존 일회용 토큰 경로와 같이 `MemberRole.USER`.

## 4. 데이터 모델

DB·Redis 변경 없음. 세션 속성 키 `memberId`·`memberRole`과 `WebSocketSessionRegistry`(노드 로컬 세션→회원 매핑)는 유지한다. 관련 ERD 변경 없음.

## 5. 처리 흐름

1. 앱이 REST로 일회용 토큰을 받는다(기존).
2. 핸드셰이크: `token` 쿼리를 GETDEL로 소비 → 회원 ID 확인 → ACTIVE 확인 → 세션 속성에 `memberId`·`memberRole` 저장. 실패면 핸드셰이크 거부.
3. CONNECT(`JwtChannelInterceptor.handleConnect`): 세션 속성에서 회원을 읽어 `PrincipalDetails` 기반 Authentication을 `accessor.setUser()`로 등록하고 `WebSocketSessionRegistry`에 등록. 세션 속성에 회원이 없으면 `null`을 반환해 CONNECT를 폐기한다.
4. 이후 SUBSCRIBE·SEND·`@MessageMapping`은 Principal로 회원을 식별한다. 가족 토픽 인가·전달 직전 재검증은 그대로다.
5. 응답: 위치·오류는 `@SendToUser(broadcast = false)`, 심박은 `convertAndSendToUser(memberId, dest, payload, sessionHeader)`, 센서는 기존 `broadcast = false`. 모두 발신 세션 1개로 해석된다.

## 6. 예외 / 에러 처리

- 핸드셰이크 거부는 기존처럼 `beforeHandshake`가 false를 반환한다(HTTP 핸드셰이크 실패).
- CONNECT 폐기는 기존 인가 거절과 같이 `null` 반환이다. 새 STOMP ERROR 프레임을 도입하지 않는다.
- 컨트롤러에서 Principal이 없으면 기존 메시지(`IllegalStateException`)를 유지해 `/user/queue/errors`로 응답한다. CONNECT가 Principal을 보장하므로 정상 흐름에서는 일어나지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: `Authorization` 헤더만 있고 `token` 쿼리가 없는 핸드셰이크는 거부된다.
- [x] AC2: 유효한 일회용 토큰으로 연결한 세션은 CONNECT 후 Principal 이름이 회원 ID다. 세션 속성에 회원이 없는 CONNECT는 폐기된다.
- [x] AC3: CONNECT의 `Authorization` 헤더는 Principal 결정에 쓰이지 않는다(다른 회원 JWT를 실어도 핸드셰이크 회원이 유지된다).
- [x] AC4: 일회용 토큰으로만 연결한 세션이 심박·위치·센서 요청을 각각 보내면 각 ACK를 1건씩 받는다(실제 STOMP 클라이언트 통합 테스트).
- [x] AC5: 같은 회원이 일회용 토큰 두 개로 두 세션을 열고 각 세션이 N건씩 요청하면, 각 세션은 자기 요청의 ACK N건만 받고 교차 수신은 0건이다. 오류 응답도 발신 세션에만 간다.
- [x] AC6: 가족 토픽 구독 인가·비가족 거절·전달 직전 재검증 기존 테스트가 통과한다.
- [x] AC7: AC4·AC5와 같은 시나리오를 변경 전 코드에서 실행한 수치(ACK 수신 수·교차 수신 수)를 기록하고, 변경 후 수치와 함께 남긴다.
- [x] AC8: `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다.

2026-10-17 검증: 실제 SockJS STOMP 클라이언트 통합 테스트(`WebSocketSessionAckIntegrationTest`, N=100)를 변경 전 코드에서 먼저 실행했다. 토큰 단일 세션의 심박 ACK 0/100, CONNECT JWT 세션이 섞이면 A의 심박 ACK 100건이 모두 B로 가고, CONNECT JWT 쌍은 오류·위치 ACK가 B에도 100건씩 갔다. 변경 후 심박·위치·센서 ACK 각 100/100, 같은 회원 두 세션의 교차 수신 0, 오류는 발신 세션에만 갔다. `verify.sh --base 0d264ae6`는 API 테스트 1452건 실패 0·건너뜀 11(테스트 Redis 조건 8, 영상 벤치마크 3)로 통과했다.

구현 중 확인해 함께 고친 점: ① STOMP 핸들러의 회원 식별을 Principal 하나로 정리했다. 기존 `@AuthenticationPrincipal` 파라미터는 STOMP 메시지 처리에서 Spring Security 인증 객체로 해석되지 않으므로, 회원은 `SimpMessageHeaderAccessor.getUser()`의 Principal에서 읽는다. ② 심박 `sessionHeader()`가 `toMap()` 일반 Map을 넘겨 `SimpMessagingTemplate`이 세션 ID를 네이티브 헤더로 옮겼다. Principal 등록 뒤 심박 ACK가 같은 회원의 모든 세션으로 퍼져(교차 100/100) `getMessageHeaders()`로 바꿨다. 실제 브라우저·모바일 앱·다중 서버 검증은 미실행이다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 핸드셰이크·CONNECT에 JWT를 쓰던 클라이언트는 연결이 거부되거나 Principal이 핸드셰이크 회원으로 고정된다. 앱은 일회용 토큰만 쓰는 것을 사용자에게 확인했다(2026-10-17). 저장소 밖 부하 측정 스크립트가 CONNECT `Authorization`에 의존하면 일회용 토큰 발급으로 바꿔야 한다.

신규 ADR N/A: ADR-0017(심박 발신 세션 ACK)·ADR-0020(가족 접근)의 결정을 인증 경로 하나로 일관되게 적용하는 구현이다. 선택과 기각한 대안은 아래에 둔다.

- **심박 ACK만 session ID로 보내기**: 국소 해결은 되지만 세션 속성/Principal 두 인증 주체가 남아 다음 응답 경로에서 같은 문제가 반복된다.
- **JWT 경로를 남기고 두 주체가 다르면 거절**: 앱이 쓰지 않는 경로를 유지할 이유가 없고, 장기 JWT가 핸드셰이크에 실리는 경로가 남는다.
- **회원당 연결 1개 강제**: 폰·워치 동시 연결을 막으므로 제품 요구 없이 적용하지 않는다.

## 미결정 사항(Open Questions)

없음.

## 10. 참고

- [LLD-0001](LLD-0001-websocket-realtime-location.md), [LLD-0023](LLD-0023-heart-rate-single-measurement-transport.md), [LLD-0032](LLD-0032-websocket-destination-allowlist.md)
- Spring 6.1.14 `DefaultUserDestinationResolver`(등록 사용자 없으면 대상 0개), `SendToMethodReturnValueHandler`(사용자 없으면 세션 ID·broadcast 해제)
- `JwtHandshakeInterceptor`, `JwtChannelInterceptor`, `HeartRateWebSocketController`, `RealtimeLocationController`, `SensorBatchWebSocketController`, `WebSocketExceptionHandler`
