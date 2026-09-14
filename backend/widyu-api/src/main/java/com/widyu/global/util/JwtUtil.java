package com.widyu.global.util;

import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;
import static com.widyu.global.constant.SecurityConstant.TOKEN_ROLE_NAME;

import com.widyu.auth.TokenType;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.SocialTemporaryTokenDto;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.global.properties.JwtProperties;
import com.widyu.member.MemberRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private static final String TOKEN_TYPE_KEY_NAME = "type";
    private static final String LOGIN_TYPE_KEY_NAME = "loginType";
    private static final String PROVIDER_KEY_NAME = "provider";
    private static final String OAUTH_ID_KEY_NAME = "oauthId";
    private static final String EMAIL_KEY_NAME = "email";
    private static final String HEADER_TYP = "typ";
    private static final String HEADER_ALG = "alg";
    private static final String HEADER_REG_DATE = "regDate";
    private static final String JWT_TYPE = "JWT";
    private static final String HS256_ALG = "HS256";

    public String generateAccessToken(Long memberId, MemberRole memberRole, String loginType) {
        return generateAccessToken(memberId, memberRole, loginType, null);
    }

    public String generateAccessToken(Long memberId, MemberRole memberRole, String loginType, Long authVersion) {
        TokenTimeInfo timeInfo = createTokenTimeInfo(jwtProperties.accessTokenExpirationMilliTime());
        Map<String, Object> claims = new HashMap<>(Map.of(TOKEN_ROLE_NAME, memberRole.name(), LOGIN_TYPE_KEY_NAME, loginType));
        if (authVersion != null) {
            claims.put("authVersion", authVersion);
        }
        return buildJwtToken(
                TokenType.ACCESS,
                memberId.toString(),
                claims,
                timeInfo,
                getAccessTokenKey()
        );
    }

    public AccessTokenDto generateAccessTokenDto(Long memberId, MemberRole memberRole, String loginType) {
        String tokenValue = generateAccessToken(memberId, memberRole, loginType);
        return AccessTokenDto.of(memberId, memberRole, loginType, tokenValue);
    }

    public String generateRefreshToken(Long memberId) {
        return generateRefreshToken(memberId, null);
    }

    public String generateRefreshToken(Long memberId, Long authVersion) {
        TokenTimeInfo timeInfo = createTokenTimeInfo(jwtProperties.refreshTokenExpirationMilliTime());
        Map<String, Object> claims = new HashMap<>();
        claims.put("jti", UUID.randomUUID().toString());
        if (authVersion != null) {
            claims.put("authVersion", authVersion);
        }
        return buildJwtToken(
                TokenType.REFRESH,
                memberId.toString(),
                claims,
                timeInfo,
                getRefreshTokenKey()
        );
    }

    public String generateTemporaryToken(String temporaryMemberId) {
        TokenTimeInfo timeInfo = createTokenTimeInfo(jwtProperties.temporaryTokenExpirationTime());
        return buildJwtToken(
                TokenType.TEMPORARY,
                temporaryMemberId,
                Map.of(TOKEN_ROLE_NAME, MemberRole.TEMPORARY.name()),
                timeInfo,
                getTemporaryTokenKey()
        );
    }

    public String generateSocialTemporaryToken(Long memberId, String provider, String oauthId, String email) {
        return generateSocialTemporaryToken(memberId, provider, oauthId, email, null);
    }

    public String generateSocialTemporaryToken(Long memberId, String provider, String oauthId, String email, Long authVersion) {
        TokenTimeInfo timeInfo = createTokenTimeInfo(jwtProperties.temporaryTokenExpirationTime());
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_ROLE_NAME, MemberRole.USER.name());
        claims.put(PROVIDER_KEY_NAME, provider);
        claims.put(OAUTH_ID_KEY_NAME, oauthId);
        claims.put(EMAIL_KEY_NAME, email);
        if (authVersion != null) {
            claims.put("authVersion", authVersion);
        }
        return buildJwtToken(
                TokenType.TEMPORARY,
                memberId.toString(),
                claims,
                timeInfo,
                getTemporaryTokenKey()
        );
    }

    public AccessTokenDto parseAccessToken(String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = parseTokenClaims(token, getAccessTokenKey());
            Claims body = claims.getBody();

            return new AccessTokenDto(
                    Long.parseLong(body.getSubject()),
                    MemberRole.valueOf(body.get(TOKEN_ROLE_NAME, String.class)),
                    body.get(LOGIN_TYPE_KEY_NAME, String.class),
                    token,
                    body.get("authVersion", Long.class)
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public RefreshTokenDto parseRefreshToken(String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = parseTokenClaims(token, getRefreshTokenKey());
            Claims body = claims.getBody();

            return new RefreshTokenDto(
                    Long.parseLong(body.getSubject()),
                    token,
                    jwtProperties.refreshTokenExpirationTime()
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public TemporaryTokenDto parseTemporaryToken(String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = parseTokenClaims(token, getTemporaryTokenKey());
            Claims body = claims.getBody();

            return new TemporaryTokenDto(
                    body.getSubject(),
                    MemberRole.valueOf(body.get(TOKEN_ROLE_NAME, String.class)),
                    token,
                    jwtProperties.temporaryTokenExpirationTime()
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public SocialTemporaryTokenDto parseSocialTemporaryToken(String token) throws ExpiredJwtException {
        try {
            Jws<Claims> claims = parseTokenClaims(token, getTemporaryTokenKey());
            Claims body = claims.getBody();

            return new SocialTemporaryTokenDto(
                    Long.valueOf(body.getSubject()),
                    body.get(PROVIDER_KEY_NAME, String.class),
                    body.get(OAUTH_ID_KEY_NAME, String.class),
                    body.get(EMAIL_KEY_NAME, String.class)
            );
        } catch (ExpiredJwtException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public RefreshTokenDto generateRefreshTokenDto(Long memberId) {
        Date issuedAt = new Date();
        Date expiredAt =
                new Date(issuedAt.getTime() + jwtProperties.refreshTokenExpirationMilliTime());
        String tokenValue = buildRefreshToken(memberId, issuedAt, expiredAt);
        return new RefreshTokenDto(
                memberId, tokenValue, jwtProperties.refreshTokenExpirationTime());
    }

    private String buildRefreshToken(Long memberId, Date issuedAt, Date expiredAt) {
        return Jwts.builder()
                .setHeader(createTokenHeader(TokenType.REFRESH))
                .setSubject(memberId.toString())
                .setIssuedAt(issuedAt)
                .setExpiration(expiredAt)
                .signWith(getRefreshTokenKey())
                .compact();
    }

    public static String extractTemporaryTokenFromHeader(HttpServletRequest request) {
        return extractTokenFromAuthorizationHeader(request);
    }

    public static String extractTokenFromAuthorizationHeader(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION))
                .filter(header -> header.startsWith(TOKEN_PREFIX))
                .map(header -> header.replace(TOKEN_PREFIX, ""))
                .orElse(null);
    }

    public long getRefreshTokenExpirationTime() {
        return jwtProperties.refreshTokenExpirationTime();
    }

    public Long accessVersion(String token) {
        return parseTokenClaims(token, getAccessTokenKey()).getBody().get("authVersion", Long.class);
    }

    public Long refreshVersion(String token) {
        return parseTokenClaims(token, getRefreshTokenKey()).getBody().get("authVersion", Long.class);
    }

    public Long temporaryVersion(String token) {
        return parseTokenClaims(token, getTemporaryTokenKey()).getBody().get("authVersion", Long.class);
    }

    private String buildJwtToken(TokenType tokenType, String subject,
                                 Map<String, Object> claims, TokenTimeInfo timeInfo, Key signingKey) {
        var builder = Jwts.builder()
                .setHeader(createTokenHeader(tokenType))
                .setSubject(subject)
                .setIssuedAt(timeInfo.issuedAt())
                .setExpiration(timeInfo.expiredAt())
                .signWith(signingKey);

        claims.forEach(builder::claim);

        return builder.compact();
    }

    private TokenTimeInfo createTokenTimeInfo(long expirationMillis) {
        Date issuedAt = new Date();
        Date expiredAt = new Date(issuedAt.getTime() + expirationMillis);
        return new TokenTimeInfo(issuedAt, expiredAt);
    }

    private Map<String, Object> createTokenHeader(TokenType tokenType) {
        return Map.of(
                HEADER_TYP, JWT_TYPE,
                HEADER_ALG, HS256_ALG,
                HEADER_REG_DATE, System.currentTimeMillis(),
                TOKEN_TYPE_KEY_NAME, tokenType.getValue()
        );
    }

    private Jws<Claims> parseTokenClaims(String token, Key key) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token);
    }

    private Key getAccessTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.accessTokenSecret().getBytes());
    }

    private Key getRefreshTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.refreshTokenSecret().getBytes());
    }

    private Key getTemporaryTokenKey() {
        return Keys.hmacShaKeyFor(jwtProperties.temporaryTokenSecret().getBytes());
    }

    private record TokenTimeInfo(Date issuedAt, Date expiredAt) {}
}
