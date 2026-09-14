package com.widyu.auth.application.guardian.oauth.strategy.apple;

import static com.widyu.global.constant.SecurityConstant.APPLE_ISSUER;

import com.widyu.global.constant.Platform;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AppleProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppleJwtUtils {

    public static final String KEY_ALGORITHM = "EC";

    private final AppleProperties appleProperties;

    public String generateClientSecret() {
        return generateClientSecret(null);
    }

    public String generateClientSecret(String platformValue) {
        LocalDateTime now = LocalDateTime.now();
        Date issuedAt = Date.from(now.atZone(ZoneId.systemDefault()).toInstant());
        Date expiration = Date.from(now.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant());

        String clientId = getClientIdByPlatform(platformValue);
        log.debug("Apple JWT 생성");

        return Jwts.builder()
                .setHeaderParam("alg", "ES256")
                .setHeaderParam("kid", appleProperties.keyId())
                .setIssuer(appleProperties.teamId())
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .setAudience(APPLE_ISSUER)
                .setSubject(clientId)
                .signWith(getPrivateKey(), SignatureAlgorithm.ES256)
                .compact();
    }

    private String getClientIdByPlatform(String platformValue) {
        Platform platform = Platform.from(platformValue);
        return switch (platform) {
            case ANDROID -> appleProperties.androidClientId();
            case IOS -> appleProperties.iosClientId();
        };
    }

    private PrivateKey getPrivateKey() {
        try {
            String privateKeyContent = appleProperties.privateKey()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] encoded = Base64.getDecoder().decode(privateKeyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            
            return keyFactory.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Apple 개인 키 파싱 실패");
            throw new BusinessException(ErrorCode.APPLE_PRIVATE_KEY_PARSING_FAILED);
        }
    }
}
