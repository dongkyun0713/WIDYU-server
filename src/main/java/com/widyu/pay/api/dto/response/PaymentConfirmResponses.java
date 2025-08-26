package com.widyu.pay.api.dto.response;

import com.widyu.pay.domain.Payment;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentConfirmResponses {

    private List<PaymentConfirmResponse> payments;

    public static PaymentConfirmResponses from(List<Payment> payments) {
        return PaymentConfirmResponses.builder()
                .payments(payments.stream()
                        .map(PaymentConfirmResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
