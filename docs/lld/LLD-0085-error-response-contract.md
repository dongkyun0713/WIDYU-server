# LLD-0085: 원인별 에러 응답 계약

> 이 문서는 이슈 #756의 첫 번째 PR(에러 응답 계약·전역 처리기) 구현과 검수 기준이다. DTO 검증 보강은 같은 이슈의 두 번째 PR에서 이 계약을 따른다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-12) |
| Issue | #756 |
| 관련 ADR | - |
| 선행 | [LLD-0027](LLD-0027-development-error-observability.md) 개발 오류 관측·응답 계약, [LLD-0084](LLD-0084-discord-alert-ai-analysis.md) 오류 알림 |
| 작성자 | Claude / WIDYU-server-error-756 |
| 작성일 | 2026-10-12 |

## 1. 목적 / 배경

입력이 잘못됐는데도 서버가 500 `SRV_5000`("서버 내부 오류가 발생했습니다.")으로 응답하는 경우가 많았다. 대표적인 예는 건강 일정 이름을 255자보다 길게 보낸 경우로, DB 길이 초과 때문에 500이 났다. 프론트는 응답만 보고 무엇이 잘못됐는지 알 수 없었다. 입력 오류도 원인과 상관없이 `REQ_4000` 하나였고, 틀린 필드는 message 문자열 안에 첫 번째 하나만 들어갔다.

이번 변경으로 응답이 이렇게 바뀐다.
- 원인마다 다른 코드로 응답한다.
- 400 응답에는 틀린 필드 목록을 구조화해서 담는다.
- dev·local의 5xx 응답에는 원인 메시지를 담는다.

## 2. 범위

### In scope
- 변경 모듈: widyu-domain(`ErrorCode`), widyu-api(`GlobalExceptionHandler`, 응답 DTO)
- 입력 오류 코드 세분화와 `errors` 필드 추가
- 처리기가 없던 DB·락·연결 예외의 원인별 매핑
- dev·local 5xx `debug.message` 추가
- 응답 계약이 바뀐 Swagger 예시 수정

### Out of scope
- **DTO 검증 보강**: 길이 검증 `@Size`, 빠진 `@Valid`, page·size·year·month 범위 검증. #756의 두 번째 PR에서 한다.
- **`IllegalArgumentException`·`DateTimeException`의 전역 400 매핑**: 서버 버그도 같은 예외를 던지므로 전역에서 400으로 바꾸지 않는다. 원인 지점의 검증으로 막는다.
- **JWT 필터의 별도 오류 응답 형식**(`ErrorResponse`, traceId 없음)
- **WebSocket STOMP 오류 응답**(LLD-0084의 `WebSocketExceptionHandler`)
- **도메인 ErrorCode 정리**: 401인데 `APPLE_5003`처럼 상태와 맞지 않는 코드 등

## 3. 인터페이스 / API

### 응답 형식

기존 필드(`code`, `message`, `data`, `traceId`, `debug`)는 그대로 둔다. `errors`는 필드를 특정할 수 있는 400 응답에만 붙는다.

```json
{
  "code": "REQ_4001",
  "message": "일정 이름은 255자 이하로 입력해 주세요 (scheduleName)",
  "data": null,
  "traceId": "259f92aa-31ad-4223-afb4-7a1b1ff41415",
  "errors": [
    {"field": "scheduleName", "reason": "일정 이름은 255자 이하로 입력해 주세요."},
    {"field": "scheduledAt", "reason": "일정 날짜는 필수입니다."}
  ]
}
```

- `message`는 기존 클라이언트가 그대로 보여 줄 수 있도록 이전 형식("첫 번째 사유 (필드)")을 유지한다.
- `field`는 요청 본문 기준 경로다. 중첩·배열은 `items[0].count`처럼 쓴다. 쿼리 파라미터·파트·헤더는 그 이름을 쓴다.

dev·local의 5xx 응답:

```json
{
  "code": "SRV_5001",
  "message": "데이터를 저장하지 못했습니다.",
  "data": null,
  "traceId": "…",
  "debug": {
    "exceptionType": "DataIntegrityViolationException",
    "requestUri": "/api/v1/goals/health-schedules/guardians",
    "message": "Column 'member_fcm_token_id' cannot be null"
  }
}
```

