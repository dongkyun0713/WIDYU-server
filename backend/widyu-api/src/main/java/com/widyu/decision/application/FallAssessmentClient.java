package com.widyu.decision.application;

import com.widyu.global.properties.AiProperties;
import com.widyu.global.properties.SensorProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** 모델 배포 전 임시 낙상 AI HTTP 입구(LLD-0051 3절). */
@Component
@RequiredArgsConstructor
public class FallAssessmentClient {

    private final RestTemplate aiRestTemplate;
    private final AiProperties aiProperties;
    private final SensorProperties sensorProperties;

    @SuppressWarnings("unchecked")
    public Result assess(Map<String, Object> body) throws RestClientException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> response = aiRestTemplate.postForObject(
                aiProperties.server().url() + sensorProperties.fallAi().path(),
                new HttpEntity<>(body, headers), Map.class);
        if (response == null || !(response.get("decision") instanceof String decision)
                || !(response.get("decider_id") instanceof String deciderId)
                || !(response.get("decider_version") instanceof String deciderVersion)) {
            throw new IllegalArgumentException("fall AI response is invalid");
        }
        Object severity = response.get("severity");
        Object triggerPath = response.get("trigger_path");
        return new Result(decision, deciderId, deciderVersion,
                optionalString(severity), optionalString(triggerPath));
    }

    private String optionalString(Object value) {
        if (value instanceof String string) {
            return string;
        }
        return null;
    }

    public record Result(String decision, String deciderId, String deciderVersion, String severity, String triggerPath) {}
}
