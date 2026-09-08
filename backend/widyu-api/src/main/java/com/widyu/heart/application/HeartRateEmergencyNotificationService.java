package com.widyu.heart.application;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import io.micrometer.core.annotation.Timed;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartRateEmergencyNotificationService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;

    @EventListener
    @Timed("heart.emergency.notification")
    public void handleHeartRateEmergency(HeartRateEmergencyEvent event) {
        Member seniorMember = memberRepository.findById(event.memberId()).orElse(null);
        if (seniorMember == null) {
            return;
        }

        Long familyId = seniorProfileRepository.findFamilyIdByMemberId(event.memberId()).orElse(null);
        if (familyId == null) {
            return;
        }

        List<FamilyMembership> memberships = familyMembershipRepository
                .findAllByFamilyIdWithGuardian(familyId);
        FcmSendDto notification = FcmSendDto.builder()
                .title(seniorMember.getName() + "님의 심박수 이상이 감지되었습니다")
                .content("현재 상태를 확인해주세요.")
                .fcmCategory(FcmCategory.HEART_MESSAGE)
                .scheme("")
                .image(seniorMember.getProfileImage())
                .build();

        for (FamilyMembership membership : memberships) {
            sendNotification(membership.getGuardian().getId(), notification);
        }
    }

    private void sendNotification(Long guardianId, FcmSendDto notification) {
        try {
            fcmService.sendMessageToUser(guardianId, notification);
        } catch (RuntimeException exception) {
            log.error("심박 긴급 알림 발송 실패: guardianId={}", guardianId, exception);
        }
    }
}
