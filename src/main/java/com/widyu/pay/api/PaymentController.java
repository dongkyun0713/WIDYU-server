package com.widyu.pay.api;

import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.pay.application.PaymentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentConfirmResponse> confirm(@RequestBody PaymentConfirmRequest paymentConfirmRequest) {
        PaymentConfirmResponse paymentConfirmResponse = paymentService.confirmPayment(paymentConfirmRequest);

        return ResponseEntity.ok(paymentConfirmResponse);
    }

    // 결제 취소
    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<PaymentConfirmResponse> cancelPayment(
            @PathVariable String paymentKey,
            @RequestBody(required = false) CancelRequest cancelRequest
    ) {
        PaymentConfirmResponse response = paymentService.cancelPayment(paymentKey, cancelRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<PaymentConfirmResponse>> getPaymentsByUser(
            @PathVariable Long userId
    ) {
        List<PaymentConfirmResponse> payments = paymentService.getPaymentsByUser(userId);
        return ResponseEntity.ok(payments);
    }

    // 유저별 결제 리스트 조회
}
