package com.widyu.global.security;

import static com.widyu.global.constant.SecurityConstant.TOKEN_ROLE_NAME;

import com.widyu.auth.domain.RefreshToken;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.JwtUtil;
import com.widyu.member.domain.MemberRole;
import io.jsonwebtoken.ExpiredJwtException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenPairResponse generateTokenPair(final Long memberId, final MemberRole memberRole) {
        String accessToken = createAccessToken(memberId, memberRole);
        String refreshToken = createRefreshToken(memberId);

        return TokenPairResponse.of(accessToken, refreshToken);
    }

    public TemporaryTokenResponse generateTemporaryToken(final TemporaryMember temporaryMember) {
        String temporaryToken = createTemporaryToken(temporaryMember);
        return TemporaryTokenResponse.from(temporaryToken);
    }

    private String createAccessToken(final Long memberId, final MemberRole memberRole) {
        return jwtUtil.generateAccessToken(memberId, memberRole);
    }

    public AccessTokenDto createAccessTokenDto(final Long memberId, final MemberRole memberRole) {
        return jwtUtil.generateAccessTokenDto(memberId, memberRole);
    }

    private String createRefreshToken(final Long memberId) {
        String refreshTokenValue = jwtUtil.generateRefreshToken(memberId);
        saveRefreshTokenToRedis(memberId, refreshTokenValue, jwtUtil.getRefreshTokenExpirationTime());
        return refreshTokenValue;
    }

    private void saveRefreshTokenToRedis(final Long memberId, final String refreshTokenDto, final Long ttl) {
        RefreshToken refreshToken =
                RefreshToken.builder()
                        .memberId(memberId)
                        .token(refreshTokenDto)
                        .ttl(ttl)
                        .build();
        refreshTokenRepository.save(refreshToken);
    }

    private String createTemporaryToken(final TemporaryMember temporaryMember) {
        return jwtUtil.generateTemporaryToken(temporaryMember.getId());
    }

    public AccessTokenDto retrieveAccessToken(final String accessTokenValue) {
        try {
            return jwtUtil.parseAccessToken(accessTokenValue);
        } catch (Exception e) {
            log.info("Access Token 파싱 실패");
            return null;
        }
    }

    public RefreshTokenDto retrieveRefreshToken(final String refreshTokenValue) {
        RefreshTokenDto refreshTokenDto = parseRefreshToken(refreshTokenValue);
        if (refreshTokenDto == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Optional<RefreshToken> refreshToken = getRefreshTokenFromRedis(refreshTokenDto.memberId());

        if (refreshToken.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        return refreshTokenDto;
    }

    public TemporaryTokenDto retrieveTemporaryToken(final String temporaryTokenValue) {
        try {
            return jwtUtil.parseTemporaryToken(temporaryTokenValue);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TEMPORARY_TOKEN_EXPIRED);
        } catch (Exception e) {
            log.info("Temporary Token 파싱 실패");
            return null;
        }
    }

    private Optional<RefreshToken> getRefreshTokenFromRedis(final Long memberId) {
        return refreshTokenRepository.findById(memberId);
    }

    private RefreshTokenDto parseRefreshToken(final String refreshTokenValue) {
        try {
            return jwtUtil.parseRefreshToken(refreshTokenValue);
        } catch (Exception e) {
            return null;
        }
    }

    public AccessTokenDto reissueAccessTokenIfExpired(final String accessTokenValue) {
        // AT가 만료된 경우 AT 재발급
        try {
            jwtUtil.parseAccessToken(accessTokenValue);
            return null;
        } catch (ExpiredJwtException e) {
            Long memberId = Long.parseLong(e.getClaims().getSubject());
            MemberRole memberRole =
                    MemberRole.valueOf(e.getClaims().get(TOKEN_ROLE_NAME, String.class));
            return createAccessTokenDto(memberId, memberRole);
        }
    }
}
