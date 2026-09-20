package com.widyu.fcm.application;

import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FcmOutboxTransactions {
    private final FcmOutboxRepository outbox;
    private final FcmNotificationRepository notifications;
    private final FcmEligibility eligibility;
    private final FcmDeliveryProperties properties;
    private final MemberFcmTokenRepository tokens;
    private final DecisionRecordRepository decisions;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FcmDelivery claim(Long id) {
        FcmOutbox row = outbox.lockById(id).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (row == null || !row.claim(now, properties.lease(), properties.maxRetries())) {
            return null;
        }
        if (!eligibility.eligible(row)) {
            row.cancel();
            return null;
        }
        return FcmDelivery.from(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean preflight(FcmDelivery delivery) {
        FcmOutbox row = outbox.lockById(delivery.id()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (row == null || !row.owns(delivery.fence(), now)) {
            return false;
        }
        if (!row.getExpiresAt().isAfter(now)) {
            row.expire();
            return false;
        }
        if (!eligibility.eligible(row)
                || !delivery.token().equals(row.getMemberFcmToken().getToken())) {
            row.cancel();
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(FcmDelivery delivery, FcmTransport.Result result) {
        FcmOutbox row = outbox.lockById(delivery.id()).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (row == null || !row.owns(delivery.fence(), now)) {
            return;
        }
        if (result.success()) {
            notifications.save(FcmNotification.builder().recipientMember(row.getRecipientMember())
                    .memberFcmToken(row.getMemberFcmToken()).title(row.getTitle()).body(row.getBody())
                    .image(row.getImage()).fcmCategory(row.getFcmCategory()).isRead(false).build());
            row.sent();
            markDecisionDelivered(row);
            return;
        }
        if (result.permanentToken()) {
            tokens.deactivateIfOwned(row.getMemberFcmToken().getId(), row.getRecipientMember().getId(), delivery.token(), now);
        }
        Duration delay = Duration.ofSeconds(Math.min(3600L, 30L << Math.min(row.getAttempts() - 1, 7)));
        if (result.retryAfter() != null && result.retryAfter().compareTo(delay) > 0) {
            delay = result.retryAfter();
        }
        row.failed(result.retryable(), delay, now, properties.maxRetries());
    }

    /**
     * 「알림」은 FCM 전송 성공으로 잰다(ADR-0035 결정 3). 「구글이 받았다」는 「가족 단말에 떴다」의 근사이고
     * 그 이상은 재지 못한다. 실패·만료는 도달하지 않은 것으로 남긴다.
     *
     * <p>판정에서 나오지 않은 알림은 가리킬 행이 없으므로 조회조차 하지 않는다.
     */
    private void markDecisionDelivered(FcmOutbox row) {
        if (row.getDecisionId() == null) {
            return;
        }
        decisions.findByDecisionId(row.getDecisionId())
                .ifPresent(record -> record.markDelivered("fcm-" + row.getId(), System.currentTimeMillis()));
    }
}