`debug.message`는 가장 안쪽 원인의 메시지를 한 줄로 바꾸고 300자에서 자른 값이다. prod(`observability.error-response.include-debug-details=false`)와 `/api/v1/auth/**`에는 `debug`가 붙지 않는다(LLD-0027과 같은 조건).

### 원인별 코드

| 원인 | HTTP | 코드 | errors | 로그 |
| --- | --- | --- | --- | --- |
| 업무 규칙 위반 (`BusinessException`) | ErrorCode 기준 | 도메인 코드, 범용은 `REQ_4000` | 없음 | 4xx WARN, 5xx ERROR |
| Bean Validation 실패 (`@Valid` 본문, 파라미터 제약) | 400 | `REQ_4001` | 실패한 필드 전부 | WARN |
| 형식 오류 (JSON 문법·타입, 경로·쿼리 타입 불일치) | 400 | `REQ_4002` | 위치를 알 수 있을 때 | WARN |
| 필수값 누락 (쿼리 파라미터·multipart 파트·헤더) | 400 | `REQ_4003` | 누락 이름 | WARN |
| DB 길이 초과 | 400 | `REQ_4004` | 컬럼 이름을 필드 이름으로 바꾼 값 | **ERROR** |
| DB 중복 키 (유니크 위반, `DuplicateKeyException`) | 409 | `REQ_4090` | 없음 | WARN |
| 그 밖의 DB 무결성 위반 (NOT NULL·FK 등) | 500 | `SRV_5001` | 없음 | ERROR |
| 락 대기 실패·쿼리 시간 초과·비동기 요청 시간 초과 | 503 | `SRV_5031` | 없음 | ERROR |
| DB·Redis 연결 실패, 트랜잭션 생성 실패 | 503 | `SRV_5032` | 없음 | ERROR |
| 그 밖의 처리하지 못한 예외 | 500 | `SRV_5000` | 없음 | ERROR |

**DB 길이 초과를 ERROR로 남기는 이유**: 클라이언트 입력 때문이라 400으로 응답하지만, 요청 검증이 빠졌다는 서버 신호이기도 하다. 그래서 개발 서버 오류 알림(LLD-0084)이 계속 잡도록 ERROR로 남긴다.

### 기존 응답과 달라지는 점

| 상황 | 이전 | 이후 |
| --- | --- | --- |
| `@Valid` 검증 실패 | `REQ_4000`, message에 첫 필드만 | `REQ_4001` + `errors` 전체, message 형식은 같음 |
| JSON 파싱 실패, 타입 불일치 | `REQ_4000` | `REQ_4002` (+ `errors`) |
| 필수 파라미터·파트 누락 | `REQ_4000` | `REQ_4003` + `errors` |
| 필수 헤더 누락 | 500 `SRV_5000` | 400 `REQ_4003` |
| DB 길이 초과 | 500 `SRV_5000` | 400 `REQ_4004` |
| DB 중복 키 | 500 `SRV_5000` | 409 `REQ_4090` |
| NOT NULL·FK 위반 | 500 `SRV_5000` | 500 `SRV_5001` |
| 락·타임아웃, DB·Redis 연결 실패 | 500 `SRV_5000` | 503 `SRV_5031`·`SRV_5032` |

## 4. 데이터 모델

DB 변경 없음. 바뀌는 응답 DTO는 다음과 같다.
- `global/response/FieldErrorDetail(field, reason)` 신규
- `ApiResponseTemplate.errors` 추가(`@JsonInclude(NON_NULL)`)
- `ApiErrorDebugInfo.message` 추가

`ErrorCode`에 `INVALID_INPUT_VALUE`, `INVALID_REQUEST_FORMAT`, `MISSING_REQUIRED_VALUE`, `INPUT_TOO_LONG`, `DUPLICATE_RESOURCE`, `DATA_INTEGRITY_ERROR`, `TEMPORARILY_BUSY`, `DEPENDENCY_UNAVAILABLE`을 추가한다.

## 5. 처리 흐름

