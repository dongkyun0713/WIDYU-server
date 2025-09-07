package com.widyu.auth.application.guardian;

import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.request.RefreshTokenRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 토큰 관리 전용 서비스
 */
@Service
@RequiredArgsConstructor
public class GuardianTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final MemberUtil memberUtil;

    /**
     * 리프레시 토큰으로 새로운 토큰 쌍 발급
     */
    @Transactional(readOnly = true)
    public TokenPairResponse reissueTokenPair(RefreshTokenRequest request) {

        RefreshTokenDto refreshTokenDto = jwtTokenProvider.retrieveRefreshToken(request.refreshToken());

        RefreshTokenDto newRefreshToken = jwtTokenProvider.createRefreshTokenDto(refreshTokenDto.memberId());
        
        Member member = memberUtil.getMemberByMemberId(newRefreshToken.memberId());

        // 토큰 재발급이므로 기존 loginType을 유지하기 위해 회원의 계정 타입 확인
        String loginType = member.getLocalAccount() != null ? "local" :
                          member.getSocialAccounts().stream()
                                  .findFirst()
                                  .map(account -> account.getProvider())
                                  .orElse("unknown");
        return jwtTokenProvider.generateTokenPair(member.getId(), MemberRole.USER, loginType);
    }
}
