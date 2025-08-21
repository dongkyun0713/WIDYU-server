package com.widyu.global.util;

import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;
import static com.widyu.global.constant.SecurityConstant.TOKEN_ROLE_NAME;

import com.widyu.auth.domain.TokenType;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.global.properties.JwtProperties;
import com.widyu.member.domain.MemberRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;
    private static final String TOKEN_TYPE_KEY_NAME = "type";

    public String generateAccessToken(final Long memberId, final MemberRole memberRole) {
        Date issuedAt = new Date();
        Date expiredAt =
                new Date(issuedAt.getTime() + jwtProperties.accessTokenExpirationMilliTime());

        return buildAccessToken(memberId, memberRole, issuedAt, expiredAt);
    }

    public AccessTokenDto generateAccessTokenDto(final Long memberId, final MemberRole memberRole) {
        Date issuedAt = new Date();
        Date expiredAt =
                new Date(issuedAt.getTime() + jwtProperties.accessTokenExpirationMilliTime());
        String tokenValue = buildAccessToken(memberId, memberRole, issuedAt, expiredAt);

        return AccessTokenDto.of(memberId, memberRole, tokenValue);
    }

    public String generateRefreshToken(final Long memberId) {
        Date issuedAt = new Date();
        Date expiredAt = new Date(issuedAt.getTime() + jwtProperties.refreshTokenExpirationMilliTime());
        return buildRefreshToken(memberId, issuedAt, expiredAt);
    }

    public String generateTemporaryToken(final String temporaryMemberId) {
        Date issuedAt = new Date();
        Date expiredAt = new Date(issuedAt.getTime() + jwtProperties.temporaryTokenExpirationTime());

        return Jwts.builder()
                .setHeader(createTokenHeader(TokenType.TEMPORARY))
                .setSubject(temporaryMemberId)
                .claim(TOKEN_ROLE_NAME, MemberRole.TEMPORARY)
                .setIssuedAt(issuedAt)
                .setExpiration(expiredAt)
                .signWith(getTemporaryTokenKey())
                .compact();
    }

    public static String extractTemporaryTokenFromHeader(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION))
                .filter(header -> header.startsWith(TOKEN_PREFIX))
                .map(header -> header.replace(TOKEN_PREFIX, ""))
                .orElse(null);
    }

    public AccessTokenDto parseAccessToken(final String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = getClaims(token, getAccessTokenKey());

            return new AccessTokenDto(
                    Long.parseLong(claims.getBody().getSubject()),
                    MemberRole.valueOf(claims.getBody().get("role", String.class)),
                    token);
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public RefreshTokenDto parseRefreshToken(final String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = getClaims(token, getRefreshTokenKey());

            return new RefreshTokenDto(
                    Long.parseLong(claims.getBody().getSubject()),
                    MemberRole.valueOf(claims.getBody().get("role", String.class)),
                    token,
                    jwtProperties.refreshTokenExpirationTime()
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public TemporaryTokenDto parseTemporaryToken(final String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = getClaims(token, getTemporaryTokenKey());

            return new TemporaryTokenDto(
                    claims.getBody().getSubject(),
                    MemberRole.valueOf(claims.getBody().get("role", String.class)),
                    token,
                    jwtProperties.temporaryTokenExpirationTime()
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public long getRefreshTokenExpirationTime() {
        return jwtProperties.refreshTokenExpirationTime();
    }

    private Key getRefreshTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.refreshTokenSecret().getBytes());
    }

    private Key getAccessTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.accessTokenSecret().getBytes());
    }

    private Key getTemporaryTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.temporaryTokenSecret().getBytes());
    }

    private Jws<Claims> getClaims(final String token, final Key key) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token);
    }

    private String buildAccessToken(final Long memberId, final MemberRole memberRole, final Date issuedAt,
                                    final Date expiredAt) {
        return Jwts.builder()
                .setHeader(
                        createTokenHeader(TokenType.ACCESS))
                .setSubject(memberId.toString())
                .claim(TOKEN_ROLE_NAME, memberRole.name())
                .setIssuedAt(issuedAt)
                .setExpiration(expiredAt)
                .signWith(getAccessTokenKey())
                .compact();
    }

    private String buildRefreshToken(final Long memberId, final Date issuedAt, final Date expiredAt) {
        return Jwts.builder()
                .setHeader(createTokenHeader(TokenType.REFRESH))
                .setSubject(memberId.toString())
                .setIssuedAt(issuedAt)
                .setExpiration(expiredAt)
                .signWith(getRefreshTokenKey())
                .compact();
    }

    private Map<String, Object> createTokenHeader(final TokenType tokenType) {
        return Map.of(
                "typ",
                "JWT",
                "alg",
                "HS256",
                "regDate",
                System.currentTimeMillis(),
                TOKEN_TYPE_KEY_NAME,
                tokenType.getValue()
        );
    }
}
