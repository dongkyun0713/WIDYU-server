package com.widyu.admin.dto.response;

import com.widyu.global.entity.Status;
import com.widyu.member.MemberType;
import com.widyu.pay.PaymentStatus;
import java.util.List;

public record AdminSearchResponse(
        List<MemberHit> members,
        List<PaymentHit> payments
) {
    public static AdminSearchResponse of(List<MemberHit> members, List<PaymentHit> payments) {
        return new AdminSearchResponse(members, payments);
    }

    public record MemberHit(
            Long id,
            String name,
            String phoneNumber,
            MemberType type,
            Status status
    ) {}

    public record PaymentHit(
            Long id,
            String orderId,
            String memberName,
            int amount,
            PaymentStatus status
    ) {}
}
