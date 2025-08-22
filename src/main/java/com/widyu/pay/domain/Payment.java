package com.widyu.pay.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String paymentKey;
    private String orderId;
    private String orderName;
    private int amount;
    private String status;
    private ZonedDateTime requestedAt;
    private ZonedDateTime approvedAt;

    private boolean cultureExpense;

    // 결제 수단별 상세
    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentCard card;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentVirtualAccount virtualAccount;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentTransfer transfer;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentEasyPay easyPay;

    // Payment.java
    public void setCard(PaymentCard card) {
        this.card = card;
        if (card != null) {
            card.setPayment(this);
        }
    }

    public void setEasyPay(PaymentEasyPay easyPay) {
        this.easyPay = easyPay;
        if (easyPay != null) {
            easyPay.setPayment(this);
        }
    }

    public void setTransfer(PaymentTransfer transfer) {
        this.transfer = transfer;
        if (transfer != null) {
            transfer.setPayment(this);
        }
    }

    public void setVirtualAccount(PaymentVirtualAccount virtualAccount) {
        this.virtualAccount = virtualAccount;
        if (virtualAccount != null) {
            virtualAccount.setPayment(this);
        }
    }

}
