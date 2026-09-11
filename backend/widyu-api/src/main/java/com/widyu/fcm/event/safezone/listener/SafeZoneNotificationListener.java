package com.widyu.fcm.event.safezone.listener;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@org.springframework.context.annotation.Profile("!pilot") // 실증 제외: 위치 이벤트 자동 알림
@Component
@RequiredArgsConstructor
public class SafeZoneNotificationListener {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;

    @EventListener
    public void handleSafeZoneExit(SafeZoneExitEvent event) {
        Member seniorMember = memberRepository.findById(event.seniorMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        SeniorProfile seniorProfile = seniorMember.getSeniorProfile();
        if (seniorProfile == null) {
            log.debug("안전구역 이탈 알림 스킵: 시니어 프로필이 없습니다. memberId={}", seniorMember.getId());
            return;
        }

        if (seniorProfile.getFamily() == null) {
            log.debug("안전구역 이탈 알림 스킵: 시니어가 가족에 속해 있지 않습니다. memberId={}", seniorMember.getId());
            return;
        }

        List<FamilyMembership> memberships = familyMembershipRepository
                .findAllByFamilyIdWithGuardian(seniorProfile.getFamily().getId());
        if (memberships.isEmpty()) {
            log.debug("안전구역 이탈 알림 스킵: 연결된 보호자가 없습니다. familyId={}", seniorProfile.getFamily().getId());
            return;
        }

        String title = seniorMember.getName() + "님이 안전구역을 벗어났어요";
        String content = "현재 위치를 확인해주세요.";

        for (FamilyMembership membership : memberships) {
            FcmSendDto dto = FcmSendDto.builder()
                    .title(title)
                    .content(content)
                    .fcmCategory(FcmCategory.SAFE_ZONE)
                    .scheme("")
                    .image(seniorMember.getProfileImage())
                    .build();

            fcmService.sendMessageToUser(membership.getGuardian().getId(), dto);
            log.info("안전구역 이탈 알림 전송 - seniorMemberId: {}, guardianMemberId: {}",
                    seniorMember.getId(), membership.getGuardian().getId());
        }
    }
}
