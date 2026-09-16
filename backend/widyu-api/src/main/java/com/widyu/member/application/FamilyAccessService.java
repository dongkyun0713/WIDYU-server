package com.widyu.member.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.FamilyMembership;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FamilyAccessService {

    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;

    public List<Long> getFamilyMemberIds(Member member) {
        List<Long> memberIds = new ArrayList<>();
        memberIds.add(member.getId());

        Long familyId = findFamilyId(member);
        if (familyId == null) {
            return memberIds;
        }

        seniorProfileRepository.findAllByFamilyIdWithMember(familyId)
                .forEach(seniorProfile -> memberIds.add(seniorProfile.getMember().getId()));
        familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId)
                .forEach(membership -> memberIds.add(membership.getGuardian().getId()));
        return memberIds.stream().distinct().toList();
    }

    public void verifySameFamily(Member member, Member targetMember) {
        if (getFamilyMemberIds(member).contains(targetMember.getId())) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "같은 가족의 앨범만 접근할 수 있습니다.");
    }

    public void verifyFamilyAccess(Long guardianId, Long targetMemberId) {
        Member targetMember = findSeniorMember(targetMemberId);
        verifyFamilyRelationship(guardianId, targetMember);
    }

    public void verifyActiveFamilyAccess(Long guardianId, Long targetMemberId) {
        Member guardian = memberRepository.findById(guardianId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN,
                        "활성 회원만 실시간 정보를 받을 수 있습니다."));
        Member targetMember = findSeniorMember(targetMemberId);

        verifyActiveMember(guardian);
        verifyActiveMember(targetMember);
        verifyFamilyRelationship(guardianId, targetMember);
    }

    public void verifyLeaderAccess(Long guardianId, Long targetMemberId) {
        verifyFamilyAccess(guardianId, targetMemberId);

        Member targetMember = memberRepository.findById(targetMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));

        boolean isLeader = familyMembershipRepository.existsByGuardianIdAndSeniorProfileIdAndIsLeaderTrue(
                guardianId,
                targetMember.getSeniorProfile().getId()
        );
        if (!isLeader) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "방장만 수정할 수 있습니다.");
        }
    }

    public void verifyGuardianLeader(Long guardianId) {
        boolean isLeader = familyMembershipRepository.findByGuardianId(guardianId)
                .map(FamilyMembership::isLeader)
                .orElse(false);
        if (!isLeader) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "방장만 수행할 수 있습니다.");
        }
    }

    private Long findFamilyId(Member member) {
        return familyMembershipRepository.findFamilyIdByGuardianId(member.getId())
                .or(() -> seniorProfileRepository.findFamilyIdByMemberId(member.getId()))
                .orElse(null);
    }

    private Member findSeniorMember(Long targetMemberId) {
        Member targetMember = memberRepository.findById(targetMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));

        if (targetMember.getType() != MemberType.SENIOR) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "시니어의 리소스만 접근할 수 있습니다.");
        }

        if (targetMember.getSeniorProfile() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "시니어 프로필이 없습니다.");
        }
        return targetMember;
    }

    private void verifyActiveMember(Member member) {
        if (member.getStatus() != Status.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "활성 회원만 실시간 정보를 받을 수 있습니다.");
        }
    }

    private void verifyFamilyRelationship(Long guardianId, Member targetMember) {
        boolean isFamily = familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(
                guardianId,
                targetMember.getSeniorProfile().getId()
        );

        if (!isFamily) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "가족으로 연결된 시니어만 접근할 수 있습니다.");
        }
    }
}
