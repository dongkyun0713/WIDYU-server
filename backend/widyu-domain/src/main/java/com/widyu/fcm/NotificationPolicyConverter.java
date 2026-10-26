package com.widyu.fcm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 기존 enum 문자열은 읽기만 지원하고 새 행에는 정책 값 자체를 저장한다. */
@Converter
public class NotificationPolicyConverter implements AttributeConverter<NotificationPolicy, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(NotificationPolicy policy) {
        if (policy == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("알림 정책을 저장할 수 없습니다.", exception);
        }
    }

    @Override
    public NotificationPolicy convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith("{")) {
            return NotificationPolicy.fromLegacyName(value);
        }
        try {
            return MAPPER.readValue(value, NotificationPolicy.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("알림 정책 저장 형식이 올바르지 않습니다.", exception);
        }
    }
}
