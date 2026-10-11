package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
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
    private final FcmOutboxRepository outbox;
    private final MemberFcmTokenRepository tokens;
    private final FcmDeliveryProperties properties;
    private final FcmEligibility eligibility;
    private final FcmOutboxDispatcher dispatcher;

    @Transactional
    public void enqueue(Long recipientId, FcmSendDto message) {
        LocalDateTime now = LocalDateTime.now();
        Long familyId = null;
        if (message.relatedMemberId() != null && !message.relatedMemberId().equals(recipientId)) {
            familyId = eligibility.familyId(message.relatedMemberId());
        }
        String eventId = message.eventId();
        if (message.notificationType() != null && (eventId == null || eventId.isBlank())) {
            eventId = UUID.randomUUID().toString();
        }
        if (message.notificationType() != null && eventId.length() > 40) {
            throw new IllegalArgumentException("알림 eventId는 40자 이하여야 합니다.");
        }
        Map<String, String> data = message.dataForEnqueue(eventId);
        String dataPayload = FcmDelivery.encodeData(data);
        for (MemberFcmToken token : tokens.findAllByMemberIdAndActiveTrue(recipientId)) {
            FcmOutbox row = FcmOutbox.builder().recipientMember(token.getMember()).memberFcmToken(token)
                    .relatedMemberId(message.relatedMemberId()).familyId(familyId)
                    .title(message.title()).body(message.content()).image(message.image()).scheme(message.scheme())
                    .dataType(data.get("type"))
                    .dataRevision(parseRevision(data.get("revision")))
                    .notificationType(message.notificationType()).dataPayload(dataPayload)
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
