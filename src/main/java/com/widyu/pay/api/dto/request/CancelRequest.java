package com.widyu.pay.api.dto.request;


public record CancelRequest(
        String cancelReason,
        Integer cancelAmount
) {
}

