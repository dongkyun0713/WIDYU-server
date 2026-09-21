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
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
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
    private final MeterRegistry meterRegistry;

    /** 측정값 1건을 AI에 전달하고 판정 결과를 반환한다. */
    @Timed("heart.ai.detection")
    public DetectionResult detect(Long memberId, HeartRateMeasurement measurement, String context) {
        long startedAt = System.nanoTime();
        AiHeartRateResponse response = requestAnalysis(memberId, measurement, context);
        HeartRateStatus status = parseStatus(response);
        boolean emergency = Boolean.TRUE.equals(response.alert()) && status == HeartRateStatus.EMERGENCY;

        logAnalysis(memberId, context, response, startedAt);

        return new DetectionResult(status, emergency, response.level(), response.reason());
    }

    /**
     * AI 호출의 소요 시간과 누적 표본 수만 남긴다.
     * 판정 상태·이상 사유·기준선 출처는 개인 건강정보이므로 어느 레벨에도 남기지 않는다
     * (#639, 정책 1.6.7·완료기준 C9-6).
     */
    private void logAnalysis(
            Long memberId,
            String context,
            AiHeartRateResponse response,
            long startedAt
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.debug(
                "심박 AI 판정: memberId={}, context={}, 소요={}ms, sampleCount={}",
                memberId,
                context,
                elapsedMillis,
                response.sampleCount()
        );
    }

    private AiHeartRateResponse requestAnalysis(
            Long memberId,
            HeartRateMeasurement measurement,
            String context
    ) {
        String url = aiProperties.server().url() + "/api/hr";
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            AiHeartRateRequest body = AiHeartRateRequest.of(memberId, measurement, context);
            HttpEntity<AiHeartRateRequest> request = new HttpEntity<>(body, headers);
            String result = aiRestTemplate.postForObject(url, request, String.class);
            AiHeartRateResponse response = parseResponse(result);
            parseStatus(response);
            outcome = "success";
            return response;
        } catch (RestClientException e) {
            outcome = classifyAiRequestFailure(e);
            log.error(
                    "AI 서버 호출 실패: url={}, exception={}, cause={}",
                    url,
                    e.getClass().getSimpleName(),
                    causeName(e));
            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "AI 서버와의 통신에 실패했습니다. 잠시 후 다시 시도해주세요."
            );
        } finally {
            sample.stop(Timer.builder("heart.ai.request")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private String causeName(Throwable throwable) {
        Throwable cause = throwable.getCause();
        if (cause == null) {
            return "-";
        }
        return cause.getClass().getSimpleName();
    }

    private String classifyAiRequestFailure(RestClientException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return "timeout";
            }
            cause = cause.getCause();
        }
        return "error";
    }

    private AiHeartRateResponse parseResponse(String jsonResponse) {
        try {
            AiHeartRateResponse response = objectMapper.readValue(jsonResponse, AiHeartRateResponse.class);
            if (response.alert() == null || response.level() == null) {
                throw invalidResponse();
            }
            return response;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error(
                    "AI 서버 응답 처리 실패: exception={}, responseLength={}",
                    e.getClass().getSimpleName(),
                    responseLength(jsonResponse));
            throw invalidResponse();
        }
    }

    private int responseLength(String jsonResponse) {
        if (jsonResponse == null) {
            return 0;
        }
        return jsonResponse.length();
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

    /**
     * {@code level}·{@code reason}은 판정 기록 행에만 들어간다(ADR-0035 결정 2).
     * 로그·응답 DTO로 흘려보내지 않는다.
     */
    public record DetectionResult(HeartRateStatus status, boolean emergency, String level, String reason) {
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
     * 판정에 사용하는 필드는 {@code alert}, {@code level}이고 {@code sample_count}는 로그 관측용이다.
     * {@code reason}은 판정 사유(개인 건강정보)라 <b>받되 로그에 남기지 않는다</b> — 판정 기록 행이 유일한
     * 보관처다(#639에서 로그를 뺀 자리, ADR-0035 결정 2). {@code layer}·{@code baseline_source}는 계속 받지 않는다.
     * 영속화 범위는 LLD-0019를 따른다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AiHeartRateResponse(
            Boolean alert,
            String level,
            String reason,
            @JsonProperty("sample_count") Integer sampleCount
    ) {
    }
}
