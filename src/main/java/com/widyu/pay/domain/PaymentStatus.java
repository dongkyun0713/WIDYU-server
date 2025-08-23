package com.widyu.pay.domain;

public enum PaymentStatus {
    READY,      // 결제 준비
    DONE,   // 결제 승인 완료
    CANCELED;   // 결제 취소
}
