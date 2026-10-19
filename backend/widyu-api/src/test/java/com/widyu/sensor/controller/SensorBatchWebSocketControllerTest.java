package com.widyu.sensor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.websocket.WebSocketExceptionHandler;
import com.widyu.global.websocket.WebSocketWorkDispatcher;
import com.widyu.member.MemberRole;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
@DisplayName("SensorBatchWebSocketController 단위 테스트")
class SensorBatchWebSocketControllerTest {

    private static final String BATCH_ID = "01j8zk3v9x2q4m7n8p1r5s6t7u";
    private static final long SEQ = 88213L;

    /** 컨트롤러는 식별자 두 개만 얕게 읽으므로 부록 A 본문에서 그 부분만 옮겼다. */
    private static final String BATCH_JSON = """
            {
              "v": 2,
              "stream": "imu_watch",
              "batch_id": "01j8zk3v9x2q4m7n8p1r5s6t7u",
              "seq": 88213
            }""";

    @Mock private SensorBatchService sensorBatchService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("유효한 배치를 전송하면 서비스가 반환한 저장 결과를 그대로 ACK로 응답한다")
    void 유효한_배치를_전송하면_저장_결과를_응답한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        given(sensorBatchService.ingest(memberId, payload))
                .willReturn(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED));

        // when
        SensorBatchResultResponse response = controller().process(memberId, payload);

        // then
        assertThat(response.batchId()).isEqualTo(BATCH_ID);
        assertThat(response.seq()).isEqualTo(SEQ);
        assertThat(response.result()).isEqualTo(SensorBatchResult.STORED);
    }

    @Test
    @DisplayName("배치 검증에 실패하면 원문에서 식별자를 꺼내 REJECTED ACK로 응답한다")
    void 배치_검증에_실패하면_원문_식별자로_REJECTED를_응답한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        willThrow(new BusinessException(ErrorCode.SENSOR_BATCH_INVALID))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when
        SensorBatchResultResponse response = controller().process(memberId, payload);

        // then
        assertThat(response.batchId()).isEqualTo(BATCH_ID);
        assertThat(response.seq()).isEqualTo(SEQ);
        assertThat(response.result()).isEqualTo(SensorBatchResult.REJECTED);
    }

    @Test
    @DisplayName("처리 중 S3 업로드에 실패하면 ACK 없이 예외를 그대로 전파한다")
    void S3_업로드에_실패하면_예외를_전파한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when & then
        assertThatThrownBy(() -> controller().process(memberId, payload))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("검증에 실패한 원문이 JSON이 아니면 ACK 없이 예외를 그대로 전파한다")
    void 검증에_실패한_원문이_JSON이_아니면_예외를_전파한다() {
        // given
        Long memberId = 42L;
        byte[] payload = "not-json".getBytes(StandardCharsets.UTF_8);
        willThrow(new BusinessException(ErrorCode.SENSOR_PAYLOAD_INVALID))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when & then
        assertThatThrownBy(() -> controller().process(memberId, payload))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.SENSOR_PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("Principal 없이 배치를 전송하면 저장하지 않고 예외가 발생한다")
    void Principal_없이_배치를_전송하면_예외가_발생한다() {
        // given
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);

        // when / then
        assertThatThrownBy(() -> controller().ingestBatch(message(payload), headerAccessor(null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sensorBatchService);
    }

    @Test
    @DisplayName("배치를 받으면 센서 실행기에서 처리한 저장 결과를 발신 세션에만 ACK로 보낸다")
    void 배치를_받으면_처리한_저장_결과를_발신_세션에만_ACK로_보낸다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        SensorBatchResultResponse stored = SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED);
        given(sensorBatchService.ingest(memberId, payload)).willReturn(stored);
        ArgumentCaptor<Map<String, Object>> headers = ArgumentCaptor.captor();

        // when
        controller().ingestBatch(message(payload), headerAccessor(principal));

        // then
        then(messagingTemplate).should().convertAndSendToUser(
                eq("42"), eq("/queue/sensor/result"), same(stored), headers.capture());
        assertThat(headers.getValue()).containsEntry(SimpMessageHeaderAccessor.SESSION_ID_HEADER, "sensor-session-1");
    }

    @Test
    @DisplayName("센서 실행기에서 S3 업로드에 실패하면 ACK 대신 발신 세션 오류 큐로 서버 오류를 보낸다")
    void S3_업로드에_실패하면_발신_세션_오류_큐로_서버_오류를_보낸다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));
        ArgumentCaptor<Object> body = ArgumentCaptor.captor();

        // when
        controller().ingestBatch(message(payload), headerAccessor(principal));

        // then
        then(messagingTemplate).should().convertAndSendToUser(
                eq("42"), eq("/queue/errors"), body.capture(), anyMap());
        then(messagingTemplate).should(never()).convertAndSendToUser(
                eq("42"), eq("/queue/sensor/result"), any(), anyMap());
        assertThat(body.getValue()).isEqualTo(Map.of(
                "error", "BusinessException", "message", ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
    }

    private SensorBatchWebSocketController controller() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebSocketWorkDispatcher dispatcher =
                new WebSocketWorkDispatcher(messagingTemplate, new WebSocketExceptionHandler(), registry);
        return new SensorBatchWebSocketController(sensorBatchService, new ObjectMapper(), dispatcher,
                new SyncTaskExecutor());
    }

    private Message<byte[]> message(byte[] payload) {
        return MessageBuilder.withPayload(payload).build();
    }

    private SimpMessageHeaderAccessor headerAccessor(PrincipalDetails principal) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("sensor-session-1");
        if (principal != null) {
            accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        }
        return accessor;
    }
}
