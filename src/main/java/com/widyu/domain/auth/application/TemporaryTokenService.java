package com.widyu.domain.auth.application;

import com.widyu.domain.auth.entity.TemporaryMember;
import com.widyu.domain.auth.dto.TemporaryTokenDto;
import com.widyu.domain.auth.repository.TemporaryMemberRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemporaryTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberRepository temporaryMemberRepository;

    public String extractFrom(HttpServletRequest httpServletRequest) {
        String token = JwtUtil.extractTemporaryTokenFromHeader(httpServletRequest);
        if (token == null) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }
        return token;
    }

    public TemporaryTokenDto parseAndValidate(String temporaryToken) {
        TemporaryTokenDto dto = jwtTokenProvider.retrieveTemporaryToken(temporaryToken);
        if (dto == null) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }
        return dto;
    }

    public TemporaryMember loadTemporaryMemberOrThrow(String temporaryMemberId) {
        return temporaryMemberRepository.findById(temporaryMemberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    public void deleteTemporaryMember(String temporaryMemberId) {
        temporaryMemberRepository.deleteById(temporaryMemberId);
    }
}
