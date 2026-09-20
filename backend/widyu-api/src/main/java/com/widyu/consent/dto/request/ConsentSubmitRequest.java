package com.widyu.consent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 항목별 동의 제출(LLD-0055 3절). 항목 이름은 문자열로 받고 서비스가 {@code ConsentKey}로
 * 옮기면서 검증한다. 모르는 이름을 역직렬화 단계에서 만나면 이유를 알 수 없는 400이 되기 때문이다.
 */
public record ConsentSubmitRequest(
        @NotBlank(message = "동의문 판은 필수입니다.")
        @Size(max = 32, message = "동의문 판은 32자 이하여야 합니다.")
        String version,

        @NotEmpty(message = "동의 항목은 하나 이상이어야 합니다.")
        Map<String, @NotNull(message = "동의 여부는 필수입니다.") Boolean> consents
) {

    public static ConsentSubmitRequest of(String version, Map<String, Boolean> consents) {
        return new ConsentSubmitRequest(version, consents);
    }
}
