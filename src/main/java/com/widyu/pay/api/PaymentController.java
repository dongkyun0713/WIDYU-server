package com.widyu.pay.api;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.pay.api.dto.response.PaymentConfirmResponses;
import com.widyu.pay.application.PaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payment")
public class PaymentController implements PaymentDocs {

    private final PaymentService paymentService;

    @PostMapping
    public ApiResponseTemplate<PaymentConfirmResponse> confirm(
            @RequestBody PaymentConfirmRequest paymentConfirmRequest
    ) {
        PaymentConfirmResponse response = paymentService.confirmPayment(paymentConfirmRequest);

        return ApiResponseTemplate.ok()
                .code("PAY_2001")
                .message("결제 승인 성공")
                .body(response);
    }

    @PostMapping("/{paymentKey}/cancel")
    public ApiResponseTemplate<PaymentConfirmResponse> cancelPayment(
            @PathVariable String paymentKey,
            @RequestBody(required = false) CancelRequest cancelRequest
    ) {
        PaymentConfirmResponse response = paymentService.cancelPayment(paymentKey, cancelRequest);

        return ApiResponseTemplate.ok()
                .code("PAY_2002")
                .message("결제 취소 성공")
                .body(response);
    }

    @GetMapping("/me")
    public ApiResponseTemplate<PaymentConfirmResponses> getPaymentsByUser() {
        return ApiResponseTemplate.ok()
                .code("PAY_2003")
                .message("결제 목록 조회 성공")
                .body(paymentService.getPaymentsByUser());
    }
}
