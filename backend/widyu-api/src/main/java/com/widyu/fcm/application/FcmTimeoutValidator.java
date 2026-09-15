package com.widyu.fcm.application;

import java.time.Duration;
import org.springframework.stereotype.Component;

/** 서로 다른 prefix에 걸친 제약이라 어느 한쪽 Properties의 생성자에도 넣을 수 없다. */
@Component
class FcmTimeoutValidator {

    private static final Duration LEASE_MARGIN = Duration.ofSeconds(5);

    FcmTimeoutValidator(FcmDeliveryProperties delivery, FirebaseProperties firebase) {
        Duration httpTimeout = firebase.http().totalTimeout();
        if (delivery.lease().compareTo(httpTimeout.plus(LEASE_MARGIN)) <= 0) {
            throw new IllegalArgumentException("fcm.delivery.lease는 firebase.http.total-timeout보다 5초 넘게 커야 합니다.");
        }
        if (delivery.adminTimeout().compareTo(httpTimeout) <= 0) {
            throw new IllegalArgumentException("fcm.delivery.admin-timeout은 firebase.http.total-timeout보다 커야 합니다.");
        }
    }
}