1. 예외가 컨트롤러 밖으로 나오면 `GlobalExceptionHandler`가 예외 타입별 처리기를 고른다. Spring은 가장 구체적인 타입의 처리기를 먼저 고른다.
2. `DataIntegrityViolationException`은 `DataIntegrityViolationKind`가 원인 사슬에서 `SQLException`을 찾아 아래처럼 나눈다. 판단 근거는 SQLState와 벤더 코드다.
   - SQLState `22001` 또는 MySQL `1406` → 길이 초과
   - MySQL `1062`, SQLState `23505`(H2 등), 또는 `DuplicateKeyException` → 중복
   - 나머지 → 서버 데이터 문제
3. 길이 초과면 메시지의 컬럼 이름(`schedule_name`)을 camelCase(`scheduleName`)로 바꿔 `field`로 쓴다. 대부분의 DTO 필드가 엔티티 필드와 이름이 같다는 전제이고, 컬럼을 찾지 못하면 `errors`를 생략한다.
4. 5xx는 `doSystemLog`로 `[EXCEPTION]` ERROR를 남기고, 디버그 설정이 켜져 있으면 `debug`를 붙인다.

## 6. 예외 / 에러 처리

- `ObjectOptimisticLockingFailureException`은 기존대로 409 `POINT_4090`이다. 낙관적 락은 포인트 차감에서만 쓴다.
- 비관적 락 실패는 `@RetryOnTransientLockFailure`가 재시도한 뒤에도 실패한 경우에만 여기까지 온다.
- `jakarta.validation.ConstraintViolationException`은 매핑하지 않는다. 지금은 `@Validated` 컨트롤러가 없고, JPA 엔티티 검증에서 나는 경우는 서버 데이터 문제라서 400으로 바꾸면 안 된다.

## 7. 인수조건 (Acceptance Criteria)

- [x] `@Valid` 본문 검증이 여러 필드에서 실패하면 400 `REQ_4001`과 필드별 사유 전체를 `errors`로 응답한다. message 형식은 이전과 같다. (`GlobalExceptionHandlerTest`, `PaymentControllerTest`)
- [x] JSON 값 형식이 틀리면 400 `REQ_4002`와 틀린 위치(`items[0].count`)를 응답한다. 문법 오류처럼 위치가 없으면 `errors`가 없다.
- [x] 경로 변수 타입 불일치는 `REQ_4002`, 필수 쿼리 파라미터·파트·헤더 누락은 `REQ_4003`과 그 이름으로 응답한다.
- [x] DB 길이 초과는 400 `REQ_4004`와 필드 이름으로 응답하고, `[EXCEPTION]` ERROR 로그를 남긴다.
- [x] DB 중복 키는 409 `REQ_4090`, 그 밖의 무결성 위반은 500 `SRV_5001`이다.
- [x] 락 대기 실패는 503 `SRV_5031`, Redis 연결 실패는 503 `SRV_5032`다.
- [x] 디버그 설정이 켜지면 5xx 응답의 `debug.message`에 가장 안쪽 원인 메시지가 담기고, 꺼지면 `debug`가 없다.
- [x] MySQL·H2의 길이 초과·중복 SQLState와 코드를 분류하고, 컬럼 이름을 필드 이름으로 바꾼다. (`DataIntegrityViolationKindTest`)
- [x] 응답이 바뀐 Swagger 예시(심박 메시지, 후속 질문, 사건 보호자 확인)를 새 코드로 고쳤다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **프론트**: 입력 오류 code가 `REQ_4000`에서 `REQ_4001`~`REQ_4004`로 나뉜다. 프론트가 `REQ_4000`을 분기 조건으로 쓰고 있다면 수정해야 한다. message와 기존 필드는 그대로라 message만 보여 주는 화면은 영향이 없다.
- **업무 오류**: 서비스가 던지는 `BusinessException(ErrorCode.BAD_REQUEST)`는 계속 `REQ_4000`이다.
- **알림**: 일부 500이 400·409·503으로 바뀐다. 503과 `SRV_5001`, 길이 초과는 계속 ERROR라 알림이 그대로 간다. 중복 키 409는 알림 대상에서 빠진다.
- DB 변경 없음.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고

- 조사 결과(검증 누락 필드 약 30개, `@Valid` 누락 9곳)는 #756 두 번째 PR의 작업 목록이다.
