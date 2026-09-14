package com.widyu.auth.application;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.MemberSessionService;
import com.widyu.member.repository.MemberRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final AuthLimitStore authLimitStore;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberRepository temporaryMemberRepository;
    private final MemberRepository memberRepository;
    private final MemberSessionService memberSessionService;

    @Transactional
    public TemporaryTokenResponse verifyAndIssueTemporaryToken(String phoneNumber, String code) {
        String name = authLimitStore.consumeCode(phoneNumber, code);
        TemporaryMember temp = TemporaryMember.createTemporaryMember(name, phoneNumber);
        memberRepository.findByPhoneNumberAndName(phoneNumber, name).ifPresent(found -> {
            var member = memberSessionService.lock(found.getId());
            member.requireActive();
            temp.bindSession(member.getId(), member.getAuthVersion());
        });
        try {
            TemporaryMember saved = temporaryMemberRepository.save(temp);
            return jwtTokenProvider.generateTemporaryToken(saved);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        }
    }
}
