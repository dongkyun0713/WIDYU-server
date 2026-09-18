# LLD-0003: 포인트·결제 플로우

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (포인트 서술은 2026-09-17 ADR-0029·LLD-0039로 갱신) |
| Issue | - |
| 관련 ADR | ADR-0003 (DB 설계 — ENUM, FK 전략), ADR-0029 (결제-포인트 분리) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-05 |

## 1. 목적 / 배경

시니어가 Toss Payments로 결제 패키지를 주문·승인·취소하는 결제 플로우다. 주문 생성 → 결제 승인의 2-step flow를 따르며 중복 결제 방지, 부분 취소 멱등성 등 결제 무결성을 보장해야 한다.

**결제는 포인트를 충전·환수하지 않는다(ADR-0029, 2026-09-17).** 포인트는 시니어 가입·복약 인증·걷기 목표·건강 일정 방문으로만 적립되고 앨범 잠금해제로만 차감된다. 아래 5-2·5-3의 포인트 단계는 삭제됐으며, 이 문서의 나머지 서술은 결제 상태 전이·멱등 처리의 오라클로 계속 유효하다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (pay 패키지), widyu-domain (Payment, PaymentOrder, PaymentCancel)
- 결제 패키지 목록 조회 (`PointChargePackage` enum — 금액만 정의)
- 주문 생성 (orderId 발급, 15분 만료)
- 결제 승인 (Toss Payments confirm API)
- 결제 취소 (Toss Payments cancel API, 부분 취소 멱등)
- 결제 내역 조회

### Out of scope
- 포인트 적립·차감 전반 (결제와 무관, ADR-0029)
- 앨범 잠금해제 포인트 차감 (AlbumService 담당, 50pt 차감)
- 시니어 가입 시 100pt 지급 (회원가입 서비스 담당)
- 행정 취소·환불 (관리자 도구 담당)
- 포인트 만료 정책 (현재 미구현)

## 3. 인터페이스 / API

```http
GET /api/v1/payment/packages
```
구매 가능한 결제 패키지 목록. `pointAmount`는 호환용 필드로 항상 0이다.

```json
{
  "isSuccess": true,
  "result": [
    { "packageId": "POINT_10000", "orderName": "포인트 충전 10,000원", "amount": 10000, "pointAmount": 0 },
    { "packageId": "POINT_30000", "orderName": "포인트 충전 30,000원", "amount": 30000, "pointAmount": 0 },
    { "packageId": "POINT_50000", "orderName": "포인트 충전 50,000원", "amount": 50000, "pointAmount": 0 }
  ]
}
```

```http
POST /api/v1/payment/orders
```
주문 생성. **시니어 전용.**

Request:
```json
{ "packageId": "POINT_10000" }
```

Response:
```json
{
  "isSuccess": true,
  "result": {
    "orderId": "order_abc123def456gh78",
    "orderName": "포인트 충전 10,000원",
    "amount": 10000,
    "pointAmount": 0,
    "status": "CREATED",
    "expiresAt": "2026-07-05T15:00:00+09:00"
  }
}
```

```http
POST /api/v1/payment
```
결제 승인. 클라이언트가 Toss Payments 위젯에서 받은 `paymentKey`를 전달.

Request:
```json
{
  "orderId": "order_abc123def456gh78",
  "paymentKey": "tgen_20260705...",
  "amount": 10000
}
```

Response: 결제 완료 정보 (`PaymentConfirmResponse`)
```json
{
  "isSuccess": true,
  "result": {
    "paymentKey": "tgen_20260705...",
    "orderId": "order_abc123def456gh78",
    "orderName": "포인트 충전 10,000원",
    "amount": 10000,
    "status": "DONE",
    "approvedAt": "2026-07-05T14:45:00+09:00"
  }
}
```

```http
POST /api/v1/payment/{paymentKey}/cancel
```
결제 취소 (부분 취소 지원).

Request:
```json
{
  "cancelReason": "구매 실수",
  "cancelAmount": 10000,
  "idempotencyKey": "cancel-20260725-0001"
}
```
- `cancelReason` 미전달 시 "사용자 요청"으로 처리
- `cancelAmount` 미전달 시 전액 취소
- 부분 취소는 `idempotencyKey` 필수. 같은 키 재전송은 기존 결과를 반환한다.

