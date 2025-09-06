package com.widyu.auth.application.guardian.oauth;

import static com.widyu.global.constant.SecurityConstant.APPLE_ISSUER;

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
        LocalDateTime now = LocalDateTime.now();
        Date issuedAt = Date.from(now.atZone(ZoneId.systemDefault()).toInstant());
        Date expiration = Date.from(now.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant());

        return Jwts.builder()
                .setHeaderParam("alg", "ES256")
                .setHeaderParam("kid", appleProperties.keyId())
                .setIssuer(appleProperties.teamId())
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .setAudience(APPLE_ISSUER)
                .setSubject(appleProperties.clientId())
                .signWith(getPrivateKey(), SignatureAlgorithm.ES256)
                .compact();
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
            throw new BusinessException(ErrorCode.APPLE_PRIVATE_KEY_PARSING_FAILED, e.getMessage());
        }
    }
}