package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.DeliveryMode;
import com.widyu.global.entity.Status;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FcmEligibility {
    private final MemberRepository members;
    private final FamilyMembershipRepository memberships;
    private final SeniorProfileRepository seniors;
    private final NotificationSettingService settings;

    public Long familyId(Long memberId) {
        return seniors.findFamilyIdByMemberId(memberId)
                .orElseGet(() -> memberships.findFamilyIdByGuardianId(memberId).orElse(null));
    }

    public boolean active(Long memberId) {
        return members.findById(memberId).filter(member -> member.getStatus() == Status.ACTIVE).isPresent();
    }

    public boolean sameActiveFamily(Long recipientId, Long relatedMemberId, Long familyId) {
        return active(relatedMemberId) && familyId != null
                && familyId.equals(familyId(recipientId))
                && familyId.equals(familyId(relatedMemberId));
    }

    public boolean eligible(FcmOutbox outbox) {
        Long recipientId = outbox.getRecipientMember().getId();
        if (!active(recipientId) || !outbox.getMemberFcmToken().isActive()
                || !recipientId.equals(outbox.getMemberFcmToken().getMember().getId())) {
            return false;
        }
        if (outbox.getNotificationType() == null
                || outbox.getNotificationType().deliveryMode() != DeliveryMode.DATA_ONLY) {
            if (!settings.isNotificationEnabled(recipientId, outbox.getFcmCategory())) {
                return false;
            }
        }
        Long related = outbox.getRelatedMemberId();
        if (related == null || related.equals(recipientId)) {
            return true;
        }
        return sameActiveFamily(recipientId, related, outbox.getFamilyId());
    }
}
