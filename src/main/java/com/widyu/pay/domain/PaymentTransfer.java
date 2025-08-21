package com.widyu.pay.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String bankCode;
    private String settlementStatus; // 정산 상태

    @OneToOne
    @JoinColumn(name = "payment_id")
    private Payment payment;
}
