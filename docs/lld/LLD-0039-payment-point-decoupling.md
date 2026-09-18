# LLD-0039: 결제와 포인트 적립 분리

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #624 |
| 관련 ADR | ADR-0029 |
| 작성자 | Claude Code |
| 작성일 | 2026-09-17 |

## 1. 목적 / 배경

포인트를 활동 보상 전용으로 확정하면서 결제 승인 시 포인트 적립, 취소 선점 시 환수 예약, 취소 중단 시 반환 로직을 결제 흐름에서 걷어낸다. Toss 결제는 향후 응급 알람 월 구독 상품으로 전환할 예정이지만 구독 상품·0원 결제는 이번 범위 밖이다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (`pay`, `global/retry`), widyu-domain (`PointChargePackage`)
- `PaymentTransactionService`에서 승인 적립·취소 예약·반환·환수 계산 제거, `SeniorProfileService` 의존 제거
- `PointChargePackage.pointAmount` 제거, 주문·취소 생성 시 포인트 컬럼에 0 기록
- 결제 통합 테스트의 포인트 검증을 "변동 없음"으로 변경
- LLD-0003·ADR-0012·architecture/payment.md·ERD·`RetryOnPointConflict` javadoc·backend 지침 갱신

### Out of scope
- 응급 알람 구독 상품, 0원 결제, 구독 게이트
- `payment_order.point_amount`·`payment.canceled_point_amount`·`payment_cancel.cancel_point_amount` 컬럼 삭제 (§8 후속)
- 과거 포인트 충전 결제의 환수 (ADR-0029: 환수하지 않는다)
- Toss 승인·취소·복구 스케줄러·Feign·멱등 처리 로직 변경

## 3. 인터페이스 / API

HTTP 경로·요청 계약은 유지한다. 응답의 포인트 필드는 존재하되 항상 0이다.

```http
GET  /api/v1/payment/packages
POST /api/v1/payment/orders
POST /api/v1/payment
POST /api/v1/payment/{paymentKey}/cancel
```

```json
{ "packageId": "POINT_10000", "orderName": "포인트 충전 10,000원", "amount": 10000, "pointAmount": 0 }
```

- 주문 생성 응답 `pointAmount`: 0
- 승인·취소 응답 `canceledPointAmount`, 취소 이력 `cancelPointAmount`: 0
- 보호자가 주문·승인 요청 시 `FORBIDDEN` 메시지: "시니어 회원만 결제할 수 있습니다."

## 4. 데이터 모델

신규 테이블·컬럼 없음. DDL 없음.

- `PointChargePackage`(widyu-domain, 코드 enum): `pointAmount` 필드·getter 제거. `id`, `orderName`, `amount`만 유지.
- `PaymentOrder.pointAmount`, `Payment.canceledPointAmount`, `PaymentCancel.cancelPointAmount`: 필드·컬럼 유지, 새 행은 항상 0. NOT NULL 컬럼이라 엔티티에서 제거하면 기존 DB에서 INSERT가 실패하므로 삭제는 §8의 후속 DDL로 분리한다.
- `PaymentPackageResponse.pointAmount`(widyu-api DTO): 필드 유지, 0.

## 5. 처리 흐름

### 5-1. 승인 완료 (`PaymentTransactionService.completeApproval`)
1. 주문 행 잠금 → 기존 `Payment` 있으면 반환 (멱등)
2. APPROVING 상태·paymentKey·PG 멱등 키 일치 검증
3. PG 응답 검증 → `Payment` 저장 → `PaymentOrder.markPaid()`
4. 포인트 적립 없음. `PointHistory` 미생성.

### 5-2. 취소 선점 (`claimCancellation`)
1. 결제 행 잠금 → 멱등 키·PENDING 취소 검사 (기존과 동일)
2. `PaymentCancel.createPending(..., cancelPointAmount = 0, ...)` 저장
3. 포인트 차감·예약 없음.

### 5-3. 취소 확정·해제·중단·대사 보류
- `completeCancellation`: `payment.cancel(cancelAmount, 0, ...)`로 누적 취소액만 반영.
- `releaseCancellation`: PENDING 취소 레코드 제거. 포인트 반환 없음.
- `stopCancellationRecovery`: ABORTED 종결. 포인트 반환 없음.
- `holdCancellationForReconciliation`: 복구 중단·수동 대사. 포인트 변동 없음.