```http
GET /api/v1/payment/me
```
본인 결제 내역 목록. 결제 없으면 PAYMENT_NOT_FOUND.

## 4. 데이터 모델

### 엔티티 (widyu-domain)

**PaymentOrder** (`payment_order` 테이블):
```
payment_order
├── id (PK, IDENTITY)
├── order_id (String, unique)  ← "order_" + UUID 16자
├── member_id (FK, ManyToOne)
├── order_name (String)
├── package_id (String)        ← PointChargePackage.id
├── amount (int)               ← 결제 금액 (원)
├── point_amount (int)         ← 항상 0 (호환용, 폐기 예정 — ADR-0029)
├── status (ENUM STRING)       ← CREATED / PAID / EXPIRED / CANCELED
├── expires_at (ZonedDateTime) ← 생성 시각 + 15분
└── createdAt, updatedAt
```

**Payment** (`payment` 테이블):
```
payment
├── id (PK, IDENTITY)
├── payment_key (String, unique)  ← Toss Payments 발급
├── order_id (String)             ← Toss 주문 id
├── member_id (FK)
├── order_name (String)
├── amount (int)
├── canceled_amount (int)          ← 누적 취소 금액
├── canceled_point_amount (int)    ← 항상 0 (호환용, 폐기 예정 — ADR-0029)
├── status (PaymentStatus ENUM)    ← READY / DONE / PARTIAL_CANCELED / CANCELED
├── requested_at, approved_at (ZonedDateTime)
├── cancel_reason, canceled_at
├── culture_expense (boolean)
├── payment_order_id (OneToOne, unique)
└── card / virtualAccount / transfer / easyPay (결제수단별 1:1 상세)
```

**PaymentCancel** (`payment_cancel` 테이블):
```
payment_cancel
├── id (PK, IDENTITY)
├── payment_id (FK, ManyToOne)
├── cancel_amount (int)
├── cancel_point_amount (int)       ← 항상 0 (호환용, 폐기 예정 — ADR-0029)
├── cancel_reason (String)
├── requested_by_member_id (Long)   ← memberId
└── canceled_at (ZonedDateTime)
```

**PaymentOrderStatus** enum: `CREATED, PAID, EXPIRED, CANCELED`

**PaymentStatus** enum: `READY, DONE, PARTIAL_CANCELED, CANCELED`

**PointChargePackage** enum (코드 정의, DB 저장 없음, 금액만):
```java
enum PointChargePackage {
    POINT_10000("POINT_10000", "포인트 충전 10,000원", 10000),
    POINT_30000("POINT_30000", "포인트 충전 30,000원", 30000),
    POINT_50000("POINT_50000", "포인트 충전 50,000원", 50000)
}
```

`PointHistory`·`SeniorProfile.points`는 결제와 무관하다(ADR-0029). 포인트 잔액 낙관적 락은 §8 참고.

### DTO (widyu-api)

- `PaymentOrderCreateRequest`: `packageId`
- `PaymentApproveRequest`: `orderId`, `paymentKey`, `amount`
- `CancelRequest`: `cancelReason`, `cancelAmount`
- `PaymentConfirmResponse`: Toss Payments 응답 + Payment 엔티티 매핑
- `PaymentOrderResponse`: 주문 생성 결과
- `PaymentPackageResponse`: 패키지 목록 항목

## 5. 처리 흐름

### 5-1. 주문 생성 (`createOrder`)

```
1. getCurrentMember() → SENIOR + seniorProfile 존재 검증 (FORBIDDEN)
2. PointChargePackage.fromId(packageId) 조회 (없으면 BAD_REQUEST)
3. generateOrderId(): "order_" + UUID 16자, 중복 시 최대 5회 재시도 (INTERNAL_SERVER_ERROR)
4. PaymentOrder.create(orderId, member, ..., expiresAt=now+15분) 저장
5. PaymentOrderResponse.from(paymentOrder) 반환
```

트랜잭션: `@Transactional`

### 5-2. 결제 승인 (`confirmPayment`)

