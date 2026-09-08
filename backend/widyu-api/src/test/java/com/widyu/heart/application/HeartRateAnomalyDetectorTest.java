package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AiProperties;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.dto.request.HeartRateMeasurement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateAnomalyDetector 예외 처리 단위 테스트")
class HeartRateAnomalyDetectorTest {

    @Mock private RestTemplate aiRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("측정값 한 건을 AI에 한 번 요청해 판정한다")
    void 측정값_한건을_AI에_한번_요청해_판정한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willReturn("{\"alert\":true,\"level\":\"EMERGENCY\"}");

        // when
        DetectionResult result = detector.detect(1L, measurement(), "UNKNOWN");

        // then
        assertThat(result.status()).isEqualTo(HeartRateStatus.EMERGENCY);
        assertThat(result.emergency()).isTrue();
        then(aiRestTemplate).should(times(1))
                .postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class));
    }

    @Test
    @DisplayName("AI 서버 통신 실패 시 INTERNAL_SERVER_ERROR 예외를 던진다")
    void AI_서버_통신_실패_시_예외가_발생한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willThrow(new RestClientException("connection refused"));

        // when & then
        assertThatThrownBy(() -> detector.detect(1L, measurement(), "REST"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR)
                .hasMessageContaining("AI 서버와의 통신에 실패했습니다.");
    }

    @Test
    @DisplayName("측정값을 AI 계약의 JSON으로 전송한다")
    void 측정값을_AI_계약의_JSON으로_전송한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willReturn("{\"alert\":false,\"level\":\"NORMAL\"}");

        // when
        DetectionResult result = detector.detect(1023L, measurement(), "ACTIVE");

        // then
        assertThat(result.status()).isEqualTo(HeartRateStatus.NORMAL);
        assertThat(result.emergency()).isFalse();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        then(aiRestTemplate).should(times(1))
                .postForObject(eq("http://ai-server/api/hr"), requestCaptor.capture(), eq(String.class));

        JsonNode request = objectMapper.valueToTree(requestCaptor.getValue().getBody());
        assertThat(request.get("user_id").asText()).isEqualTo("1023");
        assertThat(request.get("bpm").asInt()).isEqualTo(70);
        assertThat(request.get("context").asText()).isEqualTo("ACTIVE");
        assertThat(request.get("timestamp").asDouble()).isEqualTo(
                LocalDateTime.of(2026, 7, 26, 12, 0)
                        .atZone(ZoneId.of("Asia/Seoul"))
                        .toEpochSecond()
        );
    }

    @Test
    @DisplayName("AI 서버가 지원하지 않는 level을 반환하면 INTERNAL_SERVER_ERROR 예외를 던진다")
    void AI_서버가_지원하지않는_level을_반환하면_예외가_발생한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willReturn("{\"alert\":false,\"level\":\"UNKNOWN\"}");

        // when & then
        assertThatThrownBy(() -> detector.detect(1L, measurement(), "UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR)
                .hasMessageContaining("올바르지 않은 응답");
    }

    @Test
    @DisplayName("CAUTION 응답은 alert가 true여도 긴급으로 판단하지 않는다")
    void CAUTION_응답은_alert가_true여도_긴급으로_판단하지않는다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willReturn("{\"alert\":true,\"level\":\"CAUTION\"}");

        // when
        DetectionResult result = detector.detect(1L, measurement(), "REST");

        // then
        assertThat(result.status()).isEqualTo(HeartRateStatus.CAUTION);
        assertThat(result.emergency()).isFalse();
    }

    private HeartRateAnomalyDetector detector() {
        AiProperties properties = new AiProperties(new AiProperties.Server("http://ai-server"));
        return new HeartRateAnomalyDetector(aiRestTemplate, properties, objectMapper);
    }

    private HeartRateMeasurement measurement() {
        return new HeartRateMeasurement(70, LocalDateTime.of(2026, 7, 26, 12, 0));
    }
}
