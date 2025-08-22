package com.widyu.pay.application;

import com.widyu.pay.api.dto.PaymentConfirmRequest;
import com.widyu.pay.api.dto.PaymentConfirmResponse;
import com.widyu.pay.config.PaymentClient;
import com.widyu.pay.domain.Payment;
import com.widyu.pay.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentClient paymentClient;
    private final PaymentRepository paymentRepository;

    public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest paymentConfirmRequest) {
        PaymentConfirmResponse paymentConfirmResponse = paymentClient.confirmPayment(paymentConfirmRequest);
        Payment payment = paymentConfirmResponse.toPayment();

        paymentRepository.save(payment);
        return paymentConfirmResponse;
    }
}
