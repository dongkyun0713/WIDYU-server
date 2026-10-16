package com.widyu.fcm.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FcmTokenLoginRequestTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"token\":\"fcm-token\"}",
            "{\"token\":\"fcm-token\",\"deviceInfo\":\"iPhone\"}"
    })
    @DisplayName("기기 정보가 없는 요청과 구버전 요청 모두 토큰만 읽는다")
    void 토큰_등록_요청에서_토큰만_읽는다(String json) throws Exception {
        // given
        ObjectMapper objectMapper = new ObjectMapper();

        // when
        FcmTokenLoginRequest request = objectMapper.readValue(json, FcmTokenLoginRequest.class);

        // then
        assertThat(request.token()).isEqualTo("fcm-token");
        assertThat(objectMapper.writeValueAsString(request)).isEqualTo("{\"token\":\"fcm-token\"}");
    }
}
