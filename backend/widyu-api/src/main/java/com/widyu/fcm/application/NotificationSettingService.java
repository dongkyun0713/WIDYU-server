package com.widyu.fcm.application;

import com.widyu.fcm.MemberNotificationSetting;
import com.widyu.fcm.PushSettingGroup;
import com.widyu.fcm.dto.request.UpdateNotificationSettingRequest;
import com.widyu.fcm.dto.response.NotificationSettingResponse;
import com.widyu.fcm.repository.MemberNotificationSettingRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSettingService {

    private final MemberNotificationSettingRepository notificationSettingRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final MemberRepository memberRepository;
    private final MemberUtil memberUtil;

    public List<NotificationSettingResponse> getNotificationSettings() {
        Member member = memberUtil.getCurrentMember();
        boolean leader = isLeader(member);
        Map<PushSettingGroup, MemberNotificationSetting> settings = notificationSettingRepository
                .findAllByMemberId(member.getId()).stream()
                .collect(Collectors.toMap(MemberNotificationSetting::getCategory, Function.identity()));

        return PushSettingGroup.stream()
                .filter(group -> member.getType() != MemberType.SENIOR || group == PushSettingGroup.GENERAL)
                .map(group -> response(member, group, leader, settings.get(group)))
                .toList();
    }

    @Transactional
    public NotificationSettingResponse updateNotificationSetting(UpdateNotificationSettingRequest request) {
        PushSettingGroup group = parseGroup(request.group());
        if (request.enabled() == null || request.policyRevision() == null || request.policyRevision() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }

        Long memberId = memberUtil.getCurrentMember().getId();
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getNotificationPolicyRevision() != request.policyRevision()) {
            throw new BusinessException(ErrorCode.NOTIFICATION_POLICY_REVISION_CONFLICT);
        }
        if (member.getType() == MemberType.SENIOR && group != PushSettingGroup.GENERAL) {
            throw new BusinessException(ErrorCode.INVALID_FCM_CATEGORY);
        }
        boolean leader = isLeader(member);
        if (leader && group.isMandatoryForLeader() && !request.enabled()) {
            throw new BusinessException(ErrorCode.MANDATORY_NOTIFICATION_PUSH);
        }

        MemberNotificationSetting setting = notificationSettingRepository
                .findByMemberIdAndCategory(memberId, group).orElse(null);
        if (leader && group.isMandatoryForLeader()) {
            return NotificationSettingResponse.of(group, true, true, member.getNotificationPolicyRevision());
        }
        boolean previous = setting == null || setting.isEnabled();
        if (previous != request.enabled()) {
            if (setting == null) {
                notificationSettingRepository.save(MemberNotificationSetting.create(member, group, request.enabled()));
            } else {
                setting.updateEnabled(request.enabled());
            }
            member.incrementNotificationPolicyRevision();
        }
        return response(member, group, leader, request.enabled());
    }

    public boolean isNotificationEnabled(Long memberId, PushSettingGroup group) {
        if (group == null || group == PushSettingGroup.NONE) {
            return true;
        }
        if (group.isMandatoryForLeader() && familyMembershipRepository.findByGuardianId(memberId)
                .map(membership -> membership.isLeader()).orElse(false)) {
            return true;
        }
        return notificationSettingRepository.findByMemberIdAndCategory(memberId, group)
                .map(MemberNotificationSetting::isEnabled).orElse(true);
    }

    private PushSettingGroup parseGroup(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_FCM_CATEGORY);
        }
        try {
            PushSettingGroup group = PushSettingGroup.valueOf(value);
            if (group == PushSettingGroup.NONE) {
                throw new BusinessException(ErrorCode.INVALID_FCM_CATEGORY);
            }
            return group;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_FCM_CATEGORY);
        }
    }

    private boolean isLeader(Member member) {
        if (member.getType() != MemberType.GUARDIAN) {
            return false;
        }
        return familyMembershipRepository.findByGuardianId(member.getId())
                .map(membership -> membership.isLeader()).orElse(false);
    }

    private NotificationSettingResponse response(Member member, PushSettingGroup group, boolean leader,
                                                  MemberNotificationSetting setting) {
        boolean mandatory = leader && group.isMandatoryForLeader();
        boolean enabled = mandatory || setting == null || setting.isEnabled();
        return NotificationSettingResponse.of(group, enabled, mandatory, member.getNotificationPolicyRevision());
    }

    private NotificationSettingResponse response(Member member, PushSettingGroup group, boolean leader,
                                                  boolean requestedEnabled) {
        boolean mandatory = leader && group.isMandatoryForLeader();
        boolean enabled = mandatory || requestedEnabled;
        return NotificationSettingResponse.of(group, enabled, mandatory, member.getNotificationPolicyRevision());
    }
}
