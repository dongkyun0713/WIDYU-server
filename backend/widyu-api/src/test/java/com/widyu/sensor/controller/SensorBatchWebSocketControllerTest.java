package com.widyu.sensor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import com.widyu.sensor.application.SensorBatchService;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

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
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SensorBatchWebSocketController sensorBatchWebSocketController;

    @Test
    @DisplayName("유효한 배치를 전송하면 서비스가 반환한 저장 결과를 그대로 ACK로 응답한다")
    void 유효한_배치를_전송하면_저장_결과를_응답한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        given(sensorBatchService.ingest(memberId, payload))
                .willReturn(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED));

        // when
        SensorBatchResultResponse response = sensorBatchWebSocketController.ingestBatch(
                message(payload), principal, headerAccessor(null));

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
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        willThrow(new BusinessException(ErrorCode.SENSOR_BATCH_INVALID))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when
        SensorBatchResultResponse response = sensorBatchWebSocketController.ingestBatch(
                message(payload), principal, headerAccessor(null));

        // then
        assertThat(response.batchId()).isEqualTo(BATCH_ID);
        assertThat(response.seq()).isEqualTo(SEQ);
        assertThat(response.result()).isEqualTo(SensorBatchResult.REJECTED);
    }

    @Test
    @DisplayName("S3 업로드에 실패하면 ACK 없이 예외를 그대로 전파한다")
    void S3_업로드에_실패하면_예외를_전파한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when & then
        assertThatThrownBy(() -> sensorBatchWebSocketController.ingestBatch(
                message(payload), principal, headerAccessor(null)))
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
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        willThrow(new BusinessException(ErrorCode.SENSOR_PAYLOAD_INVALID))
                .given(sensorBatchService).ingest(any(Long.class), any(byte[].class));

        // when & then
        assertThatThrownBy(() -> sensorBatchWebSocketController.ingestBatch(
                message(payload), principal, headerAccessor(null)))
                .isInstanceOf(BusinessException.class)
                .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
                .isEqualTo(ErrorCode.SENSOR_PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("Principal이 없으면 세션 속성의 회원 ID로 배치를 저장한다")
    void Principal이_없으면_세션_속성의_회원_ID로_저장한다() {
        // given
        Long memberId = 42L;
        byte[] payload = BATCH_JSON.getBytes(StandardCharsets.UTF_8);
        given(sensorBatchService.ingest(memberId, payload))
                .willReturn(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED));

        // when
        SensorBatchResultResponse response = sensorBatchWebSocketController.ingestBatch(
                message(payload), null, headerAccessor(memberId));

        // then
        assertThat(response.result()).isEqualTo(SensorBatchResult.STORED);
    }

    private Message<byte[]> message(byte[] payload) {
        return MessageBuilder.withPayload(payload).build();
    }

    private SimpMessageHeaderAccessor headerAccessor(Long sessionMemberId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("sensor-session-1");
        if (sessionMemberId != null) {
            accessor.setSessionAttributes(Map.of("memberId", sessionMemberId));
        }
        return accessor;
    }
}
