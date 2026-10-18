package com.widyu.auth.application.guardian;

import com.widyu.auth.application.guardian.unlink.SocialUnlinkService;
import com.widyu.auth.dto.request.MemberWithdrawRequest;
import com.widyu.auth.event.MemberWithdrawnEvent;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.fcm.application.degraded.EmergencyRecipientSnapshotService;
import com.widyu.goal.medicineschedule.application.MedicationProofDeletionService;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.deletion.S3ObjectDeletionTaskService;
import com.widyu.global.util.MemberUtil;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
    private final S3ObjectDeletionTaskService s3ObjectDeletionTaskService;
    private final SocialUnlinkService socialUnlinkService;
    private final ApplicationEventPublisher eventPublisher;
    private final EmergencyRecipientSnapshotService emergencyRecipientSnapshots;

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
        s3ObjectDeletionTaskService.schedule(member.getId(), Stream.ofNullable(profileImage).toList());

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
        // 탈퇴한 보호자가 DB 장애 중 저하 경로 알림을 계속 받지 않게 커밋 뒤 스냅샷을 정리한다(LLD-0087 5.2).
        if (membership.isLeader()) {
            Long familyId = membership.getFamily().getId();
            // 가족이 해체되면 가족 ID로 시니어를 다시 찾을 수 없으므로 해체 전에 잡아 둔다.
            List<Long> seniorMemberIds = seniorProfileRepository.findSeniorMemberIds(familyId);
            seniorProfileRepository.clearFamilyByFamilyId(familyId);
            familyMembershipRepository.deleteByGuardianId(guardianId);
            familyRepository.deleteById(familyId);
            emergencyRecipientSnapshots.evictAfterCommit(familyId, seniorMemberIds);
            return;
        }
        Long familyId = membership.getFamily().getId();
        familyMembershipRepository.deleteByGuardianId(guardianId);
        familyRepository.incrementOrderRevision(familyId);
        emergencyRecipientSnapshots.refreshFamilyAfterCommit(familyId);
    }
}
