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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateAnomalyDetector 예외 처리 단위 테스트")
class HeartRateAnomalyDetectorTest {

    @Mock private RestTemplate aiRestTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    @DisplayName("심박수 데이터가 비어 있으면 BAD_REQUEST 예외를 던진다")
    void 심박수_데이터가_비어있으면_예외가_발생한다() {
        // given
        HeartRateAnomalyDetector detector = detector();

        // when & then
        assertThatThrownBy(() -> detector.detect(1L, List.of(), "REST"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("심박수 데이터가 비어 있습니다.");
    }

    @Test
    @DisplayName("측정값이 한 건이어도 AI를 호출해 판정한다")
    void 측정값이_한건이어도_AI를_호출해_판정한다() {
        // given
        // 단건 전송 경로는 15개 제약 없이 같은 detect()를 사용한다 (LLD-0023)
        HeartRateAnomalyDetector detector = detector();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willReturn("{\"alert\":true,\"level\":\"EMERGENCY\"}");

        // when
        DetectionResult result = detector.detect(1L, measurements().subList(0, 1), "UNKNOWN");

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
        assertThatThrownBy(() -> detector.detect(1L, measurements(), "REST"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR)
                .hasMessageContaining("AI 서버와의 통신에 실패했습니다.");
    }

    @Test
    @DisplayName("AI 서버 timeout이 발생하면 timeout 메트릭을 기록한다")
    void AI_서버_timeout이_발생하면_timeout_메트릭을_기록한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        ResourceAccessException timeout = new ResourceAccessException(
                "Read timed out",
                new SocketTimeoutException("Read timed out")
        );
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willThrow(timeout);

        // when & then
        assertThatThrownBy(() -> detector.detect(1L, measurements(), "REST"))
                .isInstanceOf(BusinessException.class);
        assertThat(meterRegistry.find("heart.ai.request")
                .tag("outcome", "timeout")
                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("측정값을 시간순 JSON으로 전송하면 가장 높은 상태와 긴급 여부를 반환한다")
    void 측정값을_시간순_JSON으로_전송하면_가장높은_상태를_반환한다() {
        // given
        HeartRateAnomalyDetector detector = detector();
        AtomicInteger callCount = new AtomicInteger();
        given(aiRestTemplate.postForObject(eq("http://ai-server/api/hr"), any(), eq(String.class)))
                .willAnswer(invocation -> {
                    int call = callCount.incrementAndGet();
                    if (call == 5) {
                        return "{\"alert\":false,\"level\":\"CAUTION\"}";
                    }
                    if (call == 10) {
                        return """
                                {
                                  "alert": true,
                                  "layer": "L0",
                                  "level": "EMERGENCY",
                                  "reason": "tachycardia",
                                  "bpm": 160.0,
                                  "context": "ACTIVE",
                                  "timestamp": 1785034809.0,
                                  "held_seconds": 30.0,
                                  "baseline_source": "PRIOR",
                                  "sample_count": 10
                                }
                                """;
                    }
                    return "{\"alert\":false,\"level\":\"NORMAL\"}";
                });
        List<HeartRateMeasurement> reversed = measurements().stream()
                .sorted(Comparator.comparing(HeartRateMeasurement::measuredAt).reversed())
                .toList();

        // when
        DetectionResult result = detector.detect(1023L, reversed, "ACTIVE");

        // then
        assertThat(result.status()).isEqualTo(HeartRateStatus.EMERGENCY);
        assertThat(result.emergency()).isTrue();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        then(aiRestTemplate).should(times(15))
                .postForObject(eq("http://ai-server/api/hr"), requestCaptor.capture(), eq(String.class));

        JsonNode firstRequest = objectMapper.valueToTree(requestCaptor.getAllValues().getFirst().getBody());
        assertThat(firstRequest.get("user_id").asText()).isEqualTo("1023");
        assertThat(firstRequest.get("bpm").asInt()).isEqualTo(70);
        assertThat(firstRequest.get("context").asText()).isEqualTo("ACTIVE");
        assertThat(firstRequest.get("timestamp").asDouble()).isEqualTo(
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
        assertThatThrownBy(() -> detector.detect(1L, measurements(), "UNKNOWN"))
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
        DetectionResult result = detector.detect(1L, measurements(), "REST");

        // then
        assertThat(result.status()).isEqualTo(HeartRateStatus.CAUTION);
        assertThat(result.emergency()).isFalse();
    }

    private HeartRateAnomalyDetector detector() {
        AiProperties properties = new AiProperties(new AiProperties.Server("http://ai-server"));
        return new HeartRateAnomalyDetector(aiRestTemplate, properties, objectMapper, meterRegistry);
    }

    private List<HeartRateMeasurement> measurements() {
        LocalDateTime batchStart = LocalDateTime.of(2026, 7, 26, 12, 0);
        return java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> new HeartRateMeasurement(70 + i, batchStart.plusSeconds(i)))
                .toList();
    }
}
