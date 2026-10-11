package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class FcmOutboxService {
    private static final String RETENTION_POLICY_VERSION = "v1";
    private final FcmOutboxRepository outbox;
    private final FcmNotificationRepository notifications;
    private final MemberRepository members;
    private final MemberFcmTokenRepository tokens;
    private final FcmDeliveryProperties properties;
    private final FcmEligibility eligibility;
    private final FcmOutboxDispatcher dispatcher;

    @Transactional
    public void enqueue(Long recipientId, FcmSendDto message) {
        LocalDateTime now = LocalDateTime.now();
        Member recipient = members.findById(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() != Status.ACTIVE) {
            return;
        }
        Long familyId = null;
        if (message.relatedMemberId() != null && !message.relatedMemberId().equals(recipientId)) {
            familyId = eligibility.familyId(message.relatedMemberId());
            if (!eligibility.sameActiveFamily(recipientId, message.relatedMemberId(), familyId)) {
                return;
            }
        }
        String eventId = message.eventId();
        if (eventId == null || eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        if (eventId.length() > 40) {
            throw new IllegalArgumentException("알림 eventId는 40자 이하여야 합니다.");
        }
        Map<String, String> data = message.dataForEnqueue(eventId);
        String dataPayload = FcmDelivery.encodeData(data);
        NotificationType type = message.notificationType();
        DeliveryMode mode = DeliveryMode.PUSH_AND_CENTER;
        if (type != null) {
            mode = type.deliveryMode();
        }
        Long notificationId = null;
        if (mode == DeliveryMode.PUSH_AND_CENTER || mode == DeliveryMode.CENTER_ONLY) {
            FcmNotification center = notifications.findByRecipientMemberIdAndEventId(recipientId, eventId)
                    .orElse(null);
            if (center == null) {
                center = notifications.save(FcmNotification.builder()
                        .recipientMember(recipient).eventId(eventId).type(type)
                        .fcmCategory(category(message))
                        .title(message.centerTitleOrTitle()).body(message.centerBodyOrContent())
                        .image(message.image()).deepLink(data.get("deepLink"))
                        .entityId(message.entityId()).seniorId(message.seniorId())
                        .actorDisplayName(message.actorDisplayName())
                        .seniorDisplayName(message.seniorDisplayName())
                        .remainingLockedCount(message.remainingLockedCount())
                        .decisionId(message.decisionId())
                        .expiresAt(centerExpiresAt(now, type))
                        .retentionPolicyVersion(retentionVersion(type))
                        .pushEligible(mode == DeliveryMode.PUSH_AND_CENTER)
                        .isRead(false).build());
            }
            notificationId = center.getId();
        }
        if (mode == DeliveryMode.CENTER_ONLY) {
            return;
        }
        for (MemberFcmToken token : tokens.findAllByMemberIdAndActiveTrue(recipientId)) {
            FcmOutbox row = FcmOutbox.builder().recipientMember(recipient).memberFcmToken(token)
                    .relatedMemberId(message.relatedMemberId()).familyId(familyId)
                    .title(message.title()).body(message.content()).image(message.image()).scheme(message.scheme())
                    .dataType(data.get("type"))
                    .dataRevision(parseRevision(data.get("revision")))
                    .notificationType(type).dataPayload(dataPayload).notificationId(notificationId)
                    .fcmCategory(category(message)).emergency(message.emergency())
                    .decisionId(message.decisionId()).state(FcmOutbox.State.PENDING)
                    .availableAt(now).expiresAt(now.plus(properties.ttl(message.emergency()))).build();
            outbox.save(row);
            Long id = row.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { dispatcher.submit(id); }
            });
        }
    }

    private LocalDateTime centerExpiresAt(LocalDateTime now, NotificationType type) {
        if (type == null) {
            return null;
        }
        return now.plus(type.retentionClass().duration());
    }

    private String retentionVersion(NotificationType type) {
        if (type == null) {
            return null;
        }
        return RETENTION_POLICY_VERSION;
    }

    private Long parseRevision(String revision) {
        if (revision == null) {
            return null;
        }
        return Long.parseLong(revision);
    }

    private com.widyu.fcm.FcmCategory category(FcmSendDto message) {
        if (message.notificationType() != null) {
            return message.notificationType().fcmCategory();
        }
        return message.fcmCategory();
    }
}
