package com.widyu.pay.api.dto;

import com.widyu.pay.domain.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Getter
@NoArgsConstructor
public class PaymentConfirmResponse {

    private String mId;
    private String lastTransactionKey;
    private String paymentKey;
    private String orderId;
    private String orderName;
    private int amount;
    private int taxExemptionAmount;
    private String status;
    private ZonedDateTime requestedAt;
    private ZonedDateTime approvedAt;
    private boolean useEscrow;
    private boolean cultureExpense;

    // 세부 결제 수단
    private Card card;
    private EasyPay easyPay;
    private Transfer transfer;
    private VirtualAccount virtualAccount;

    // ---------------------- inner classes ----------------------
    @Getter
    @NoArgsConstructor
    public static class Card {
        private String issuerCode;
        private String acquirerCode;
        private String number;
        private int installmentPlanMonths;
        private boolean isInterestFree;
        private String approveNo;
        private String cardType;
    }

    @Getter
    @NoArgsConstructor
    public static class EasyPay {
        private String provider;
        private int amount;
    }

    @Getter
    @NoArgsConstructor
    public static class Transfer {
        private String bankCode;
        private String settlementStatus;
    }

    @Getter
    @NoArgsConstructor
    public static class VirtualAccount {
        private String accountNumber;
        private String bankCode;
        private ZonedDateTime dueDate;
        private boolean expired;
    }

    // ---------------------- 매핑 ----------------------
    public Payment toPayment(Long reservationId) {
        Payment payment = Payment.builder()
                .paymentKey(paymentKey)
                .orderId(orderId)
                .orderName(orderName)
                .amount(amount)
                .status(status)
                .requestedAt(requestedAt)
                .approvedAt(approvedAt)
                .reservationId(reservationId)
                .build();

        // 카드 결제
        if (card != null) {
            PaymentCard paymentCard = PaymentCard.builder()
                    .issuerCode(card.getIssuerCode())
                    .acquirerCode(card.getAcquirerCode())
                    .number(card.getNumber())
                    .installmentPlanMonths(
                            card.getInstallmentPlanMonths() != 0 ? card.getInstallmentPlanMonths() : 0
                    )
                    .isInterestFree(card.isInterestFree())
                    .approveNo(card.getApproveNo())
                    .cardType(card.getCardType())
                    .build();
            payment.setCard(paymentCard);
        }

        // 간편결제
        if (easyPay != null) {
            PaymentEasyPay paymentEasyPay = PaymentEasyPay.builder()
                    .provider(easyPay.getProvider())
                    .amount(easyPay.getAmount())
                    .build();
            payment.setEasyPay(paymentEasyPay);
        }

        // 계좌이체
        if (transfer != null) {
            PaymentTransfer paymentTransfer = PaymentTransfer.builder()
                    .bankCode(transfer.getBankCode())
                    .settlementStatus(transfer.getSettlementStatus())
                    .build();
            payment.setTransfer(paymentTransfer);
        }

        // 가상계좌
        if (virtualAccount != null) {
            PaymentVirtualAccount paymentVirtualAccount = PaymentVirtualAccount.builder()
                    .accountNumber(virtualAccount.getAccountNumber())
                    .bankCode(virtualAccount.getBankCode())
                    .dueDate(virtualAccount.getDueDate())
                    .expired(virtualAccount.isExpired())
                    .build();
            payment.setVirtualAccount(paymentVirtualAccount);
        }

        return payment;
    }
}
