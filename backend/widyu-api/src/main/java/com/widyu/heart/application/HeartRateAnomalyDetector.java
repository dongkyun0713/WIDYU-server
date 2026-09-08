package com.widyu.heart.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AiProperties;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateMeasurement;
import java.time.Duration;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartRateAnomalyDetector {

    private static final ZoneId HEART_RATE_ZONE = ZoneId.of("Asia/Seoul");
    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    /** 측정값 1건을 AI에 전달하고 판정 결과를 반환한다. */
    public DetectionResult detect(Long memberId, HeartRateMeasurement measurement, String context) {
        long startedAt = System.nanoTime();
        AiHeartRateResponse response = requestAnalysis(memberId, measurement, context);
        HeartRateStatus status = parseStatus(response);
        boolean emergency = Boolean.TRUE.equals(response.alert()) && status == HeartRateStatus.EMERGENCY;

        logAnalysis(memberId, context, status, response, startedAt);

        return new DetectionResult(status, emergency);
    }

    /**
     * AI 판정 근거를 남긴다. 개인화(layer=L1, baselineSource=PERSONAL)가 실제로 적용되는지와
     * 누적 표본 수를 운영에서 확인하기 위한 로그다.
     * 심박 수치는 개인 건강정보이므로 어느 프로파일에서도 기본 비활성화하고, 진단이 필요한 순간에만
     * {@code LOGGING_LEVEL_COM_WIDYU_HEART=DEBUG}로 켰다가 끈다.
     */
    private void logAnalysis(
            Long memberId,
            String context,
            HeartRateStatus status,
            AiHeartRateResponse response,
            long startedAt
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.debug(
                "심박 AI 판정: memberId={}, context={}, status={}, 소요={}ms, layer={}, baselineSource={}, sampleCount={}, 이상사유={}",
                memberId,
                context,
                status,
                elapsedMillis,
                response.layer(),
                response.baselineSource(),
                response.sampleCount(),
                response.reason()
        );
    }

    private AiHeartRateResponse requestAnalysis(
            Long memberId,
            HeartRateMeasurement measurement,
            String context
    ) {
        String url = aiProperties.server().url() + "/api/hr";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            AiHeartRateRequest body = AiHeartRateRequest.of(memberId, measurement, context);
            HttpEntity<AiHeartRateRequest> request = new HttpEntity<>(body, headers);
            String result = aiRestTemplate.postForObject(url, request, String.class);
            return parseResponse(result);
        } catch (RestClientException e) {
            log.error("AI 서버 호출 실패: url={}, error={}", url, e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "AI 서버와의 통신에 실패했습니다. 잠시 후 다시 시도해주세요."
            );
        }
    }

    private AiHeartRateResponse parseResponse(String jsonResponse) {
        try {
            AiHeartRateResponse response = objectMapper.readValue(jsonResponse, AiHeartRateResponse.class);
            if (response.alert() == null || response.level() == null) {
                throw invalidResponse();
            }
            return response;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("AI 서버 응답 처리 실패: response={}, error={}", jsonResponse, e.getMessage());
            throw invalidResponse();
        }
    }

    private HeartRateStatus parseStatus(AiHeartRateResponse response) {
        return switch (response.level()) {
            case "NORMAL" -> HeartRateStatus.NORMAL;
            case "CAUTION" -> HeartRateStatus.CAUTION;
            case "EMERGENCY" -> HeartRateStatus.EMERGENCY;
            default -> throw invalidResponse();
        };
    }

    private BusinessException invalidResponse() {
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "AI 서버가 올바르지 않은 응답을 반환했습니다.");
    }

    public record DetectionResult(HeartRateStatus status, boolean emergency) {
    }

    private record AiHeartRateRequest(
            @JsonProperty("user_id") String userId,
            Integer bpm,
            String context,
            Double timestamp
    ) {
        private static AiHeartRateRequest of(
                Long memberId,
                HeartRateMeasurement measurement,
                String context
        ) {
            double timestamp = measurement.measuredAt()
                    .atZone(HEART_RATE_ZONE)
                    .toInstant()
                    .toEpochMilli() / 1000.0;
            return new AiHeartRateRequest(
                    memberId.toString(),
                    measurement.heartRate(),
                    context,
                    timestamp
            );
        }
    }

    /**
     * 판정에 사용하는 필드는 {@code alert}, {@code level} 뿐이고 나머지는 로그 관측용이다.
     * 영속화 범위는 LLD-0019를 따른다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AiHeartRateResponse(
            Boolean alert,
            String level,
            String layer,
            String reason,
            @JsonProperty("baseline_source") String baselineSource,
            @JsonProperty("sample_count") Integer sampleCount
    ) {
    }
}
