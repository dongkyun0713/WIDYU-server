package com.widyu.consent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 항목별 동의 철회(LLD-0055 3절). 철회도 지우는 것이 아니라 행을 하나 더 남긴다. */
public record ConsentWithdrawRequest(
        @NotEmpty(message = "철회할 동의 항목은 하나 이상이어야 합니다.")
        List<@NotBlank(message = "동의 항목 이름은 비어 있을 수 없습니다.") String> keys
) {

    public static ConsentWithdrawRequest of(List<String> keys) {
        return new ConsentWithdrawRequest(keys);
    }
}
