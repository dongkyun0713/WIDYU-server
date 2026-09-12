package com.widyu.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 문자열 컬럼을 AES-256-GCM(값마다 랜덤 IV)으로 저장 시 암호화하는 JPA 컨버터.
 * 값 기반 조회가 없는 민감 필드에만 명시적으로 {@code @Convert} 해 적용한다(ADR-0025 ②).
 *
 * <p>기존 평문 데이터와의 호환을 위해, 복호화 시 암호화 접두사({@code enc:v1:})가 없는 값은
 * 평문으로 간주해 그대로 반환한다. 쓰기는 항상 암호화하므로 시간이 지나며 점진적으로 암호화된다.
 */
@Converter
@Component
public class AesGcmStringConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmStringConverter(@Value("${widyu.encryption.aes-key}") final String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            throw new IllegalStateException("widyu.encryption.aes-key 가 설정되지 않았습니다.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("widyu.encryption.aes-key 는 AES-256용 32바이트여야 합니다.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(final String attribute) {
        if (attribute == null) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + cipherText.length)
                    .put(iv)
                    .put(cipherText)
                    .array();
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("필드 암호화에 실패했습니다.", e);
        }
    }

    @Override
    public String convertToEntityAttribute(final String dbData) {
        if (dbData == null) {
            return null;
        }

        if (!dbData.startsWith(PREFIX)) {
            // 암호화 이전에 저장된 평문 데이터 → 그대로 반환(점진적 마이그레이션)
            return dbData;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(combined);

            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("필드 복호화에 실패했습니다.", e);
        }
    }
}