트랜잭션 경계·PG 호출 분리는 ADR-0016 그대로다. `PaymentTransactionService`는 `SeniorProfileService`를 주입받지 않는다.

## 6. 예외 / 에러 처리

| 상황 | 에러 | 메시지 |
| --- | --- | --- |
| 보호자가 주문·승인 시도 | FORBIDDEN | "시니어 회원만 결제할 수 있습니다." |

"결제 취소에 필요한 포인트가 부족합니다." 경로는 결제에서 사라진다(`SeniorProfileService.deductPointsFromMember`의 메시지는 다른 호출자를 위해 유지).

## 7. 인수조건 (Acceptance Criteria)

- [x] 결제 승인 완료 후 `SeniorProfile.points`가 승인 전과 같고 `PointHistory`가 비어 있다.
- [x] 부분 취소 선점·확정·해제·중단·대사 보류 어느 경로에서도 `PointHistory`가 생기지 않고 잔액이 변하지 않는다.
- [x] 새로 생성되는 `PaymentOrder.pointAmount`·`PaymentCancel.cancelPointAmount`·`Payment.canceledPointAmount`는 0이다.
- [x] `GET /packages`·주문 생성·승인 응답의 `pointAmount`/`canceledPointAmount`가 0이다(필드 존재).
- [x] `PaymentTransactionService` 생성자가 `SeniorProfileService`를 받지 않는다.
- [x] 보호자 주문·승인 시 403 메시지가 "시니어 회원만 결제할 수 있습니다."이다.
- [x] Swagger 예시의 `pointAmount`가 0이다.
- [x] `./gradlew :backend:widyu-api:test`가 통과한다.
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- DDL 없음. 클라이언트 요청·응답 필드 변경 없음(값만 0).
- **사용자 영향 (배포 전 확인 필수)**: `GET /api/v1/payment/packages`·`POST /orders`·`POST /payment` 진입점은 그대로 열려 있고 패키지 이름도 "포인트 충전 10,000원"이다. 배포 후 시니어가 실결제하면 돈은 나가고 포인트는 0이다. 배포 전에 클라이언트가 결제 진입점(패키지 목록·주문 화면)을 노출하지 않음을 확인한다. 노출 중이면 응급 알람 구독 상품 전환(별도 이슈) 전까지 클라이언트에서 결제 메뉴를 숨기거나, 서버에서 `getPackages`를 빈 목록으로 막는 후속 PR을 먼저 낸다.
- **배포 전 확인**: 예약 포인트가 걸린 진행 중 취소가 없어야 한다. 있으면 배포 전에 수동으로 반환한다.
  ```sql
  SELECT COUNT(*) FROM payment_cancel WHERE status = 'PENDING' AND cancel_point_amount > 0;  -- 0이어야 함
  ```
- **후속 DDL** (클라이언트가 `pointAmount`·`canceledPointAmount`·`cancelPointAmount`를 읽지 않게 된 뒤 실행):
  ```sql
  ALTER TABLE payment_order DROP COLUMN point_amount;
  ALTER TABLE payment DROP COLUMN canceled_point_amount;
  ALTER TABLE payment_cancel DROP COLUMN cancel_point_amount;
  ```
  이때 엔티티 필드·DTO 필드도 함께 제거한다.
- 과거 포인트 충전 결제를 배포 후 취소하면 환불만 되고 포인트는 환수하지 않는다(ADR-0029).
- `RetryOnPointConflict` javadoc의 결제 예외 문단, LLD-0003 §8의 결제 재시도 미적용 항목이 사라진다.

## 9. 미결정 사항 (Open Questions)

- 없음. 구독 상품·0원 결제는 별도 이슈로 다룬다.

## 10. 참고

- ADR-0029, ADR-0012, ADR-0016
- LLD-0003 (포인트·결제 플로우, 이 문서로 포인트 서술 갱신)
- `PaymentTransactionService.java`, `PaymentService.java`, `PointChargePackage.java`
