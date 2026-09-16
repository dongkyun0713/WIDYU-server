package com.widyu.fcm.application;

import com.widyu.fcm.FcmOutbox;
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

    public boolean eligible(FcmOutbox outbox) {
        Long recipientId = outbox.getRecipientMember().getId();
        if (!active(recipientId) || !outbox.getMemberFcmToken().isActive()
                || !recipientId.equals(outbox.getMemberFcmToken().getMember().getId())
                || !settings.isNotificationEnabled(recipientId, outbox.getFcmCategory())) {
            return false;
        }
        Long related = outbox.getRelatedMemberId();
        if (related == null || related.equals(recipientId)) {
            return true;
        }
        Long family = outbox.getFamilyId();
        return active(related) && family != null && family.equals(familyId(recipientId))
                && family.equals(familyId(related));
    }
}
