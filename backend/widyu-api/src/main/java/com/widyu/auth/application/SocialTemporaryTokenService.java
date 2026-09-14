package com.widyu.auth.application;

import com.widyu.auth.dto.SocialTemporaryTokenDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.MemberSessionService;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SocialTemporaryTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberSessionService memberSessionService;

    public String createSocialTemporaryToken(Long memberId, String provider, String oauthId, String email) {
        String token = jwtTokenProvider.generateSocialTemporaryToken(memberId, provider, oauthId, email);
        
        log.info("소셜 임시 토큰 생성: memberId={}, provider={}", memberId, provider);
        
        return token;
    }

    public SocialTemporaryTokenDto validateAndRetrieve(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }

        SocialTemporaryTokenDto tokenDto = jwtTokenProvider.retrieveSocialTemporaryToken(token);

        log.info("소셜 임시 토큰 검증 성공: memberId={}, provider={}", 
                tokenDto.memberId(), tokenDto.provider());

        return tokenDto;
    }

    @Transactional
    public void deleteSocialTemporaryToken(String token) {
        SocialTemporaryTokenDto dto = jwtTokenProvider.retrieveSocialTemporaryToken(token);
        memberSessionService.revoke(dto.memberId());
    }
}
