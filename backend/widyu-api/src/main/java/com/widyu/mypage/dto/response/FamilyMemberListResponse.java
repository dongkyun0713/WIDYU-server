package com.widyu.mypage.dto.response;

import com.widyu.member.FamilyMembership;
import com.widyu.member.SeniorProfile;
import java.util.ArrayList;
import java.util.List;

public record FamilyMemberListResponse(
        boolean isCurrentUserLeader,
        long familyOrderRevision,
        List<FamilyMemberItem> members
) {
    public record FamilyMemberItem(
            Long memberId,
            String name,
            String profileImage,
            boolean isLeader,
            boolean isCurrent,
            boolean isSenior
    ) {}

    public static FamilyMemberListResponse of(
            List<FamilyMembership> memberships,
            List<SeniorProfile> seniors,
            Long currentGuardianId,
            long familyOrderRevision
    ) {
        boolean isCurrentUserLeader = memberships.stream()
                .anyMatch(m -> m.getGuardian().getId().equals(currentGuardianId) && m.isLeader());

        List<FamilyMemberItem> seniorItems = seniors.stream()
                .map(sp -> new FamilyMemberItem(
                        sp.getMember().getId(),
                        sp.getMember().getName(),
                        sp.getMember().getProfileImage(),
                        false,
                        false,
                        true
                ))
                .toList();

        List<FamilyMemberItem> guardianItems = memberships.stream()
                .map(m -> new FamilyMemberItem(
                        m.getGuardian().getId(),
                        m.getGuardian().getName(),
                        m.getGuardian().getProfileImage(),
                        m.isLeader(),
                        m.getGuardian().getId().equals(currentGuardianId),
                        false
                ))
                .toList();

        List<FamilyMemberItem> allMembers = new ArrayList<>();
        allMembers.addAll(seniorItems);
        allMembers.addAll(guardianItems);

        return new FamilyMemberListResponse(isCurrentUserLeader, familyOrderRevision, allMembers);
    }
}
