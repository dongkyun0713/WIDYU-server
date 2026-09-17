package com.widyu.pay.dto.response;

import com.widyu.pay.PointChargePackage;

public record PaymentPackageResponse(
        String packageId,
        String orderName,
        int amount,
        int pointAmount
) {
    public static PaymentPackageResponse from(PointChargePackage paymentPackage) {
        return new PaymentPackageResponse(
                paymentPackage.getId(),
                paymentPackage.getOrderName(),
                paymentPackage.getAmount(),
                0
        );
    }
}
