package com.widyu.pay.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String issuerCode;
    private String acquirerCode;
    private String number; // 마스킹 처리된 카드번호
    @Column(nullable = false, columnDefinition = "int default 0")
    private int installmentPlanMonths;
    private boolean isInterestFree;
    private String approveNo;
    private String cardType;

    @OneToOne
    @JoinColumn(name = "payment_id")
    private Payment payment;

    public void assignPayment(Payment payment) {
        this.payment = payment;
    }
}
