# LLD-0011: 안전구역 이탈 알림 중복 차단 원자화

> Redis 30분 알림 키를 이용한 중복 차단은 [LLD-0071](LLD-0071-safe-zone-incident.md)이 대체한다.

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #422 |
| 관련 ADR | ADR-0009 |
| 작성자 | Codex |
| 작성일 | 2026-07-21 |

## 1. 목적 / 배경

안전구역 이탈 알림 중복 차단이 Redis `hasKey()` 후 `set()`으로 나뉘어 있어 병렬 위치 업데이트에서 이벤트가 중복 발행될 수 있다.
플래그 생성과 TTL 설정을 Redis 원자 동작으로 바꾸고, 알림 플래그 처리 책임을 별도 서비스로 분리한다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api
- `SafeZoneAlertService`를 추가한다.
- 안전구역 이탈 시 `safezone:alert:{memberId}` 키를 Redis `set-if-absent` + TTL로 생성한다.
- 플래그 생성에 성공한 경우에만 `SafeZoneExitEvent`를 발행한다.
- 안전구역 재진입 시 `safezone:alert:{memberId}` 키를 삭제하는 기존 동작을 유지한다.
- 위치 업데이트, 최신 위치 저장, 이동 경로 저장, 브로드캐스트 응답 계약은 유지한다.

### Out of scope

- FCM 전송 실패 재시도, outbox, 메시지 큐 도입
- 안전구역 반경 정책 변경
- 안전구역 등록·수정·삭제 API 변경
- WebSocket/REST API 계약 변경

## 3. 인터페이스 / API

기존 API 계약 변경 없음.

- WebSocket: `/app/location/update`
- 보호자 브로드캐스트 destination: `/topic/location/senior/{memberId}`
- 안전구역 이탈 이벤트: `SafeZoneExitEvent`

## 4. 데이터 모델

DB 스키마 변경 없음.
Redis 키 패턴은 기존과 동일하다.

| 키 패턴 | 타입 | TTL | 내용 |
| --- | --- | --- | --- |
| `safezone:alert:{memberId}` | Redis String | 1800s | 안전구역 이탈 알림 중복 방지 플래그 |

## 5. 처리 흐름

`RealtimeLocationService.calculateStayInfo()`

1. 이전 `StayInfo`를 조회한다.
2. 30m 이내 같은 위치면 기존 `StayInfo`를 반환한다.
3. 새 위치가 어떤 안전구역에 속하는지 판단한다.
4. 이전 위치 타입과 현재 위치 타입을 `SafeZoneAlertService.handleSafeZoneTransition()`에 전달한다.
5. 새 `StayInfo`를 저장한다.

`SafeZoneAlertService.handleSafeZoneTransition(memberId, previousLocationType, currentLocationType)`

1. 현재 위치 타입이 있으면 안전구역 재진입으로 보고 `safezone:alert:{memberId}` 키를 삭제한다.
2. 이전 위치 타입이 있고 현재 위치 타입이 없으면 안전구역 이탈로 판단한다.
3. Redis `set-if-absent(alertKey, true, 1800s)`를 호출한다.
4. 반환값이 true이면 `SafeZoneExitEvent(memberId)`를 발행한다.
5. 반환값이 false이면 이미 30분 중복 차단 중이므로 이벤트를 발행하지 않는다.

트랜잭션 경계:

- `RealtimeLocationService.updateAndBroadcast()`의 기존 `@Transactional` 경계는 유지한다.
- 알림 중복 차단은 Redis 원자 연산으로 처리한다.
- `@EventListener` 구조는 유지한다.

## 6. 예외 / 에러 처리

- Redis `set-if-absent` 또는 `delete` 실패 시 기존 Redis 작업과 동일하게 예외가 전파된다.
- FCM 전송 실패 재시도 정책은 이번 범위에서 변경하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 안전구역 재진입 시 알림 플래그를 삭제한다.
- [x] 안전구역 이탈 시 플래그 생성에 성공한 경우에만 이벤트를 발행한다.
- [x] 이미 알림 플래그가 있으면 이벤트를 발행하지 않는다.
- [x] 안전구역 안에서 안전구역 밖으로 이동하지 않은 경우 이벤트를 발행하지 않는다.
- [x] 위치 업데이트의 권한 검증, 위치 저장, trail 저장, 브로드캐스트 흐름은 유지된다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DB/Redis 스키마 변경 없음.
- 기존 `safezone:alert:{memberId}` 키 패턴과 TTL을 유지한다.
- API 계약 변경 없음.

## 9. 미결정 사항 (Open Questions)

없음.

## 10. 참고

- Issue #422
- ADR-0009
- LLD-0001
