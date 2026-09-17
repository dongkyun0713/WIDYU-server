package com.widyu.pay.controller.docs;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.pay.dto.request.CancelRequest;
import com.widyu.pay.dto.request.PaymentApproveRequest;
import com.widyu.pay.dto.request.PaymentOrderCreateRequest;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.dto.response.PaymentConfirmResponses;
import com.widyu.pay.dto.response.PaymentPackageResponse;
import com.widyu.pay.dto.response.PaymentOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment", description = "결제 API")
public interface PaymentDocs {

    @Operation(
            summary = "결제 패키지 목록 조회",
            description = "서버가 제공하는 포인트 충전 패키지 목록을 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "패키지 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "응답 예시",
                            value = """
                                    {
                                      "code": "PAY_1999",
                                      "message": "결제 패키지 조회 성공",
                                      "data": [
                                        {
                                          "packageId": "POINT_10000",
                                          "orderName": "포인트 10,000P 충전",
                                          "amount": 10000,
                                          "pointAmount": 0
                                        },
                                        {
                                          "packageId": "POINT_30000",
                                          "orderName": "포인트 30,000P 충전",
                                          "amount": 30000,
                                          "pointAmount": 0
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<java.util.List<PaymentPackageResponse>> getPackages();

    @Operation(
            summary = "결제 주문 생성",
            description = "결제 승인 전에 서버에 선행 주문을 생성하고 주문 ID, 금액, 만료 시각을 반환합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "주문 생성 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "응답 예시",
                            value = """
                                    {
                                      "code": "PAY_2000",
                                      "message": "주문 생성 성공",
                                      "data": {
                                        "orderId": "order_123456",
                                        "packageId": "POINT_10000",
                                        "orderName": "포인트 10,000P 충전",
                                        "amount": 10000,
                                        "pointAmount": 0,
                                        "status": "CREATED",
                                        "expiresAt": "2026-07-06T12:10:00+09:00"
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<PaymentOrderResponse> createOrder(
            @RequestBody(
                    required = true,
                    description = "주문 생성 요청 정보",
                    content = @Content(
                            schema = @Schema(implementation = PaymentOrderCreateRequest.class),
                            examples = @ExampleObject(
                                    name = "주문 생성 요청 예시",
                                    value = """
                                            {
                                              "packageId": "POINT_10000"
                                            }
                                            """
                            )
                    )
            ) final PaymentOrderCreateRequest paymentOrderCreateRequest
    );

    @Operation(
            summary = "결제 승인",
            description = "미리 생성한 주문을 기준으로 결제를 승인하고 결제 정보를 반환합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "결제 승인 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답 예시",
                            value = """
                                    {
                                      "code": "PAY_2001",
                                      "message": "결제 승인 성공",
                                      "data": {
                                        "mId": null,
                                        "lastTransactionKey": null,
                                        "paymentKey": "pay_abc123",
                                        "orderId": "order_123456",
                                        "orderName": "포인트 충전 5,000P",
                                        "amount": 5000,
                                        "taxExemptionAmount": 0,
                                        "status": "DONE",
                                        "requestedAt": "2026-07-06T12:00:00+09:00",
                                        "approvedAt": "2026-07-06T12:00:05+09:00",
                                        "canceledAmount": 0,
                                        "canceledPointAmount": 0,
                                        "remainingAmount": 5000,
                                        "useEscrow": false,
                                        "cultureExpense": false,
                                        "cancellations": [],
                                        "card": {
                                          "issuerCode": "3K",
                                          "acquirerCode": "3K",
                                          "number": "43301234****000*",
                                          "installmentPlanMonths": 0,
                                          "interestFree": false,
                                          "approveNo": "00000000",
                                          "cardType": "신용"
                                        },
                                        "easyPay": null,
                                        "transfer": null,
                                        "virtualAccount": null
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<PaymentConfirmResponse> confirm(
            @RequestBody(
                    required = true,
                    description = "결제 승인 요청 정보",
                    content = @Content(
                            schema = @Schema(implementation = PaymentApproveRequest.class),
                            examples = @ExampleObject(
                                    name = "결제 승인 요청 예시",
                                    value = """
                                            {
                                              "orderId": "order_123456",
                                              "paymentKey": "pay_abc123"
                                            }
                                            """
                            )
                    )
            ) final PaymentApproveRequest paymentApproveRequest
    );

    @Operation(
            summary = "결제 취소",
            description = "주어진 paymentKey를 기준으로 결제를 취소합니다. 부분 취소에는 재전송 방지를 위한 idempotencyKey가 필요합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "결제 취소 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "성공 응답 예시",
                            value = """
                                    {
                                      "code": "PAY_2002",
                                      "message": "결제 취소 성공",
                                      "data": {
                                        "mId": null,
                                        "lastTransactionKey": null,
                                        "paymentKey": "pay_abc123",
                                        "orderId": "order_123456",
                                        "orderName": "포인트 충전 5,000P",
                                        "amount": 5000,
                                        "taxExemptionAmount": 0,
                                        "status": "CANCELED",
                                        "requestedAt": "2026-07-06T12:00:00+09:00",
                                        "approvedAt": "2026-07-06T12:00:05+09:00",
                                        "canceledAmount": 5000,
                                        "canceledPointAmount": 0,
                                        "remainingAmount": 0,
                                        "useEscrow": false,
                                        "cultureExpense": false,
                                        "cancellations": [
                                          {
                                            "cancelAmount": 5000,
                                            "cancelPointAmount": 0,
                                            "cancelReason": "사용자 요청",
                                            "requestedByMemberId": 1,
                                            "canceledAt": "2026-07-06T13:00:00+09:00"
                                          }
                                        ],
                                        "card": null,
                                        "easyPay": null,
                                        "transfer": null,
                                        "virtualAccount": null
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<PaymentConfirmResponse> cancelPayment(
            @Parameter(
                    name = "paymentKey",
                    description = "취소할 결제의 고유 키",
                    required = true,
                    example = "pay_abc123"
            ) final String paymentKey,

            @RequestBody(
                    required = false,
                    description = "취소 요청 사유 등",
                    content = @Content(
                            schema = @Schema(implementation = CancelRequest.class),
                            examples = @ExampleObject(
                                    name = "취소 요청 예시",
                                    value = """
                                            {
                                              "cancelReason": "부분 취소 요청",
                                              "cancelAmount": 3000,
                                              "idempotencyKey": "cancel-20260725-0001"
                                            }
                                            """
                            )
                    )
            ) final CancelRequest cancelRequest
    );

    @Operation(
            summary = "내 결제 목록 조회",
            description = "로그인된 사용자 기준으로 본인의 결제 내역을 조회합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "결제 목록 조회 성공",
            content = @Content(
                    schema = @Schema(implementation = ApiResponseTemplate.class),
                    examples = @ExampleObject(
                            name = "응답 예시",
                            value = """
                                    {
                                      "code": "PAY_2003",
                                      "message": "결제 목록 조회 성공",
                                      "data": {
                                        "payments": [
                                          {
                                            "mId": null,
                                            "lastTransactionKey": null,
                                            "paymentKey": "pay_abc123",
                                            "orderId": "order_123456",
                                            "orderName": "포인트 충전 5,000P",
                                            "amount": 5000,
                                            "taxExemptionAmount": 0,
                                            "status": "DONE",
                                            "requestedAt": "2026-07-06T12:00:00+09:00",
                                            "approvedAt": "2026-07-06T12:00:05+09:00",
                                            "canceledAmount": 0,
                                            "canceledPointAmount": 0,
                                            "remainingAmount": 5000,
                                            "useEscrow": false,
                                            "cultureExpense": false,
                                            "cancellations": [],
                                            "card": null,
                                            "easyPay": null,
                                            "transfer": null,
                                            "virtualAccount": null
                                          },
                                          {
                                            "mId": null,
                                            "lastTransactionKey": null,
                                            "paymentKey": "pay_xyz789",
                                            "orderId": "order_789123",
                                            "orderName": "포인트 충전 10,000P",
                                            "amount": 10000,
                                            "taxExemptionAmount": 0,
                                            "status": "PARTIAL_CANCELED",
                                            "requestedAt": "2026-07-05T09:30:00+09:00",
                                            "approvedAt": "2026-07-05T09:30:04+09:00",
                                            "canceledAmount": 3000,
                                            "canceledPointAmount": 0,
                                            "remainingAmount": 7000,
                                            "useEscrow": false,
                                            "cultureExpense": false,
                                            "cancellations": [
                                              {
                                                "cancelAmount": 3000,
                                                "cancelPointAmount": 0,
                                                "cancelReason": "부분 취소 요청",
                                                "requestedByMemberId": 1,
                                                "canceledAt": "2026-07-05T15:00:00+09:00"
                                              }
                                            ],
                                            "card": null,
                                            "easyPay": null,
                                            "transfer": null,
                                            "virtualAccount": null
                                          }
                                        ]
                                      }
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<PaymentConfirmResponses> getPaymentsByUser();
}
