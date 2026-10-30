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
        // 실행기 대기 전에 잡아야 server_received가 실제 수신 시각이 된다(정책 1.1.5).
        long serverReceivedAtMs = System.currentTimeMillis();
        Long memberId = resolveMemberId(headerAccessor.getUser());
        String sessionId = headerAccessor.getSessionId();
        byte[] payload = message.getPayload();

        workDispatcher.dispatch(websocketSensorExecutor, API, headerAccessor, () -> workDispatcher.sendToSession(
                memberId.toString(), sessionId, ACK_DESTINATION, process(memberId, payload, serverReceivedAtMs)));
    }

    /**
     * 센서 실행기에서 돈다. 저장 결과 또는 검증 실패 {@code REJECTED} ACK를 돌려주고,
     * 그 밖의 예외는 던져 발신 세션 /user/queue/errors로 가게 한다.
     */
    public SensorBatchResultResponse process(Long memberId, byte[] payload, long serverReceivedAtMs) {
        try {
            return sensorBatchService.ingest(memberId, payload, serverReceivedAtMs);
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
        // 거절 이유는 에러 코드로만 남긴다. 원문·샘플 값은 어떤 레벨에도 남기지 않는다(정책 1.6.7).
        String code = cause.getErrorCode().getCode();
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException e) {
            // 예외 메시지에 샘플 값이 섞일 수 있어 어떤 레벨에도 남기지 않는다.
            log.warn("센서 배치 거절(본문 해석 불가): memberId={}, code={}", memberId, code);
            throw cause;
        }

        String stream = streamOf(root);
        JsonNode batchId = root.get("batch_id");
        JsonNode seq = root.get("seq");
        if (batchId == null || !batchId.isTextual() || seq == null || !seq.canConvertToLong()) {
            log.warn("센서 배치 거절(식별자 없음): memberId={}, stream={}, code={}", memberId, stream, code);
            throw cause;
        }

        log.warn("센서 배치 거절: memberId={}, batchId={}, seq={}, stream={}, code={}, result={}",
                memberId, batchId.asText(), seq.asLong(), stream, code, SensorBatchResult.REJECTED);
        return SensorBatchResultResponse.of(batchId.asText(), seq.asLong(), SensorBatchResult.REJECTED);
    }

    /** 로그용 stream. 기기가 보낸 값이라 짧은 소문자 이름만 그대로 남기고 나머지는 가린다(로그 주입 방지). */
    private static String streamOf(JsonNode root) {
        JsonNode stream = root.get("stream");
        if (stream == null || !stream.isTextual()) {
            return null;
        }
        if (!stream.asText().matches("[a-z0-9_]{1,32}")) {
            return "invalid";
        }
        return stream.asText();
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
