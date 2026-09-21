package com.widyu.consent.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 항목별 동의 철회(LLD-0055 3절). 철회도 지우는 것이 아니라 행을 하나 더 남긴다.
 *
 * <p>{@code keys}가 비었는지는 서비스가 본다. Bean Validation이 먼저 걸면 LLD-0055 6절이 정한
 * {@code CONSENT_4001} 대신 일반 검증 400이 나간다.
 */
public record ConsentWithdrawRequest(
        List<@NotBlank(message = "동의 항목 이름은 비어 있을 수 없습니다.") String> keys
) {

    public static ConsentWithdrawRequest of(List<String> keys) {
        return new ConsentWithdrawRequest(keys);
    }
}
