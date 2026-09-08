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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private static final String NORMAL_REASON = "normal";

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * 측정값을 시각 오름차순으로 AI에 순차 전달한다. 배치(15개)와 단건(1개) 경로가 함께 사용한다.
     * 배치 크기 검증은 요청 DTO의 {@code @Size}가 담당한다.
     */
    @Timed("heart.ai.detection")
    public DetectionResult detect(Long memberId, List<HeartRateMeasurement> measurements, String context) {
        if (measurements.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "심박수 데이터가 비어 있습니다.");
        }

        List<HeartRateMeasurement> sortedMeasurements = measurements.stream()
                .sorted(Comparator.comparing(HeartRateMeasurement::measuredAt))
                .toList();

        long startedAt = System.nanoTime();
        List<AiHeartRateResponse> responses = new ArrayList<>();
        HeartRateStatus status = HeartRateStatus.NORMAL;
        boolean emergency = false;
        for (HeartRateMeasurement measurement : sortedMeasurements) {
            AiHeartRateResponse response = requestAnalysis(memberId, measurement, context);
            responses.add(response);
            HeartRateStatus nextStatus = parseStatus(response);
            status = higherStatus(status, nextStatus);
            if (Boolean.TRUE.equals(response.alert()) && nextStatus == HeartRateStatus.EMERGENCY) {
                emergency = true;
            }
        }

        logBatchSummary(memberId, context, status, sortedMeasurements, responses, startedAt);

        return new DetectionResult(status, emergency);
    }

    /**
     * 배치 단위로 AI 판정 근거를 남긴다. 개인화(layer=L1, baselineSource=PERSONAL)가 실제로 적용되는지와
     * AI 순차 호출이 조회 지연에 얼마나 기여하는지를 운영에서 확인하기 위한 로그다.
     * <p>
     * AI는 이상 범위가 30초를 넘게 끊김 없이 이어져야 EMERGENCY를 판정하고, 중간에 정상값이 하나라도
     * 섞이면 지속 시간을 처음부터 다시 센다. 배치 하나는 15개뿐이라 단독으로는 조건을 채울 수 없으므로,
     * "왜 이상값을 보냈는데 NORMAL인가"를 판단하려면 배치에 실제로 담겨 온 심박 분포와 측정 구간이 필요하다.
     * <p>
     * 심박 수치는 개인 건강정보이므로 어느 프로파일에서도 기본 비활성화하고, 진단이 필요한 순간에만
     * {@code LOGGING_LEVEL_COM_WIDYU_HEART=DEBUG}로 켰다가 끈다. 측정 시각은 남기지 않는다.
     * 배치가 언제 들어왔는지는 로그 자체의 타임스탬프로 확인할 수 있고, 판정에 필요한 것은 구간 길이뿐이다.
     */
    private void logBatchSummary(
            Long memberId,
            String context,
            HeartRateStatus status,
            List<HeartRateMeasurement> sortedMeasurements,
            List<AiHeartRateResponse> responses,
            long startedAt
    ) {
        if (!log.isDebugEnabled()) {
            return;
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.debug(
                "심박 배치 AI 판정: memberId={}, context={}, status={}, 소요={}ms, {}, layer={}, baselineSource={}, sampleCount={}, 이상사유={}",
                memberId,
                context,
                status,
                elapsedMillis,
                describeBatch(sortedMeasurements),
                distinctValues(responses, AiHeartRateResponse::layer),
                distinctValues(responses, AiHeartRateResponse::baselineSource),
                lastSampleCount(responses),
                abnormalReasons(responses)
        );
    }

    /**
     * 배치에 담겨 온 심박 분포와 구간 길이. 이상값이 배치 전체를 채웠는지 일부만 튀었는지 구분한다.
     * 측정 시각은 개인 활동 시간대를 드러내므로 남기지 않고 구간 길이만 계산한다.
     */
    private String describeBatch(List<HeartRateMeasurement> sortedMeasurements) {
        IntSummaryStatistics bpmStats = sortedMeasurements.stream()
                .mapToInt(HeartRateMeasurement::heartRate)
                .summaryStatistics();
        long spanSeconds = Duration.between(
                sortedMeasurements.getFirst().measuredAt(),
                sortedMeasurements.getLast().measuredAt()
        ).toSeconds();
        return String.format(
                "bpm=[%d~%d 평균%.0f], 구간=%d초",
                bpmStats.getMin(),
                bpmStats.getMax(),
                bpmStats.getAverage(),
                spanSeconds
        );
    }

    private Set<String> distinctValues(
            List<AiHeartRateResponse> responses,
            Function<AiHeartRateResponse, String> extractor
    ) {
        return responses.stream()
                .map(extractor)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> abnormalReasons(List<AiHeartRateResponse> responses) {
        return responses.stream()
                .map(AiHeartRateResponse::reason)
                .filter(Objects::nonNull)
                .filter(reason -> !NORMAL_REASON.equals(reason))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Integer lastSampleCount(List<AiHeartRateResponse> responses) {
        if (responses.isEmpty()) {
            return null;
        }
        return responses.getLast().sampleCount();
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
            log.error("AI 서버 호출 실패: url={}, error={}", url, e.getMessage(), e);
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

    private HeartRateStatus higherStatus(HeartRateStatus current, HeartRateStatus next) {
        if (current == HeartRateStatus.EMERGENCY || next == HeartRateStatus.EMERGENCY) {
            return HeartRateStatus.EMERGENCY;
        }
        if (current == HeartRateStatus.CAUTION || next == HeartRateStatus.CAUTION) {
            return HeartRateStatus.CAUTION;
        }
        return HeartRateStatus.NORMAL;
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
