package com.widyu.pay.domain;

import com.widyu.member.domain.Member;
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

    // PG사에서 제공하는 결제 키
    private String paymentKey;

    // 주문 관련 정보
    private String orderId;
    private String orderName;
    private int amount;

    // 결제 상태
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    // 결제 시각 정보
    private ZonedDateTime requestedAt; // 결제 요청 시각
    private ZonedDateTime approvedAt;  // 결제 승인 시각

    // 취소 관련 필드
    private String cancelReason;       // 취소 사유
    private ZonedDateTime canceledAt;  // 취소된 시각

    private boolean cultureExpense;    // 문화비 여부

    // 결제 수단별 상세 매핑 (1:1 관계)
    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentCard card;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentVirtualAccount virtualAccount;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentTransfer transfer;

    @OneToOne(mappedBy = "payment", cascade = CascadeType.ALL)
    private PaymentEasyPay easyPay;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // -------------------- 연관관계 편의 메서드 --------------------
    public void assignCard(PaymentCard card) {
        this.card = card;
        if (card != null) {
            card.assignPayment(this);
        }
    }

    public void assignEasyPay(PaymentEasyPay easyPay) {
        this.easyPay = easyPay;
        if (easyPay != null) {
            easyPay.assignPayment(this);
        }
    }

    public void assignTransfer(PaymentTransfer transfer) {
        this.transfer = transfer;
        if (transfer != null) {
            transfer.assignPayment(this);
        }
    }

    public void assignVirtualAccount(PaymentVirtualAccount virtualAccount) {
        this.virtualAccount = virtualAccount;
        if (virtualAccount != null) {
            virtualAccount.assignPayment(this);
        }
    }

    public void cancel(String reason) {
        this.status = PaymentStatus.CANCELED; // 상태 변경
        this.cancelReason = reason;        // 취소 사유 저장
        this.canceledAt = ZonedDateTime.now(); // 취소 시간 기록
        this.approvedAt = null;            // 더 이상 승인 상태가 아님
    }
}