```
1. getCurrentMember() → SENIOR 검증
2. paymentOrderRepository.findByOrderId(orderId) (없으면 PAYMENT_NOT_FOUND)
3. 주문 소유권 검증 (FORBIDDEN)
4. 주문 상태 검증:
   - isExpired() → markExpired() + BAD_REQUEST "만료된 주문"
   - !isCreated() → BAD_REQUEST "이미 처리된 주문"
5. 멱등성 체크 (중복 요청 처리):
   a. findByOrderId(orderId) 존재 → 소유권 검증 후 기존 Payment 반환
   b. findByPaymentKey(paymentKey) 존재 → 소유권 + 금액/orderId 일치 검증 후 반환
6. PaymentGatewayConfirmRequest 생성 → paymentClient.confirmPayment() (Feign)
7. 응답 검증: paymentKey, orderId, amount 일치 (PAYMENT_FAILED)
8. PaymentMapper.toEntity(rawResponse, member, paymentOrder) → paymentRepository.save()
9. paymentOrder.markPaid()
10. (삭제됨 — ADR-0029) 포인트 적립 없음
11. DataIntegrityViolationException 캐치 → 중복 저장 처리 (race condition 안전)
12. PaymentConfirmResponse.from(payment) 반환
```

트랜잭션: `@Transactional`

### 5-3. 결제 취소 (`cancelPayment`)

```
1. paymentRepository.findByPaymentKey(paymentKey) (없으면 PAYMENT_NOT_FOUND)
2. getCurrentMember() → 소유권 검증 (FORBIDDEN)
3. isCanceled() → 이미 취소된 경우 기존 응답 반환 (멱등)
4. sanitizeCancelRequest(): cancelReason 기본값, cancelAmount 전액 처리, 범위 검증
5. (삭제됨 — ADR-0029) 환수 포인트 계산 없음
6. (삭제됨 — ADR-0029) 포인트 잔액 검증·예약 차감 없음
7. paymentClient.cancelPayment(paymentKey, cancelRequest) (Feign)
8. 취소 응답 검증: paymentKey 일치 (PAYMENT_FAILED)
9. PaymentCancel.createPending(..., cancelPointAmount = 0, ...) → payment.addCancellation(paymentCancel)
10. payment.cancel(cancelAmount, 0, reason, canceledAt)
    └─ payment.canceledAmount += cancelAmount
    └─ 전액 취소 시 status = CANCELED
11. (삭제됨 — ADR-0029) 포인트 환수 없음. 취소 해제·중단 시 포인트 반환도 없음
12. 전액 취소 + PaymentOrder 존재 시: paymentOrder.markCanceled()
```

현재 선점·PG 호출·결과 반영 트랜잭션 분리는 ADR-0016·LLD-0022, 포인트 분리 후 각 경로의 동작은 LLD-0039 §5를 따른다.

트랜잭션: `@Transactional`

### 5-4. PaymentOrder 상태 전이

```
[CREATED] ──결제 승인──► [PAID]
    │
    ├──만료(15분)──► [EXPIRED]     (validatePaymentOrderState에서 markExpired)
    │
    └──(직접 취소)──► [CANCELED]   (cancelPayment 후 전액 취소 시)
```

Payment 상태: `DONE` → (부분 취소) → `PARTIAL_CANCELED` → (전액 취소) → `CANCELED`

### 5-5. Toss Payments Feign 클라이언트

```java
// PaymentClient (Feign)
POST {spring.payment.base-url}/confirm  → confirmPayment()
POST {spring.payment.base-url}/{paymentKey}/cancel  → cancelPayment()
```
- 인증: `Authorization: Basic {Base64(secretKey:)}` 헤더
- `application-pay.yml`에 secretKey 관리

## 6. 예외 / 에러 처리

| 상황 | 에러 | 메시지 |
|------|------|--------|
| GUARDIAN이 결제 시도 | FORBIDDEN | "시니어 회원만 결제할 수 있습니다." |
| 주문 없음 | PAYMENT_NOT_FOUND | "주문 정보를 찾을 수 없습니다." |
| 주문 만료 | BAD_REQUEST | "만료된 주문입니다." |
| 이미 처리된 주문 | BAD_REQUEST | "이미 처리된 주문입니다." |
| 본인 주문·결제 아님 | FORBIDDEN | "본인 주문만 결제할 수 있습니다." |
| PG 응답 불일치 | PAYMENT_FAILED | "PG 응답과 요청 정보가 일치하지 않습니다." |
| 취소 금액 > 남은 금액 | BAD_REQUEST | "남은 결제 금액보다 크게 취소할 수 없습니다." |
| orderId 5회 생성 실패 | INTERNAL_SERVER_ERROR | "주문 ID 생성에 실패했습니다." |
| 결제 없음 (내역 조회) | PAYMENT_NOT_FOUND | - |

