# LLD-0097: 체류 정보 Redis 읽기 복원

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #806 |
| 관련 ADR | - |
| 작성자 | Claude / feature-806 |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

`location:stay:{memberId}`에 저장한 체류 정보(`StayInfo`)를 다시 읽지 못해 안심구역 이탈·돌아옴 알림이 한 번도 발행되지 않았다.

`RedisConfig`의 `GenericJackson2JsonRedisSerializer(objectMapper)`는 주어진 매퍼를 그대로 쓰며 default typing을 켜지 않는다. 저장된 JSON에 타입 정보가 없으므로 `opsForValue().get()`은 `LinkedHashMap`을 돌려준다. `RealtimeLocationService`의 `instanceof StayInfo` 3곳이 항상 거짓이 되어 다음이 모두 깨져 있었다(#194, b41b43b6부터).

- 이전 위치 타입이 항상 `null`이라 `SafeZoneAlertService`가 `안→밖`(이전 `null`이면 반환)·`밖→안`(`EXITED` 표식 없음)을 모두 건너뛴다.
- 체류 시작 시각이 매 갱신마다 새로 잡혀 체류 시간이 0분이다.
- 홈 카드 외출 여부가 `null`, 마지막 위치의 `locationType`·`locationName`이 `null`이다.

기존 단위 테스트는 `valueOperations.get()`이 `StayInfo` 객체를 돌려주도록 mock해 이 결함을 가렸다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api
- 체류 정보 읽기를 `RealtimeLocationService.getStayInfo(memberId)` 하나로 모은다(체류 계산·마지막 위치·추적 목록·외출 여부).
- Redis가 돌려준 값을 Redis 직렬화기와 같은 ObjectMapper 설정으로 `StayInfo`로 변환한다. 설정은 `RedisConfig.redisObjectMapper()` 한 곳에서 만든다.
- 값을 읽을 수 없을 때(역직렬화·변환 실패)만 키를 지우고, Redis 연결·시간 초과 오류에는 지우지 않는다.
- 로그에는 예외 클래스 이름만 남긴다. Jackson 예외 메시지에는 원문 JSON 일부(좌표)가 섞일 수 있다(LLD-0029).

### Out of scope

- 직렬화기 전체에 default typing을 켜는 변경. 이미 저장된 값과 같은 템플릿을 쓰는 다른 키의 형식이 바뀐다.
- 이동 경로(`location:trail:*`)의 수동 `LinkedHashMap` 변환 정리. 지금도 동작하며, 이동 경로 상한 작업(LLD-0098)과 같은 줄을 건드린다.
- 체류 정보 조회가 연결 오류로 실패할 때 위치 갱신을 실패시킬지 여부(9절).
- 4분 스케줄러의 `updatedAt`, v2 판별 조건 등 리뷰에서 함께 나온 다른 위치 결함.

## 3. 인터페이스 / API

API 계약 변경 없음. 응답 필드(`stayStartTime`·`stayDurationMinutes`·`locationType`·`locationName`, 홈 카드 외출 여부)가 실제 값으로 채워진다.

## 4. 데이터 모델

DB·Redis 스키마 변경 없음. 저장 형식(타입 정보 없는 JSON, `startTime`은 ISO-8601 문자열)과 TTL(86400s)은 그대로다. 이미 저장된 값도 같은 방법으로 읽힌다.

## 5. 처리 흐름

`getStayInfo(memberId)`:

1. `opsForValue().get("location:stay:{memberId}")`로 값을 읽는다. 없으면 `null`.
2. `RedisConfig.redisObjectMapper().convertValue(raw, StayInfo.class)`로 변환해 돌려준다.
3. `SerializationException`·`IllegalArgumentException`(값 손상)이면 WARN 후 키를 지우고 `null`. 변환은 됐지만 `latitude`·`longitude`·`startTime` 중 하나가 없으면 거리 계산·체류 시간에서 null로 실패하므로 같은 손상으로 처리한다.
4. `DataAccessException`(연결·시간 초과)이면 WARN 후 키를 두고 `null`.

`calculateStayInfo`는 이 결과로 이전 위치 타입을 정하고, 새 체류 정보 커밋 후 저장(LLD-0058)과 `SafeZoneAlertService` 전이 판정(LLD-0078)은 그대로 쓴다.

## 6. 예외 / 에러 처리

- 새 에러 코드 없음.
- 연결 오류로 읽지 못한 경우 `calculateStayInfo`는 지금처럼 이전 체류가 없는 것으로 보고 진행한다. 이때 그 갱신의 이탈은 감지하지 못한다. 달라지는 점은 키를 지우지 않는다는 것뿐이다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 운영 직렬화기를 거친 `HOME` 체류 정보에서 밖으로 이동하면 `handleSafeZoneTransition(memberId, "HOME", null)`을 호출한다.
- [x] AC2. 운영 직렬화기를 거친 체류 정보로 마지막 위치를 조회하면 저장된 체류 시작 시각·`locationType`·`locationName`을 돌려준다.
- [x] AC3. 격리 Redis에서 `안→밖→안`으로 이동하면 `SafeZoneExitEvent`·`SafeZoneEnterEvent`가 한 번씩, 이 순서로 발행된다.
- [x] AC4. 격리 Redis에서 30m 안의 재전송은 첫 체류 시작 시각을 유지한다.
- [x] AC5. 격리 Redis에서 이탈 갱신이 롤백되면 체류 정보와 알림 키가 이전 상태로 남아 다음 갱신이 이탈을 다시 감지한다(LLD-0058 경로 도달).
- [x] AC6. Redis 연결 오류로 읽지 못하면 키를 지우지 않고, 값을 읽을 수 없으면 키를 지운다.
- [x] AC6-1. 좌표·시작 시각이 빠진 값은 키를 지우고 이전 체류가 없는 것으로 위치를 갱신한다(예외 없음).
- [x] AC7. 변경한 테스트 클래스와 `compileJava`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- 배포 직후부터 이탈·돌아옴 알림(Z01·Z02)이 실제로 발행된다. 지금까지 발행된 적이 없으므로 보호자에게는 새 알림처럼 보인다.
- 체류 시간·외출 여부·위치 이름이 실제 값으로 바뀌어 앱 표시가 달라진다.
- 마이그레이션·설정 변경 없음.

## 9. 미결정 사항 (Open Questions)

- [ ] 위치 갱신 중 체류 정보 조회가 Redis 연결·시간 초과로 실패하면 갱신 전체를 실패시킬지. 지금은 이전 체류가 없는 것으로 보고 진행해 그 갱신의 이탈을 놓칠 수 있다. 실패시키면 ACK가 실패하고 앱이 다시 보낸다.

## 10. 참고

- Issue #806, #194
- LLD-0001, LLD-0058, LLD-0078, LLD-0029
