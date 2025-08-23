package com.widyu.pay.api;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Payment", description = "결제 API")
public interface PaymentDocs {

    @Operation(
            summary = "결제 승인",
            description = "결제를 승인하고 결제 정보를 반환합니다."
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
                                        "paymentKey": "string",
                                        "orderId": "order_123456",
                                        "status": "DONE"
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
                            schema = @Schema(implementation = PaymentConfirmRequest.class),
                            examples = @ExampleObject(
                                    name = "결제 승인 요청 예시",
                                    value = """
                                            {
                                              "orderId": "order_123456",
                                              "amount": 10000,
                                              "paymentKey": "pay_abc123"
                                            }
                                            """
                            )
                    )
            ) final PaymentConfirmRequest paymentConfirmRequest
    );

    @Operation(
            summary = "결제 취소",
            description = "주어진 paymentKey를 기준으로 결제를 취소합니다."
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
                                        "paymentKey": "string",
                                        "orderId": "order_123456",
                                        "status": "CANCELED"
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
                                              "cancelReason": "사용자 요청",
                                              "cancelAmount": 10000
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
                                      "data": [
                                        {
                                          "paymentKey": "pay_abc123",
                                          "orderId": "order_123456",
                                          "status": "DONE"
                                        },
                                        {
                                          "paymentKey": "pay_xyz789",
                                          "orderId": "order_789123",
                                          "status": "CANCELED"
                                        }
                                      ]
                                    }
                                    """
                    )
            )
    )
    ApiResponseTemplate<List<PaymentConfirmResponse>> getPaymentsByUser();
}
