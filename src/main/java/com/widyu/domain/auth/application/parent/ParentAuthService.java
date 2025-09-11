package com.widyu.domain.auth.application.parent;

import com.widyu.domain.auth.dto.request.ParentSignInRequest;
import com.widyu.domain.auth.dto.request.ParentSignUpRequest;
import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import com.widyu.domain.member.entity.Member;
import com.widyu.domain.member.entity.MemberType;
import com.widyu.domain.member.entity.ParentProfile;
import com.widyu.domain.member.repository.MemberRepository;
import com.widyu.domain.member.repository.ParentProfileRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParentAuthService {
    private final MemberRepository memberRepository;
    private final ParentProfileRepository parentProfileRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberUtil memberUtil;

    @Transactional
    public void parentSignUpBulk(List<ParentSignUpRequest> requests) {
        Member guardian = memberUtil.getCurrentMember();

        validateRequestsNotEmpty(requests);

        List<Member> members = buildMembersFromRequests(requests);
        saveAllMembers(members);

        List<ParentProfile> profiles = buildProfilesFromRequests(requests, members, guardian);
        saveAllProfiles(profiles);
    }

    @Transactional
    public TokenPairResponse parentSignIn(ParentSignInRequest request) {
        ParentProfile parentProfile = findByInviteCodeAndPhoneNumber(request.inviteCode(), request.phoneNumber());
        return generateTokenPairForMember(parentProfile.getMember());
    }

    private void validateRequestsNotEmpty(List<ParentSignUpRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, ": 요청 리스트가 비어 있습니다.");
        }
    }


    private List<Member> buildMembersFromRequests(List<ParentSignUpRequest> requests) {
        return requests.stream()
                .map(req -> Member.createMember(
                        MemberType.PARENT,
                        req.name(),
                        req.phoneNumber()
                ))
                .toList();
    }

    private void saveAllMembers(List<Member> members) {
        memberRepository.saveAll(members);
    }

    private List<ParentProfile> buildProfilesFromRequests(List<ParentSignUpRequest> requests, List<Member> members, Member guardian) {
        List<ParentProfile> profiles = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            ParentSignUpRequest req = requests.get(i);
            Member member = members.get(i);
            ParentProfile profile = ParentProfile.createParentProfile(
                    member,
                    guardian,
                    req.birthDate(),
                    req.address(),
                    req.detailAddress(),
                    req.inviteCode()
            );
            profiles.add(profile);
        }
        return profiles;
    }

    private void saveAllProfiles(List<ParentProfile> profiles) {
        parentProfileRepository.saveAll(profiles);
    }

    private ParentProfile findByInviteCodeAndPhoneNumber(String inviteCode, String phoneNumber) {
        return parentProfileRepository.findByInviteCodeAndMemberPhoneNumber(inviteCode, phoneNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_CODE_NOT_FOUND, inviteCode));
    }

    private TokenPairResponse generateTokenPairForMember(Member member) {
        // 부모는 초대코드로 로그인하므로 "parent"를 loginType으로 사용
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole(), "parent");
    }
}
