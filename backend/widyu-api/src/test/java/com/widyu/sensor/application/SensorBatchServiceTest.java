package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.GyroMode;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.SensorBatchKind;
import com.widyu.sensor.SensorStreamType;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SensorBatchService 단위 테스트")
class SensorBatchServiceTest {

    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private S3Service s3Service;

    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SensorBatchService sensorBatchService;

    @Test
    @DisplayName("유효한 배치를 저장하면 STORED와 함께 측정 범위·해시가 담긴 인덱스 행을 남긴다")
    void 유효한_배치를_저장하면_STORED와_함께_측정_범위와_해시가_담긴_인덱스_행을_남긴다() {
        // given
        Long memberId = 1L;
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> samples = List.of(
                accelSample(mapper, 1758150000143L, -10, 982, 44),
                accelSample(mapper, 1758150000123L, -12, 980, 45)
        );
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE,
                GyroMode.CONTINUOUS, true, samples);
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(false);
        long before = System.currentTimeMillis();

        // when
        SensorBatchResultResponse response = sensorBatchService.ingest(memberId, request);

        // then
        assertThat(response).isEqualTo(SensorBatchResultResponse.of(1234L, SensorBatchResult.STORED));

        ArgumentCaptor<SensorBatch> saved = ArgumentCaptor.forClass(SensorBatch.class);
        then(sensorBatchRepository).should().save(saved.capture());
        SensorBatch batch = saved.getValue();
        byte[] expectedPayload = serialize(mapper, request);
        assertThat(batch.getMember()).isEqualTo(member);
        assertThat(batch.getStreamType()).isEqualTo(SensorStreamType.WATCH_ACCEL);
        assertThat(batch.getBatchKind()).isEqualTo(SensorBatchKind.LIVE);
        assertThat(batch.getGyroMode()).isEqualTo(GyroMode.CONTINUOUS);
        assertThat(batch.getOnBody()).isTrue();
        assertThat(batch.getMeasuredFromMs()).isEqualTo(1758150000123L);
        assertThat(batch.getMeasuredToMs()).isEqualTo(1758150000143L);
        assertThat(batch.getSampleCount()).isEqualTo(2);
        assertThat(batch.getReceivedAtMs()).isBetween(before, System.currentTimeMillis());
        assertThat(batch.getS3Key())
                .isEqualTo("sensor/1/watch-3f2a/s-20260918-01/WATCH_ACCEL/1234.json");
        assertThat(batch.getByteSize()).isEqualTo(expectedPayload.length);
        assertThat(batch.getSha256()).isEqualTo(sha256Hex(expectedPayload));

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        then(s3Service).should().uploadBytes(
                eq("sensor/1/watch-3f2a/s-20260918-01/WATCH_ACCEL/1234.json"),
                uploaded.capture(),
                eq("application/json"));
        assertThat(uploaded.getValue()).isEqualTo(expectedPayload);
    }

    @Test
    @DisplayName("같은 키의 배치가 이미 있으면 DUPLICATE를 반환하고 S3에 올리지 않는다")
    void 같은_키의_배치가_이미_있으면_DUPLICATE를_반환하고_S3에_올리지_않는다() {
        // given
        Long memberId = 1L;
        ObjectMapper mapper = new ObjectMapper();
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.RETRANSMIT,
                GyroMode.TRIGGER, true, List.of(accelSample(mapper, 1758150000123L, -12, 980, 45)));
        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(true);

        // when
        SensorBatchResultResponse response = sensorBatchService.ingest(memberId, request);

        // then
        assertThat(response).isEqualTo(SensorBatchResultResponse.of(1234L, SensorBatchResult.DUPLICATE));
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("저장 중 유니크 키 경합이 나면 DUPLICATE를 반환한다")
    void 저장_중_유니크_키_경합이_나면_DUPLICATE를_반환한다() {
        // given
        Long memberId = 1L;
        ObjectMapper mapper = new ObjectMapper();
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE,
                GyroMode.CONTINUOUS, true, List.of(accelSample(mapper, 1758150000123L, -12, 980, 45)));
        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(false);
        given(sensorBatchRepository.save(any(SensorBatch.class)))
                .willThrow(new DataIntegrityViolationException("uk_sensor_batch_seq"));

        // when
        SensorBatchResultResponse response = sensorBatchService.ingest(memberId, request);

        // then
        assertThat(response).isEqualTo(SensorBatchResultResponse.of(1234L, SensorBatchResult.DUPLICATE));
    }

    @Test
    @DisplayName("재직렬화한 배치가 32KB를 넘으면 예외가 발생하고 아무것도 저장하지 않는다")
    void 재직렬화한_배치가_32KB를_넘으면_예외가_발생하고_아무것도_저장하지_않는다() {
        // given
        Long memberId = 1L;
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> samples = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            samples.add(accelSample(mapper, 1758150000000L + i, -123456, 987654, -456789));
        }
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE, GyroMode.CONTINUOUS, true, samples);
        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> sensorBatchService.ingest(memberId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_BATCH_TOO_LARGE);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("샘플에 정수 측정 시각이 없으면 예외가 발생하고 아무것도 저장하지 않는다")
    void 샘플에_정수_측정_시각이_없으면_예외가_발생하고_아무것도_저장하지_않는다() {
        // given
        Long memberId = 1L;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode withoutTime = mapper.createObjectNode().put("x", -12).put("y", 980).put("z", 45);
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE, GyroMode.CONTINUOUS, true,
                List.of(accelSample(mapper, 1758150000123L, -12, 980, 45), withoutTime));
        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(false);

        // when & then
        assertThatThrownBy(() -> sensorBatchService.ingest(memberId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_SAMPLE_INVALID);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("S3 업로드가 실패하면 인덱스 행을 저장하지 않는다")
    void S3_업로드가_실패하면_인덱스_행을_저장하지_않는다() {
        // given
        Long memberId = 1L;
        ObjectMapper mapper = new ObjectMapper();
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE, GyroMode.CONTINUOUS, true,
                List.of(accelSample(mapper, 1758150000123L, -12, 980, 45)));
        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
        given(sensorBatchRepository.existsByMemberIdAndDeviceIdAndSessionIdAndStreamTypeAndSeq(
                memberId, "watch-3f2a", "s-20260918-01", SensorStreamType.WATCH_ACCEL, 1234L))
                .willReturn(false);
        willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED))
                .given(s3Service).uploadBytes(anyString(), any(), anyString());

        // when & then
        assertThatThrownBy(() -> sensorBatchService.ingest(memberId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED);
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("회원이 없으면 예외가 발생한다")
    void 회원이_없으면_예외가_발생한다() {
        // given
        Long memberId = 999L;
        ObjectMapper mapper = new ObjectMapper();
        SensorBatchRequest request = SensorBatchRequest.of(
                "watch-3f2a", "s-20260918-01", 1234L,
                SensorStreamType.WATCH_ACCEL, SensorBatchKind.LIVE, GyroMode.CONTINUOUS, true,
                List.of(accelSample(mapper, 1758150000123L, -12, 980, 45)));
        given(memberRepository.findById(memberId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sensorBatchService.ingest(memberId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    private JsonNode accelSample(ObjectMapper mapper, long t, int x, int y, int z) {
        return mapper.createObjectNode().put("t", t).put("x", x).put("y", y).put("z", z);
    }

    private byte[] serialize(ObjectMapper mapper, SensorBatchRequest request) {
        try {
            return mapper.writeValueAsBytes(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
