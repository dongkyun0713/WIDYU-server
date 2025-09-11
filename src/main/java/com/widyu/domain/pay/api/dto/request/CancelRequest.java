package com.widyu.domain.pay.api.dto.request;


public record CancelRequest(
        String cancelReason,
        Integer cancelAmount
) {
}

