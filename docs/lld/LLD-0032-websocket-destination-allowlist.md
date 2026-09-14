# LLD-0032: WebSocket 목적지 allowlist

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (사용자가 승인한 #602 범위) |
| Issue | #602 |
| 관련 ADR | ADR-0002, ADR-0020, ADR-0017 |
| 작성자 | Codex |
| 작성일 | 2026-09-14 |

## 1. 목적 / 배경

기존 SUBSCRIBE 검사는 보호 토픽 접두사 밖의 `/topic/**`를 통과시켜 가족 검증을 우회할 수 있다. SEND는 목적지를 검사하지 않아 클라이언트가 브로커에 위치·심박 메시지를 직접 발송할 수 있다. 인바운드 채널에서 명시한 목적지만 허용한다.

## 2. 범위

- In scope: widyu-api의 JwtChannelInterceptor, 보안 회귀 테스트, 기존 ACK 호환성.
- Out of scope: #603 세션 폐기, DB tokenversion, 관리자 필터, JwtTokenProvider, 운영/AWS 호출, 커밋·push.

## 3. 인터페이스 / API

| 명령 | 정확한 목적지 | 조건 |
| --- | --- | --- |
| SUBSCRIBE | `/topic/location/senior/{memberId}` | 인증 및 FamilyAccessService 가족 인가 |
| SUBSCRIBE | `/topic/heart-rate/{memberId}` | 인증 및 FamilyAccessService 가족 인가 |
| SUBSCRIBE | `/user/queue/location/ack` | 인증된 사용자, Spring user destination 유지 |
| SUBSCRIBE | `/user/queue/heart-rate/result` | 인증된 사용자, 기존 발신 세션 ACK 유지 |
| SUBSCRIBE | `/user/queue/errors` | 인증된 사용자, 기존 오류 응답 유지 |
| SEND | `/app/location/update` | 인증, 기존 발신자 검증 유지 |
| SEND | `/app/heart-rate/send-single` | 인증, 기존 단건 처리 유지 |

memberId는 기존 계약인 ASCII 숫자 1~18자리를 유지한다. 존재·시니어 여부·가족 관계는 기존 서비스가 검증한다. 그 외 목적지와 목적지 누락은 거절한다. wildcard, suffix, raw `/queue`, 타 사용자 지정 `/user/{id}/queue`도 허용하지 않는다. HTTP·DTO·Swagger 변경은 없다.

## 4. 데이터 모델

변경 없음. ERD-0001의 Member, SeniorProfile, FamilyMembership 관계를 기존 FamilyAccessService로 조회한다. 서버가 인증 후 저장한 세션 memberId fallback을 유지한다.

## 5. 처리 흐름

1. CONNECT는 기존 인증 처리를 유지한다.
2. SUBSCRIBE는 인증 회원을 확인하고 사용자 큐 3개 또는 정확한 가족 토픽인지 검사한다.
3. 가족 토픽은 추출한 대상 회원과 호출자 ID로 가족 검증 후 통과시킨다.
4. SEND는 인증 회원과 정확한 애플리케이션 목적지 2개를 확인한다.
5. heartbeat(command 없음), ACK/NACK, UNSUBSCRIBE, DISCONNECT 등 제어 프레임은 기존대로 통과시킨다.

인터셉터는 clientInboundChannel에만 적용되므로 서버의 브로커 발송은 유지된다. 트랜잭션·이벤트 경계는 변경하지 않는다.

## 6. 예외 / 에러 처리

인가 거절은 기존 SUBSCRIBE와 같이 null 반환으로 프레임을 폐기한다. 새 STOMP ERROR 또는 오류 큐 응답을 도입하지 않는다. 가족 검증의 BusinessException은 거절하고 예상 밖 오류는 전파된다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 정확한 위치·심박 토픽은 가족 인가 후 허용하고 비가족은 거절한다.
- [x] AC2: 상위 wildcard, 변형 경로, raw 큐, 누락 목적지 구독은 거절한다.
- [x] AC3: 인증된 SEND는 두 경로만 허용하며 브로커 직접 발송·레거시 배치·변형 경로는 거절한다.
- [x] AC4: 인증 누락은 허용 목적지에서도 거절하며 Principal과 인증 세션 fallback을 유지한다.
- [x] AC5: 사용자 ACK·오류 큐 구독과 heartbeat·ACK/NACK·unsubscribe·disconnect를 유지한다.
- [x] AC6: 채널을 통한 차단과 정상 전달 회귀 테스트 및 `bash scripts/harness/verify.sh`가 통과한다.

2026-09-14 검증: Java 21 하네스 정적 검사·compileJava·API 테스트 통과. 총 586건 중 577건 통과, 실패/오류 0건, 9건 건너뜀(로컬 Redis 부재 6건, 영상 fixture 부재 3건). JwtChannelInterceptorTest 68건은 모두 통과했으며 실제 Simple Broker 전달 회귀 2건을 포함한다. 기존 HeartRateWebSocketControllerTest의 발신 세션 ACK 검증도 통과했다. 실제 브라우저/SockJS 네트워크 E2E는 실행하지 않았다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. 미등록 목적지를 쓰는 클라이언트는 거절되며 신규 경로 추가 시 allowlist와 회귀 테스트를 함께 수정해야 한다. 신규 ADR N/A: 기존 ADR-0002·0020의 명시적 가족 인가를 STOMP 경계에서 누락 없이 적용하는 구현으로, 구체적인 경로·대안은 이 LLD에 기록한다.

접두사 차단 목록 확장은 상위 패턴 누락을 반복할 수 있어 제외한다. Spring Security 메시징 전면 도입은 인증·제어 프레임 설정까지 범위가 확대되므로 제외하고 기존 인터셉터의 정확한 allowlist를 선택한다.

## 미결정 사항(Open Questions)

없음. 기존 구독의 가족 탈퇴 후 즉시 폐기와 세션 수명 관리는 별도 작업이다.

## 10. 참고

- [LLD-0001](LLD-0001-websocket-realtime-location.md), [LLD-0023](LLD-0023-heart-rate-single-measurement-transport.md).
- WebSocketConfig, RealtimeLocationController/Service, HeartRateWebSocketController, WebSocketExceptionHandler.
