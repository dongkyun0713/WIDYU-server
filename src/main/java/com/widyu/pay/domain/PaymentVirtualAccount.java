package com.widyu.pay.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentVirtualAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountNumber;
    private String bankCode;
    private ZonedDateTime dueDate;
    private boolean expired;

    @OneToOne
    @JoinColumn(name = "payment_id")
    private Payment payment;

    public void assignPayment(Payment payment) {
        this.payment = payment;
    }
}
