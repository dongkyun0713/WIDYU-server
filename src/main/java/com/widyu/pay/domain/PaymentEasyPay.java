package com.widyu.pay.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentEasyPay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider; // 예: NAVER_PAY, KAKAO_PAY
    private int amount;

    @OneToOne
    @JoinColumn(name = "payment_id")
    private Payment payment;
}
