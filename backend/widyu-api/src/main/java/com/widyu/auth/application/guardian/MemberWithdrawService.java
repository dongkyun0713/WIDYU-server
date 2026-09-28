package com.widyu.auth.application.guardian;

import com.widyu.auth.application.guardian.unlink.SocialUnlinkService;
import com.widyu.auth.dto.request.MemberWithdrawRequest;
import com.widyu.auth.event.MemberWithdrawnEvent;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.goal.medicineschedule.application.MedicationProofDeletionService;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final FamilyRepository familyRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MemberUtil memberUtil;
    private final MedicationProofDeletionService medicationProofDeletionService;
    private final SocialUnlinkService socialUnlinkService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void withdrawMember(MemberWithdrawRequest request) {
        Member member = memberUtil.getCurrentMember();

        log.info("회원 탈퇴 시작: memberId={}", member.getId());

        // 외부 side-effect 전 방장 위임 여부 사전 검증
        validateLeaderCanWithdraw(member.getId());

        medicationProofDeletionService.deleteAllByMember(member);

        // 1. 리프레시 토큰 삭제
        refreshTokenRepository.deleteById(member.getId());

        // 2. 소셜 연동 해제 작업 저장 (마스킹 전 oauthId·토큰으로, 외부 호출은 커밋 뒤)
        socialUnlinkService.schedule(member);

        // 3. FamilyMembership 처리 (leader 정책 적용)
        handleFamilyMembershipWithdrawal(member.getId());

        // 4. 개인정보 마스킹 (GDPR 준수)
        String profileImage = member.getProfileImage();
        member.maskPersonalInfo();

        // 5. 로컬 계정 삭제
        member.withdraw();

        // 6. 회원 데이터 저장
        memberRepository.save(member);

        // 7. 프로필 사진 삭제 작업 저장 (커밋 뒤 삭제, 실패 시 재시도, 롤백되면 작업도 사라진다)
        medicationProofDeletionService.scheduleImageDeletion(member.getId(), profileImage);

        // 8. 커밋 뒤 소셜 연동 해제·Redis 위치 삭제
        eventPublisher.publishEvent(new MemberWithdrawnEvent(member.getId()));

        log.info("회원 탈퇴 완료: memberId={}", member.getId());
    }

    private void validateLeaderCanWithdraw(Long guardianId) {
        Optional<FamilyMembership> membershipOpt = familyMembershipRepository.findByGuardianId(guardianId);
        if (membershipOpt.isEmpty() || !membershipOpt.get().isLeader()) {
            return;
        }
        long memberCount = familyMembershipRepository.countByFamilyId(membershipOpt.get().getFamily().getId());
        if (memberCount > 1) {
            throw new BusinessException(ErrorCode.FAMILY_LEADER_MUST_DELEGATE_BEFORE_WITHDRAW);
        }
    }

    private void handleFamilyMembershipWithdrawal(Long guardianId) {
        Optional<FamilyMembership> membershipOpt = familyMembershipRepository.findByGuardianId(guardianId);
        if (membershipOpt.isEmpty()) {
            return;
        }
        FamilyMembership membership = membershipOpt.get();
        if (membership.isLeader()) {
            Long familyId = membership.getFamily().getId();
            seniorProfileRepository.clearFamilyByFamilyId(familyId);
            familyMembershipRepository.deleteByGuardianId(guardianId);
            familyRepository.deleteById(familyId);
            return;
        }
        familyMembershipRepository.deleteByGuardianId(guardianId);
    }
}
