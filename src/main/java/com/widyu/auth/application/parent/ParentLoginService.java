package com.widyu.auth.application.parent;

import com.widyu.auth.dto.request.ParentSignInRequest;
import com.widyu.auth.dto.request.ParentSignUpRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.ParentProfile;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.ParentProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ParentLoginService {

    private final MemberRepository memberRepository;
    private final ParentProfileRepository parentProfileRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public void parentSignUp(ParentSignUpRequest request) {
        if (parentProfileRepository.existsByInviteCode(request.inviteCode())) {
            throw new BusinessException(ErrorCode.INVITE_CODE_DUPLICATED);
        }

        Member member = Member.createMember(
                MemberType.PARENT,
                request.name(),
                request.phoneNumber()
        );
        memberRepository.save(member);

        ParentProfile parentProfile = ParentProfile.createParentProfile(
                member,
                request.birthDate(),
                request.address(),
                request.detailAddress(),
                request.inviteCode()
        );
        parentProfileRepository.save(parentProfile);
    }

    @Transactional
    public TokenPairResponse parentSignIn(ParentSignInRequest request) {
        ParentProfile parentProfile = parentProfileRepository.findByInviteCode(request.inviteCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_CODE_NOT_FOUND, request.inviteCode()));

        Member member = parentProfile.getMember();

        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }
}
