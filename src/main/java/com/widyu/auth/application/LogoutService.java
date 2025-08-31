package com.widyu.auth.application;

import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberUtil memberUtil;

    @Transactional
    public void logout(String refreshToken) {

        Long memberId = memberUtil.getCurrentMember().getId();

        RefreshTokenDto refresh = jwtTokenProvider.retrieveRefreshToken(refreshToken);

        if (!memberId.equals(refresh.memberId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, " : 토큰 소유자 불일치");
        }

        refreshTokenRepository.deleteById(refresh.memberId());

    }
}
