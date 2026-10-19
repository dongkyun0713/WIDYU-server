package com.widyu.sensor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.websocket.WebSocketWorkDispatcher;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import java.io.IOException;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * 평시 1초 배치를 받는 WebSocket 수신 경로(LLD-0041 3.2·5.2).
 * REST와 같이 원문 바이트를 그대로 서비스에 넘긴다. 재직렬화 지점을 두면 S3에 저장한 내용이
 * 앱이 보낸 본문과 바이트 단위로 달라진다(지시서 B2).
 * 원시 센서값은 어떤 로그 레벨에도 남기지 않는다(정책 1.6.7).
 * S3 업로드가 최대 5초 걸리므로 센서 전용 실행기에서 처리하고 ACK는 처리 후 발신 세션에만 보낸다(LLD-0091).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SensorBatchWebSocketController {

    private static final String API = "SensorBatchWebSocketController.ingestBatch";
    private static final String ACK_DESTINATION = "/queue/sensor/result";

    private final SensorBatchService sensorBatchService;
    private final ObjectMapper objectMapper;
    private final WebSocketWorkDispatcher workDispatcher;
    private final TaskExecutor websocketSensorExecutor;

    @MessageMapping("/sensor/batches/send")
    public void ingestBatch(
            Message<byte[]> message,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        Long memberId = resolveMemberId(headerAccessor.getUser());
        String sessionId = headerAccessor.getSessionId();
        byte[] payload = message.getPayload();

        workDispatcher.dispatch(websocketSensorExecutor, API, headerAccessor, () -> workDispatcher.sendToSession(
                memberId.toString(), sessionId, ACK_DESTINATION, process(memberId, payload)));
    }

    /**
     * 센서 실행기에서 돈다. 저장 결과 또는 검증 실패 {@code REJECTED} ACK를 돌려주고,
     * 그 밖의 예외는 던져 발신 세션 /user/queue/errors로 가게 한다.
     */
    public SensorBatchResultResponse process(Long memberId, byte[] payload) {
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

    /**
     * STOMP 요청의 회원은 CONNECT가 핸드셰이크 회원으로 등록한 Principal로만 식별한다(LLD-0088).
     */
    private Long resolveMemberId(Principal user) {
        if (user instanceof Authentication authentication
                && authentication.getPrincipal() instanceof PrincipalDetails principal) {
            return principal.getMemberId();
        }
        throw new IllegalStateException("인증 정보가 없습니다. WebSocket 연결 시 유효한 JWT 토큰이 필요합니다.");
    }
}
