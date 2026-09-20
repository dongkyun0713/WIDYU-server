package com.widyu.sensor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

/**
 * 평시 1초 배치를 받는 WebSocket 수신 경로(LLD-0041 3.2·5.2).
 * REST와 같이 원문 바이트를 그대로 서비스에 넘긴다. 재직렬화 지점을 두면 S3에 저장한 내용이
 * 앱이 보낸 본문과 바이트 단위로 달라진다(지시서 B2).
 * 원시 센서값은 어떤 로그 레벨에도 남기지 않는다(정책 1.6.7).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SensorBatchWebSocketController {

    private final SensorBatchService sensorBatchService;
    private final ObjectMapper objectMapper;

    @MessageMapping("/sensor/batches/send")
    @SendToUser(destinations = "/queue/sensor/result", broadcast = false)
    public SensorBatchResultResponse ingestBatch(
            Message<byte[]> message,
            @AuthenticationPrincipal PrincipalDetails principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Long memberId = resolveMemberId(principal, headerAccessor);
        byte[] payload = message.getPayload();

        try {
            return sensorBatchService.ingest(memberId, payload);
        } catch (BusinessException e) {
            if (ErrorCode.FILE_UPLOAD_FAILED.equals(e.getErrorCode())) {
                // S3 실패는 재전송으로 복구된다. ACK 없이 /user/queue/errors로 보낸다.
                throw e;
            }
            return rejected(memberId, payload, e);
        }
    }

    /**
     * 검증 실패 ACK에 실을 식별자만 원문에서 얕게 꺼낸다. 정상 경로는 서비스가 파싱하므로 여기까지 오지 않는다.
     * {@code batch_id}·{@code seq}를 읽지 못하면 ACK를 만들 수 없어 원래 예외를 그대로 던진다(→ /user/queue/errors).
     */
    private SensorBatchResultResponse rejected(Long memberId, byte[] payload, BusinessException cause) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException e) {
            // 예외 메시지에 샘플 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다.
            throw cause;
        }

        JsonNode batchId = root.get("batch_id");
        if (batchId == null || !batchId.isTextual()) {
            throw cause;
        }

        JsonNode seq = root.get("seq");
        if (seq == null || !seq.canConvertToLong()) {
            throw cause;
        }

        log.warn("센서 배치 거절: memberId={}, batchId={}, seq={}, result={}",
                memberId, batchId.asText(), seq.asLong(), SensorBatchResult.REJECTED);
        return SensorBatchResultResponse.of(batchId.asText(), seq.asLong(), SensorBatchResult.REJECTED);
    }

    private Long resolveMemberId(PrincipalDetails principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal != null && principal.getMemberId() != null) {
            return principal.getMemberId();
        }

        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("memberId") instanceof Long memberId) {
            log.debug("세션 속성에서 memberId 조회 - memberId: {}", memberId);
            return memberId;
        }

        throw new IllegalStateException("인증 정보가 없습니다. WebSocket 연결 시 유효한 JWT 토큰이 필요합니다.");
    }
}
