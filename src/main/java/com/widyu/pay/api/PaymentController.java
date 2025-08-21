package com.widyu.pay.api;

import com.widyu.pay.api.dto.PaymentConfirmRequest;
import com.widyu.pay.api.dto.PaymentConfirmResponse;
import com.widyu.pay.application.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/{reservationId}/payment")
    public ResponseEntity<PaymentConfirmResponse> confirm(@RequestBody PaymentConfirmRequest paymentConfirmRequest, @PathVariable("reservationId") Long reservationId) {
        final PaymentConfirmResponse paymentConfirmResponse =  paymentService.confirmPayment(paymentConfirmRequest, reservationId);
        return ResponseEntity.ok(paymentConfirmResponse);
    }
}
