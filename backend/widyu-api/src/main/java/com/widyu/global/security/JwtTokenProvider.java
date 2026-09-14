package com.widyu.global.security;

import com.widyu.auth.RefreshToken;
import com.widyu.auth.TemporaryMember;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.SocialTemporaryTokenDto;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.JwtUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;
    private final MemberSessionService memberSessionService;

    @Transactional
    public TokenPairResponse generateTokenPair(Long memberId, MemberRole memberRole, String loginType) {
        Member member = memberSessionService.lock(memberId);
        member.requireActive();
        if (memberRole == MemberRole.ADMIN && member.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String accessToken = jwtUtil.generateAccessToken(memberId, memberRole, loginType, member.getAuthVersion());
        String refreshToken = jwtUtil.generateRefreshToken(memberId, member.getAuthVersion());
        saveRefreshTokenToStorage(memberId, refreshToken);

        return TokenPairResponse.of(memberId, accessToken, refreshToken);
    }

    public TemporaryTokenResponse generateTemporaryToken(TemporaryMember temporaryMember) {
        String temporaryToken = generateTemporaryTokenValue(temporaryMember);
        return TemporaryTokenResponse.from(temporaryToken);
    }

    @Transactional
    public String generateSocialTemporaryToken(Long memberId, String provider, String oauthId, String email) {
        Member member = memberSessionService.lock(memberId);
        member.requireActive();
        return jwtUtil.generateSocialTemporaryToken(memberId, provider, oauthId, email, member.getAuthVersion());
    }

    @Transactional
    public String generateAccessToken(Long memberId, MemberRole memberRole, String loginType) {
        Member member = memberSessionService.lock(memberId);
        member.requireActive();
        if (memberRole == MemberRole.ADMIN && member.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return jwtUtil.generateAccessToken(memberId, memberRole, loginType, member.getAuthVersion());
    }

    @Transactional
    public AccessTokenDto generateAccessTokenDto(Long memberId, MemberRole memberRole, String loginType) {
        return jwtUtil.parseAccessToken(generateAccessToken(memberId, memberRole, loginType));
    }

    public AccessTokenDto retrieveAccessToken(String accessTokenValue) {
        try {
            AccessTokenDto accessTokenDto = jwtUtil.parseAccessToken(accessTokenValue);
            if (accessTokenDto == null) {
                throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
            }
            if (!memberSessionService.isCurrent(accessTokenDto.memberId(), jwtUtil.accessVersion(accessTokenValue))) {
                throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
            }
            return accessTokenDto;
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.EXPIRED_ACCESS_TOKEN);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefreshTokenDto retrieveRefreshToken(String refreshTokenValue) {
        RefreshTokenDto refreshTokenDto = parseRefreshTokenSafely(refreshTokenValue);
        Member member = memberSessionService.lock(refreshTokenDto.memberId());
        Long version = jwtUtil.refreshVersion(refreshTokenValue);
        if (member.getStatus() != Status.ACTIVE
                || version == null || version != member.getAuthVersion()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        validateRefreshTokenMatches(refreshTokenDto.memberId(), refreshTokenValue);
        return refreshTokenDto;
    }

    public TemporaryTokenDto retrieveTemporaryToken(String temporaryTokenValue) {
        try {
            return jwtUtil.parseTemporaryToken(temporaryTokenValue);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TEMPORARY_TOKEN_EXPIRED);
        } catch (Exception e) {
            log.debug("Temporary Token 파싱 실패");
            return null;
        }
    }

    @Transactional
    public SocialTemporaryTokenDto retrieveSocialTemporaryToken(String socialTemporaryTokenValue) {
        try {
            SocialTemporaryTokenDto socialTemporaryTokenDto = jwtUtil.parseSocialTemporaryToken(socialTemporaryTokenValue);
            if (socialTemporaryTokenDto == null) {
                throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
            }
            Member member = memberSessionService.lock(socialTemporaryTokenDto.memberId());
            Long version = jwtUtil.temporaryVersion(socialTemporaryTokenValue);
            if (member.getStatus() != Status.ACTIVE || version == null || version != member.getAuthVersion()) {
                throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
            }
            return socialTemporaryTokenDto;
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TEMPORARY_TOKEN_EXPIRED);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Social Temporary Token 파싱 실패");
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }
    }

    public AccessTokenDto reissueAccessTokenIfExpired(String accessTokenValue) {
        retrieveAccessToken(accessTokenValue);
        return null;
    }

    @Transactional
    public RefreshTokenDto createRefreshTokenDto(Long memberId) {
        Member member = memberSessionService.lock(memberId);
        member.requireActive();
        String token = jwtUtil.generateRefreshToken(memberId, member.getAuthVersion());
        saveRefreshTokenToStorage(memberId, token);
        return jwtUtil.parseRefreshToken(token);
    }

    private void saveRefreshTokenToStorage(Long memberId, String refreshTokenValue) {
        RefreshToken refreshToken = RefreshToken.builder()
                .memberId(memberId)
                .token(refreshTokenValue)
                .ttl(jwtUtil.getRefreshTokenExpirationTime())
                .build();

        refreshTokenRepository.save(refreshToken);
    }

    private String generateTemporaryTokenValue(TemporaryMember temporaryMember) {
        return jwtUtil.generateTemporaryToken(temporaryMember.getId());
    }

    private RefreshTokenDto parseRefreshTokenSafely(String refreshTokenValue) {
        try {
            RefreshTokenDto refreshTokenDto = jwtUtil.parseRefreshToken(refreshTokenValue);
            if (refreshTokenDto == null) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            return refreshTokenDto;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private void validateRefreshTokenMatches(Long memberId, String refreshTokenValue) {
        RefreshToken savedRefreshToken = refreshTokenRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (!savedRefreshToken.getToken().equals(refreshTokenValue)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

}
