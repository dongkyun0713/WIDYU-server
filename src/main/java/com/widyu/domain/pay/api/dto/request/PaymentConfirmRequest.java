package com.widyu.domain.pay.api.dto.request;

public record PaymentConfirmRequest(
        String orderId,
        int amount,
        String paymentKey
) {

}
