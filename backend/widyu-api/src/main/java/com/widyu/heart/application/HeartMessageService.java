package com.widyu.heart.application;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.heart.dto.request.HeartMessageRequest;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeartMessageService {

    private final MemberUtil memberUtil;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final FcmService fcmService;

    @Transactional
    public void sendHeartMessage(HeartMessageRequest request) {
        Member sender = memberUtil.getCurrentMember();
        Member receiver = memberRepository.findById(request.receiverId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        validateFamilyConnection(sender, receiver);

        String title = sender.getName() + "님이 메시지를 보냈어요.";

        FcmSendDto fcmSendDto = FcmSendDto.builder()
                .title(title)
                .content(request.message())
                .fcmCategory(FcmCategory.HEART_MESSAGE)
                .scheme("")
                .image(sender.getProfileImage())
                .relatedMemberId(sender.getId())
                .build();

        fcmService.sendMessageToUser(receiver.getId(), fcmSendDto);
        log.info("하트 메시지 발송 요청 접수: senderId={}, receiverId={}", sender.getId(), receiver.getId());
    }

    private void validateFamilyConnection(Member sender, Member receiver) {
        if (sender.getType() == MemberType.GUARDIAN && receiver.getType() == MemberType.SENIOR) {
            Long seniorProfileId = receiver.getSeniorProfile().getId();
            if (!familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(sender.getId(), seniorProfileId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        } else if (sender.getType() == MemberType.SENIOR && receiver.getType() == MemberType.GUARDIAN) {
            Long seniorProfileId = sender.getSeniorProfile().getId();
            if (!familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(receiver.getId(), seniorProfileId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
