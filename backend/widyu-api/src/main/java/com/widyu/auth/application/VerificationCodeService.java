package com.widyu.auth.application;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final AuthLimitStore authLimitStore;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberRepository temporaryMemberRepository;

    public TemporaryTokenResponse verifyAndIssueTemporaryToken(String phoneNumber, String code) {
        String name = authLimitStore.consumeCode(phoneNumber, code);
        TemporaryMember temp = TemporaryMember.createTemporaryMember(name, phoneNumber);
        try {
            TemporaryMember saved = temporaryMemberRepository.save(temp);
            return jwtTokenProvider.generateTemporaryToken(saved);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        }
    }
}
