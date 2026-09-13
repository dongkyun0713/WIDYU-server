package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.dto.response.AdminMemberDetailFullResponse;
import com.widyu.admin.dto.response.AdminMemberDetailFullResponse.FamilyInfo;
import com.widyu.admin.dto.response.AdminMemberDetailFullResponse.RecentAlbum;
import com.widyu.admin.dto.response.AdminMemberDetailFullResponse.RecentPayment;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.pay.Payment;
import com.widyu.pay.repository.PaymentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private final MemberRepository memberRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final AlbumRepository albumRepository;
    private final PaymentRepository paymentRepository;
    private final HeartRateEmergencyRepository heartRateEmergencyRepository;
    private final AdminAuditLogService adminAuditLogService;

    @Transactional(readOnly = true)
    public AdminMemberDetailFullResponse getMemberDetail(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        FamilyInfo familyInfo = buildFamilyInfo(member);
        long activeFcmTokens = memberFcmTokenRepository.countByMemberIdAndActiveTrue(memberId);

        List<RecentAlbum> recentAlbums = albumRepository
                .findTop3ByMemberIdAndStatusNotOrderByIdDesc(memberId, Status.DELETED)
                .stream()
                .map(RecentAlbum::from)
                .toList();

        List<RecentPayment> recentPayments = paymentRepository
                .findTop3ByMemberIdOrderByIdDesc(memberId)
                .stream()
                .map(p -> new RecentPayment(p.getId(), p.getOrderName(), p.getAmount(), p.getStatus(), p.getApprovedAt()))
                .toList();

        long emergencyCount = heartRateEmergencyRepository.countByMemberId(memberId);

        return AdminMemberDetailFullResponse.of(
                member.getId(),
                member.getName(),
                member.getPhoneNumber(),
                member.getType(),
                member.getRole(),
                member.getStatus(),
                member.getCreatedAt(),
                familyInfo,
                activeFcmTokens,
                recentAlbums,
                recentPayments,
                emergencyCount
        );
    }

    @Transactional
    public Status changeStatus(Long memberId, Status newStatus) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        Status before = member.getStatus();
        if (newStatus == Status.ACTIVE) {
            member.reactivate();
        } else if (newStatus == Status.INACTIVE) {
            member.withdraw();
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN, "ACTIVE 또는 INACTIVE만 허용됩니다.");
        }
        adminAuditLogService.log(
                AdminAction.MEMBER_STATUS_CHANGE, "MEMBER", memberId,
                member.getName() + " " + before + " → " + member.getStatus()
        );
        return member.getStatus();
    }

    private FamilyInfo buildFamilyInfo(Member member) {
        if (member.getType() == MemberType.SENIOR) {
            return seniorProfileRepository.findByMemberId(member.getId())
                    .map(sp -> new FamilyInfo(
                            sp.getFamily().getFamilyCode(),
                            sp.getInviteCode(),
                            sp.getAddress(),
                            sp.getPoints(),
                            null, null, null, null
                    ))
                    .orElse(null);
        } else {
            return familyMembershipRepository.findByGuardianId(member.getId())
                    .map(fm -> new FamilyInfo(
                            fm.getFamily().getFamilyCode(),
                            null, null, null,
                            fm.isLeader(),
                            fm.isRepresentative(),
                            fm.getNickname(),
                            fm.getConnectedAt()
                    ))
                    .orElse(null);
        }
    }
}