## 7. 인수조건 (Acceptance Criteria)

- [x] 시니어만 결제 패키지를 주문할 수 있다 (보호자 시도 시 403)
- [x] 주문 생성 후 15분이 지나면 결제 승인이 EXPIRED 에러를 반환한다
- [x] 동일 paymentKey로 중복 결제 승인 요청 시 기존 Payment를 그대로 반환한다 (멱등)
- [x] 결제 승인·취소 후 SeniorProfile.points와 PointHistory가 변하지 않는다 (ADR-0029, LLD-0039)
- [x] 전액 취소 시 PaymentOrder.status가 CANCELED로 변경된다
- [x] 동일 멱등 키의 부분 취소 재전송은 PG 호출을 반복하지 않는다
- [x] Swagger에 주문·승인·취소·내역 응답이 반영된다
- [x] `./gradlew :backend:widyu-api:test`가 통과한다

## 8. 영향 범위 / 마이그레이션

- 부분 취소 멱등성을 위해 `payment_cancel.idempotency_key`와 `(payment_id, idempotency_key)` 고유 제약을 추가한다.
- `PointChargePackage` enum 변경 시 기존 `payment_order.package_id` 데이터 영향 없음 (문자열로 저장)
- 새 패키지 추가: enum 값 추가만으로 반영 (DB 마이그레이션 불필요)

### 포인트 잔액 낙관적 락 도입 (2026-07, Issue #390)

- `SeniorProfile`에 `@Version private Long version` 컬럼 추가로 포인트 잔액 lost update·과차감 방지
- `@RetryOnPointConflict`(최대 5회, 50ms→2배·최대 200ms 백오프)로 충돌 시 **새 트랜잭션에서 재시도**
- **재시도 경계 = 최외곽 트랜잭션.** 낙관적 락 충돌은 커밋(flush) 시점에 발생하므로, 재시도는 자신이 최외곽 트랜잭션 경계가 되는 진입점에만 유효하다. 적용 대상:
  - `SeniorProfileService.addPointsToMember`/`deductPointsFromMember` — 스케줄러 등 트랜잭션 밖에서 직접 호출될 때
  - `WalkService.updateSteps`, `AdminPointGrantService.grant`
- **재시도 미적용 경로(충돌 시 409 응답 → 클라이언트 재시도):**
  - `AlbumUnlockService.unlockAlbum`: 트랜잭션 내 동기 FCM 이벤트 발행 → 서버 재시도 시 알림 중복 위험
  - 결제 경로는 포인트를 증감하지 않으므로 낙관적 락 충돌 대상이 아니다 (ADR-0029)
- 재시도 소진·미적용 경로의 충돌은 `ObjectOptimisticLockingFailureException` → GlobalExceptionHandler가 409(`POINT_CONCURRENT_UPDATE`) 응답
- **운영 DB 마이그레이션 필요** (`ddl-auto: update`는 기존 행에 NOT NULL 컬럼 backfill을 보장하지 않음):
  ```sql
  ALTER TABLE senior_profile ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
  ```

### 부분 취소 멱등 키 도입 (2026-07-25)

```sql
ALTER TABLE payment_cancel ADD COLUMN idempotency_key VARCHAR(100) NULL;
ALTER TABLE payment_cancel
    ADD CONSTRAINT uk_payment_cancel_payment_idempotency_key
    UNIQUE (payment_id, idempotency_key);
```

## 9. 미결정 사항 (Open Questions)

Toss의 취소 거래 키를 대사·보정 식별자로 별도 보관할지 검토한다.

## 10. 참고

- `PaymentService.java`: 전체 결제 플로우 구현
- `PaymentOrder.java` (widyu-domain): CREATED/PAID/EXPIRED/CANCELED 상태 전이
- `Payment.java` (widyu-domain): DONE/PARTIAL_CANCELED/CANCELED, 취소 금액 누적
- `PaymentCancel.java` (widyu-domain): 취소 이력
- `PaymentClient.java`: Toss Payments Feign 클라이언트
- LLD-0039: 결제와 포인트 적립 분리
- Toss Payments API 문서: https://docs.tosspayments.com/reference
