package com.widyu.domain.pay.api.dto.mapper;

import com.widyu.domain.member.domain.Member;
import com.widyu.domain.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.domain.pay.domain.Payment;
import com.widyu.domain.pay.domain.PaymentCard;
import com.widyu.domain.pay.domain.PaymentEasyPay;
import com.widyu.domain.pay.domain.PaymentStatus;
import com.widyu.domain.pay.domain.PaymentTransfer;
import com.widyu.domain.pay.domain.PaymentVirtualAccount;

public class PaymentMapper {

    public static Payment toEntity(PaymentConfirmResponse dto, Member member) {
        Payment payment = Payment.builder()
                .member(member)
                .paymentKey(dto.getPaymentKey())
                .orderId(dto.getOrderId())
                .orderName(dto.getOrderName())
                .amount(dto.getAmount())
                .status(dto.getStatus() != null ? dto.getStatus() : PaymentStatus.DONE)
                .requestedAt(dto.getRequestedAt())
                .approvedAt(dto.getApprovedAt())
                .cultureExpense(dto.isCultureExpense())
                .build();

        // 카드 결제
        if (dto.getCard() != null) {
            PaymentCard card = PaymentCard.builder()
                    .issuerCode(dto.getCard().getIssuerCode())
                    .acquirerCode(dto.getCard().getAcquirerCode())
                    .number(dto.getCard().getNumber())
                    .installmentPlanMonths(dto.getCard().getInstallmentPlanMonths())
                    .isInterestFree(dto.getCard().isInterestFree())
                    .approveNo(dto.getCard().getApproveNo())
                    .cardType(dto.getCard().getCardType())
                    .build();
            payment.assignCard(card);
        }

        // 간편결제
        if (dto.getEasyPay() != null) {
            PaymentEasyPay easyPay = PaymentEasyPay.builder()
                    .provider(dto.getEasyPay().getProvider())
                    .amount(dto.getEasyPay().getAmount())
                    .build();
            payment.assignEasyPay(easyPay);
        }

        // 계좌이체
        if (dto.getTransfer() != null) {
            PaymentTransfer transfer = PaymentTransfer.builder()
                    .bankCode(dto.getTransfer().getBankCode())
                    .settlementStatus(dto.getTransfer().getSettlementStatus())
                    .build();
            payment.assignTransfer(transfer);
        }

        // 가상계좌
        if (dto.getVirtualAccount() != null) {
            PaymentVirtualAccount va = PaymentVirtualAccount.builder()
                    .accountNumber(dto.getVirtualAccount().getAccountNumber())
                    .bankCode(dto.getVirtualAccount().getBankCode())
                    .dueDate(dto.getVirtualAccount().getDueDate())
                    .expired(dto.getVirtualAccount().isExpired())
                    .build();
            payment.assignVirtualAccount(va);
        }

        return payment;
    }
}
