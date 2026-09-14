package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import java.time.LocalDateTime;
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
        for (MemberFcmToken token : tokens.findAllByMemberIdAndActiveTrue(recipientId)) {
            FcmOutbox row = FcmOutbox.builder().recipientMember(token.getMember()).memberFcmToken(token)
                    .relatedMemberId(message.relatedMemberId()).familyId(familyId)
                    .title(message.title()).body(message.content()).image(message.image()).scheme(message.scheme())
                    .fcmCategory(message.fcmCategory()).emergency(message.emergency()).state(FcmOutbox.State.PENDING)
                    .availableAt(now).expiresAt(now.plus(properties.ttl(message.emergency()))).build();
            outbox.save(row);
            Long id = row.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { dispatcher.submit(id); }
            });
        }
    }
}
