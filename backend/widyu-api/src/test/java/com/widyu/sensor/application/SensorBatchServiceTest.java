package com.widyu.sensor.application;

import static com.widyu.sensor.application.SensorBatchFixture.ACC;
import static com.widyu.sensor.application.SensorBatchFixture.BATCH_ID;
import static com.widyu.sensor.application.SensorBatchFixture.GYRO;
import static com.widyu.sensor.application.SensorBatchFixture.RESEND;
import static com.widyu.sensor.application.SensorBatchFixture.SEQ;
import static com.widyu.sensor.application.SensorBatchFixture.batch;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.properties.SensorProperties;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.dto.response.SensorBatchResult;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import com.widyu.sensor.repository.SensorBatchRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("SensorBatchService 단위 테스트")
class SensorBatchServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private ClockMappingService clockMappingService;
    @Mock private com.widyu.heart.application.HeartRateBatchService heartRateBatchService;
    @Mock private com.widyu.decision.application.FallAssessmentService fallAssessmentService;
    @Mock private com.widyu.run.application.CollectionRunService collectionRunService;
    @Mock private MemberRepository memberRepository;
    @Mock private S3Service s3Service;

    @Test
    @DisplayName("부록 A 배치를 저장하면 원문 바이트와 해시·환산 시각이 그대로 담긴 행을 남긴다")
    void 부록_A_배치를_저장하면_원문_바이트와_해시와_환산_시각이_그대로_담긴_행을_남긴다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        byte[] payload = batch(ACC, "null").getBytes(UTF_8);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        long before = System.currentTimeMillis();
        String expectedKey = expectedKey(payload);

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response)
                .isEqualTo(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED));

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        then(s3Service).should()
                .uploadBytes(eq(expectedKey), uploaded.capture(), eq("application/json"));
        assertThat(uploaded.getValue()).isEqualTo(payload);

        SensorBatch saved = savedBatch();
        assertThat(saved.getBatchId()).isEqualTo(BATCH_ID);
        assertThat(saved.getMember()).isEqualTo(member);
        assertThat(saved.getStream()).isEqualTo("imu_watch");
        assertThat(saved.getSource()).isEqualTo("watch");
        assertThat(saved.getS3Key()).isEqualTo(expectedKey);
        assertThat(saved.getByteSize()).isEqualTo(payload.length);
        assertThat(saved.getPayloadSha256()).isEqualTo(sha256Hex(payload));
        assertThat(saved.getQualityStatus()).isEqualTo("OK");

        // 시계 다섯 값은 원본 그대로 남는다(정책 1.1.7).
        assertThat(saved.getBootId()).isEqualTo("b7c1");
        assertThat(saved.getClockMappingId()).isEqualTo("cm-01");
        assertThat(saved.getAnchorElapsedNs()).isEqualTo(993_847_100_000_001L);
        assertThat(saved.getAnchorEpochMs()).isEqualTo(1_760_000_000_000L);
        assertThat(saved.getUncertaintyMs()).isEqualTo(2.0);

        // 환산(4.3): 기준점에서 12,340,000ns 뒤 시작, dt 합 40,001,422ns 뒤 종료.
        assertThat(saved.getMeasuredAtStartMs()).isEqualTo(1_760_000_000_012L);
        assertThat(saved.getMeasuredAtEndMs()).isEqualTo(1_760_000_000_052L);
        assertThat(saved.getAccN()).isEqualTo(3);
        assertThat(saved.getAccT0ElapsedNs()).isEqualTo(993_847_112_340_001L);
        assertThat(saved.getAccFsHzRequested()).isEqualTo(50.0);

        // 시각 단계는 합치지 않는다(지시서 B3).
        assertThat(saved.getServerReceivedAtMs()).isBetween(before, System.currentTimeMillis());
        assertThat(saved.getAcceptedAtMs()).isGreaterThanOrEqualTo(saved.getServerReceivedAtMs());
        assertThat(saved.getPersistedAtMs()).isGreaterThanOrEqualTo(saved.getAcceptedAtMs());
        assertThat(saved.getModelAvailableAtServerMs()).isEqualTo(saved.getPersistedAtMs());
        // 잰 시각이 받은 시각보다 앞선다. 늦게 도착한 자료도 잰 시각을 소급하지 않는다(C5·C6).
        assertThat(saved.getMeasuredAtEndMs()).isLessThan(saved.getServerReceivedAtMs());
    }

    @Test
    @DisplayName("자이로가 null이면 자이로 축 컬럼이 null로 남고 0으로 채워지지 않는다")
    void 자이로가_null이면_자이로_축_컬럼이_null로_남고_0으로_채워지지_않는다() {
        // given
        givenMemberExists();

        // when
        service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getGyroN()).isNull();
        assertThat(saved.getGyroT0ElapsedNs()).isNull();
        assertThat(saved.getGyroFsHzRequested()).isNull();
        assertThat(saved.getAccN()).isEqualTo(3);
    }

    @Test
    @DisplayName("충격 배치의 판정 훅이 실패해도 배치 저장 결과는 STORED다")
    void 충격_배치의_판정_훅이_실패해도_배치_저장_결과는_STORED다() {
        // given
        givenMemberExists();
        String trigger = """
                {"kind":"impact","smv_g":3.0,"event_elapsed_ns":"993847112340001","ts_ms":1760000000012}
                """;
        willThrow(new IllegalStateException("AI unavailable"))
                .given(fallAssessmentService).assessAfterImpact(any(SensorBatch.class));

        // when
        SensorBatchResultResponse response = service().ingest(
                MEMBER_ID, batch(ACC, "null", trigger, "false", "null", "null").getBytes(UTF_8));

        // then
        assertThat(response).isEqualTo(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.STORED));
        then(sensorBatchRepository).should().save(any(SensorBatch.class));
        then(fallAssessmentService).should().assessAfterImpact(any(SensorBatch.class));
    }

    @Test
    @DisplayName("자이로가 함께 오면 두 축의 시각 범위를 합쳐 환산한다")
    void 자이로가_함께_오면_두_축의_시각_범위를_합쳐_환산한다() {
        // given
        givenMemberExists();

        // when
        service().ingest(MEMBER_ID, batch(ACC, GYRO).getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getGyroN()).isEqualTo(2);
        assertThat(saved.getGyroT0ElapsedNs()).isEqualTo(993_847_110_000_001L);
        // 자이로가 10ms 먼저 시작하고 가속도가 52ms에 끝난다.
        assertThat(saved.getMeasuredAtStartMs()).isEqualTo(1_760_000_000_010L);
        assertThat(saved.getMeasuredAtEndMs()).isEqualTo(1_760_000_000_052L);
    }

    @Test
    @DisplayName("같은 batch_id가 이미 있으면 DUPLICATE를 반환하고 S3에 올리지 않는다")
    void 같은_batch_id가_이미_있으면_DUPLICATE를_반환하고_S3에_올리지_않는다() {
        // given
        givenMemberExists();
        byte[] payload = batch(ACC, "null").getBytes(UTF_8);
        given(sensorBatchRepository.findByBatchId(BATCH_ID))
                .willReturn(Optional.of(storedBatchWithHash(payload)));

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response)
                .isEqualTo(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.DUPLICATE));
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("같은 batch_id에 다른 원문이 이미 있으면 중복으로 승인하지 않는다")
    void 같은_batch_id에_다른_원문이_이미_있으면_중복으로_승인하지_않는다() {
        // given
        givenMemberExists();
        byte[] payload = batch(ACC, "null").getBytes(UTF_8);
        given(sensorBatchRepository.findByBatchId(BATCH_ID))
                .willReturn(Optional.of(storedBatchWithHash("different".getBytes(UTF_8))));

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, payload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_BATCH_INVALID);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("같은 batch_id의 다른 원문이 경합해도 각 행 후보는 자신이 올린 불변 객체를 가리킨다")
    void 같은_batch_id의_다른_원문이_경합해도_각_행_후보는_자신이_올린_불변_객체를_가리킨다() {
        // given
        givenMemberExists();
        byte[] first = batch(ACC, "null").getBytes(UTF_8);
        byte[] second = batch(ACC, "null")
                .replace("\n}", ",\n  \"future\": true\n}")
                .getBytes(UTF_8);

        // when
        service().ingest(MEMBER_ID, first);
        service().ingest(MEMBER_ID, second);

        // then
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        then(s3Service).should(org.mockito.Mockito.times(2))
                .uploadBytes(keys.capture(), payloads.capture(), eq("application/json"));
        assertThat(keys.getAllValues()).containsExactly(expectedKey(first), expectedKey(second));
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();

        ArgumentCaptor<SensorBatch> rows = ArgumentCaptor.forClass(SensorBatch.class);
        then(sensorBatchRepository).should(org.mockito.Mockito.times(2)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(SensorBatch::getPayloadSha256)
                .containsExactly(sha256Hex(first), sha256Hex(second));
        assertThat(rows.getAllValues())
                .extracting(SensorBatch::getS3Key)
                .containsExactlyElementsOf(keys.getAllValues());
    }

    @Test
    @DisplayName("저장 중 유니크 키 경합이 나면 재조회로 확인하고 DUPLICATE를 반환한다")
    void 저장_중_유니크_키_경합이_나면_재조회로_확인하고_DUPLICATE를_반환한다() {
        // given
        givenMemberExists();
        byte[] payload = batch(ACC, "null").getBytes(UTF_8);
        given(sensorBatchRepository.findByBatchId(BATCH_ID))
                .willReturn(Optional.empty(), Optional.of(storedBatchWithHash(payload)));
        given(sensorBatchRepository.save(any(SensorBatch.class)))
                .willThrow(new DataIntegrityViolationException("uk_sensor_batch_batch_id"));

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response)
                .isEqualTo(SensorBatchResultResponse.of(BATCH_ID, SEQ, SensorBatchResult.DUPLICATE));
    }

    @Test
    @DisplayName("무결성 오류 뒤에도 같은 batch_id 행이 없으면 원래 예외가 전파된다")
    void 무결성_오류_뒤에도_같은_batch_id_행이_없으면_원래_예외가_전파된다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        given(sensorBatchRepository.save(any(SensorBatch.class)))
                .willThrow(new DataIntegrityViolationException("fk_sensor_batch_member"));

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("나노초 시각이 JSON 숫자로 오면 본문 오류로 거부한다")
    void 나노초_시각이_JSON_숫자로_오면_본문_오류로_거부한다() {
        // given
        givenMemberExists();
        String numericT0 = """
                {
                    "fs_hz_requested": 50,
                    "n": 3,
                    "t0_elapsed_ns": 993847112340001,
                    "dt_ns": [19998417, 20003005],
                    "mg": [[20, -980, 110], [22, -979, 108], [21, -981, 109]]
                  }""";

        // when & then
        assertRejected(batch(numericT0, "null"), ErrorCode.SENSOR_PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("dt_ns 길이가 n보다 하나 적지 않으면 축 오류로 거부한다")
    void dt_ns_길이가_n보다_하나_적지_않으면_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String shortDt = SensorBatchFixture.acc("3", "[19998417]", "[[20, -980, 110], [22, -979, 108], [21, -981, 109]]");

        // when & then
        assertRejected(batch(shortDt, "null"), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("값 배열이 n행 3열이 아니면 축 오류로 거부한다")
    void 값_배열이_n행_3열이_아니면_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String twoColumns = SensorBatchFixture.acc("3", "[19998417, 20003005]", "[[20, -980], [22, -979], [21, -981]]");

        // when & then
        assertRejected(batch(twoColumns, "null"), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("dt_ns에 0이 있으면 축 오류로 거부한다")
    void dt_ns에_0이_있으면_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String zeroDt = SensorBatchFixture.acc("3", "[0, 20003005]", "[[20, -980, 110], [22, -979, 108], [21, -981, 109]]");

        // when & then
        assertRejected(batch(zeroDt, "null"), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("dt_ns가 uint32를 넘으면 축 오류로 거부한다")
    void dt_ns가_uint32를_넘으면_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String overflowed = SensorBatchFixture.acc("3", "[4294967296, 20003005]", "[[20, -980, 110], [22, -979, 108], [21, -981, 109]]");

        // when & then
        assertRejected(batch(overflowed, "null"), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("마지막 경과 시각 덧셈이 long 범위를 넘으면 업로드 전에 축 오류로 거부한다")
    void 마지막_경과_시각_덧셈이_long_범위를_넘으면_업로드_전에_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String overflowed = """
                {
                    "fs_hz_requested": 50,
                    "n": 2,
                    "t0_elapsed_ns": "9223372036854775807",
                    "dt_ns": [1],
                    "mg": [[20, -980, 110], [22, -979, 108]]
                  }""";

        // when & then
        assertRejected(batch(overflowed, "null"), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("epoch 환산 덧셈이 long 범위를 넘으면 업로드 전에 축 오류로 거부한다")
    void epoch_환산_덧셈이_long_범위를_넘으면_업로드_전에_축_오류로_거부한다() {
        // given
        givenMemberExists();
        String overflowed = batch(ACC, "null")
                .replace("\"anchor_elapsed_ns\": \"993847100000001\"", "\"anchor_elapsed_ns\": \"0\"")
                .replace("\"anchor_epoch_ms\": 1760000000000", "\"anchor_epoch_ms\": 9223372036854775807");

        // when & then
        assertRejected(overflowed, ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("보강 배치인데 원 배치를 가리키지 않으면 필드 오류로 거부한다")
    void 보강_배치인데_원_배치를_가리키지_않으면_필드_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertRejected(batch(ACC, GYRO, "null", "true", "null", "null"), ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("보강 대상 배열에 문자열이 아닌 값이 있으면 필드 오류로 거부한다")
    void 보강_대상_배열에_문자열이_아닌_값이_있으면_필드_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertRejected(
                batch("null", GYRO, "null", "true", "[\"01j8y000000000000000000000\", 1]", "null"),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("트리거 종류가 저장 길이를 넘으면 필드 오류로 거부한다")
    void 트리거_종류가_저장_길이를_넘으면_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String trigger = """
                {"kind": "123456789012345678901", "smv_g": 2.0,
                 "event_elapsed_ns": "993847112340001", "ts_ms": 1760000000012}""";

        // when & then
        assertRejected(batch(ACC, "null", trigger, "false", "null", "null"),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("재전송 계보 6필드 중 하나가 빠지면 필드 오류로 거부한다")
    void 재전송_계보_6필드_중_하나가_빠지면_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String withoutOriginalSeq = """
                {
                    "is_resend": true,
                    "original_batch_id": "01j8y000000000000000000000",
                    "original_run_id": "run-01",
                    "original_session_id": "s-20260919-00",
                    "resent_at_ms": 1760000180000
                  }""";

        // when & then
        assertRejected(
                batch(ACC, "null", "null", "false", "null", withoutOriginalSeq),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("재전송 계보 6필드를 갖추면 그대로 컬럼에 저장한다")
    void 재전송_계보_6필드를_갖추면_그대로_컬럼에_저장한다() {
        // given
        givenMemberExists();
        given(collectionRunService.requireAttributableRun(eq("run-01"), eq(MEMBER_ID), eq("gw-3f2a"), anyLong()))
                .willReturn(attributableRun("run-01"));
        String payload = batch(ACC, "null", "null", "false", "null", RESEND)
                .replace("\"run_id\": null", "\"run_id\": \"run-01\"");

        // when
        service().ingest(MEMBER_ID, payload.getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getIsResend()).isTrue();
        assertThat(saved.getOriginalBatchId()).isEqualTo("01j8y000000000000000000000");
        assertThat(saved.getOriginalSeq()).isEqualTo(88210L);
        assertThat(saved.getOriginalRunId()).isEqualTo("run-01");
        assertThat(saved.getOriginalSessionId()).isEqualTo("s-20260919-00");
        assertThat(saved.getResentAtMs()).isEqualTo(1_760_000_180_000L);
    }

    @Test
    @DisplayName("재전송 원 회차가 현재 회차와 다르면 필드 오류로 거부한다")
    void 재전송_원_회차가_현재_회차와_다르면_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String payload = batch(ACC, "null", "null", "false", "null", RESEND)
                .replace("\"run_id\": null", "\"run_id\": \"run-02\"");

        // when & then
        assertRejected(payload, ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("재전송 원 회차 키가 빠지면 현재 회차가 null이어도 필드 오류로 거부한다")
    void 재전송_원_회차_키가_빠지면_현재_회차가_null이어도_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String withoutOriginalRunId = RESEND.replace("\"original_run_id\": \"run-01\",\n", "");

        // when & then
        assertRejected(
                batch(ACC, "null", "null", "false", "null", withoutOriginalRunId),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("현재 회차와 재전송 원 회차가 모두 null이면 명시한 계보를 저장한다")
    void 현재_회차와_재전송_원_회차가_모두_null이면_명시한_계보를_저장한다() {
        // given
        givenMemberExists();
        String nullRunLineage = RESEND.replace("\"original_run_id\": \"run-01\"", "\"original_run_id\": null");

        // when
        service().ingest(MEMBER_ID,
                batch(ACC, "null", "null", "false", "null", nullRunLineage).getBytes(UTF_8));

        // then
        assertThat(savedBatch().getOriginalRunId()).isNull();
    }

    @Test
    @DisplayName("수집 모드가 없으면 필드 오류로 거부한다")
    void 수집_모드가_없으면_필드_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertRejected(
                batch(ACC, "null").replace("\"collection_mode\": \"product\",\n", ""),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("착용 여부가 없으면 필드 오류로 거부한다")
    void 착용_여부가_없으면_필드_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertRejected(
                batch(ACC, "null").replace("\"on_body\": true,\n", ""),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("스트림과 출처가 어긋나면 필드 오류로 거부한다")
    void 스트림과_출처가_어긋나면_필드_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertRejected(
                batch(ACC, "null").replace("\"source\": \"watch\"", "\"source\": \"phone\""),
                ErrorCode.SENSOR_BATCH_INVALID);
    }

    @Test
    @DisplayName("본문이 상한을 넘으면 파싱하기 전에 거부한다")
    void 본문이_상한을_넘으면_파싱하기_전에_거부한다() {
        // given
        givenMemberExists();
        byte[] oversized = new byte[32_769];

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, oversized))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_BATCH_TOO_LARGE);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("시계 매핑이 충돌하면 S3 업로드와 배치 저장을 하지 않는다")
    void 시계_매핑이_충돌하면_S3_업로드와_배치_저장을_하지_않는다() {
        // given
        givenMemberExists();
        willThrow(new BusinessException(ErrorCode.SENSOR_CLOCK_MAPPING_CONFLICT))
                .given(clockMappingService).register(any(), anyString(), anyLong(), anyLong());

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_CLOCK_MAPPING_CONFLICT);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("시계 매핑에 넘기는 관측 범위가 두 축 시각의 최소·최대와 같다")
    void 시계_매핑에_넘기는_관측_범위가_두_축_시각의_최소와_최대와_같다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());

        // when
        service().ingest(MEMBER_ID, batch(ACC, GYRO).getBytes(UTF_8));

        // then
        ArgumentCaptor<Long> minNs = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> maxNs = ArgumentCaptor.forClass(Long.class);
        then(clockMappingService).should()
                .register(any(), eq("gw-3f2a"), minNs.capture(), maxNs.capture());
        // 자이로 t0가 가장 이르고, 가속도의 마지막 샘플(t0 + dt 합)이 가장 늦다.
        assertThat(minNs.getValue()).isEqualTo(993_847_110_000_001L);
        assertThat(maxNs.getValue()).isEqualTo(993_847_152_341_423L);
    }

    @Test
    @DisplayName("run_id가 없는 배치는 그 시점에 열려 있던 회차로 귀속된다")
    void run_id가_없는_배치는_그_시점에_열려_있던_회차로_귀속된다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        CollectionRun run = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("P-001")
                .collectionMode("research")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(collectionRunService.resolveRun(eq(MEMBER_ID), eq("gw-3f2a"), anyLong()))
                .willReturn(Optional.of(run));

        // when
        service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getRunId()).isEqualTo("run-0f3a");
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("P-001");
    }

    @Test
    @DisplayName("회차가 있으면 배치가 실어 보낸 연구 식별자를 무시하고 회차 값을 저장한다")
    void 회차가_있으면_배치가_실어_보낸_연구_식별자를_무시하고_회차_값을_저장한다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        given(collectionRunService.resolveRun(eq(MEMBER_ID), eq("gw-3f2a"), anyLong()))
                .willReturn(Optional.of(attributableRun("run-0f3a")));

        // when
        String payload = batch(ACC, "null")
                .replace("\"study_id\": null", "\"study_id\": \"요청이-주장한-연구\"")
                .replace("\"participation_id\": null", "\"participation_id\": \"요청이-주장한-참여\"");
        service().ingest(MEMBER_ID, payload.getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("P-001");
    }

    @Test
    @DisplayName("재전송 배치는 원 회차를 우선해 귀속하고 열린 회차를 찾지 않는다")
    void 재전송_배치는_원_회차를_우선해_귀속하고_열린_회차를_찾지_않는다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        given(collectionRunService.requireAttributableRun(eq("run-01"), eq(MEMBER_ID), eq("gw-3f2a"), anyLong()))
                .willReturn(attributableRun("run-01"));

        // when
        String payload = batch(ACC, "null", "null", "false", "null", RESEND)
                .replace("\"run_id\": null", "\"run_id\": \"run-01\"");
        service().ingest(MEMBER_ID, payload.getBytes(UTF_8));

        // then
        // 늦게 도착한 자료를 나중 참가자·나중 회차에 붙이면 안 된다(지시서 B4).
        SensorBatch saved = savedBatch();
        assertThat(saved.getRunId()).isEqualTo("run-01");
        then(collectionRunService).should(never()).resolveRun(anyLong(), anyString(), anyLong());
    }

    private CollectionRun attributableRun(String runId) {
        return CollectionRun.builder()
                .runId(runId)
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("P-001")
                .collectionMode("research")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
    }

    @Test
    @DisplayName("설정과 같은 값을 실어 보낸 배치는 불일치로 표시되지 않는다")
    void 설정과_같은_값을_실어_보낸_배치는_불일치로_표시되지_않는다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());

        // when
        service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8));

        // then
        assertThat(savedBatch().getConfigMismatch()).isFalse();
    }

    @Test
    @DisplayName("표본율이 지시값과 다르면 불일치로 표시하되 배치는 저장한다")
    void 표본율이_지시값과_다르면_불일치로_표시하되_배치는_저장한다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        String slowerAcc = SensorBatchFixture.acc("3", "[19998417, 20003005]",
                "[[20, -980, 110], [22, -979, 108], [21, -981, 109]]")
                .replace("\"fs_hz_requested\": 50", "\"fs_hz_requested\": 25");

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, batch(slowerAcc, "null").getBytes(UTF_8));

        // then
        // 거부하지 않는다. 설정이 적용되지 않은 채 도는 상황을 표시만 한다(지시서 B12).
        assertThat(response.result()).isEqualTo(SensorBatchResult.STORED);
        assertThat(savedBatch().getConfigMismatch()).isTrue();
    }

    @Test
    @DisplayName("자이로 모드가 지시값과 다르면 불일치로 표시하되 배치는 저장한다")
    void 자이로_모드가_지시값과_다르면_불일치로_표시하되_배치는_저장한다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        String triggerMode = batch(ACC, "null").replace("\"gyro_mode\": \"continuous\"", "\"gyro_mode\": \"trigger\"");

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, triggerMode.getBytes(UTF_8));

        // then
        assertThat(response.result()).isEqualTo(SensorBatchResult.STORED);
        assertThat(savedBatch().getConfigMismatch()).isTrue();
    }

    @Test
    @DisplayName("연구 회차에 귀속된 배치가 product로 오면 불일치로 표시한다")
    void 연구_회차에_귀속된_배치가_product로_오면_불일치로_표시한다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        CollectionRun researchRun = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .collectionMode("research")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(collectionRunService.resolveRun(eq(MEMBER_ID), eq("gw-3f2a"), anyLong()))
                .willReturn(Optional.of(researchRun));

        // when
        service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getCollectionMode()).isEqualTo("product");
        assertThat(saved.getConfigMismatch()).isTrue();
    }

    @Test
    @DisplayName("앱이 실어 보낸 회차가 연구 회차면 research 배치를 불일치로 표시하지 않는다")
    void 앱이_실어_보낸_회차가_연구_회차면_research_배치를_불일치로_표시하지_않는다() {
        // given
        givenMemberExists();
        given(sensorBatchRepository.findByBatchId(BATCH_ID)).willReturn(Optional.empty());
        CollectionRun researchRun = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .collectionMode("research")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(collectionRunService.requireAttributableRun(
                eq("run-0f3a"), eq(MEMBER_ID), eq("gw-3f2a"), anyLong())).willReturn(researchRun);

        // when
        service().ingest(MEMBER_ID, researchBatchWithRunId("run-0f3a").getBytes(UTF_8));

        // then
        SensorBatch saved = savedBatch();
        assertThat(saved.getRunId()).isEqualTo("run-0f3a");
        assertThat(saved.getCollectionMode()).isEqualTo("research");
        assertThat(saved.getConfigMismatch()).isFalse();
    }

    @Test
    @DisplayName("모르는 회차를 실어 보내면 귀속을 거부한다")
    void 모르는_회차를_실어_보내면_귀속을_거부한다() {
        // given
        givenMemberExists();
        willThrow(new BusinessException(ErrorCode.RUN_NOT_FOUND))
                .given(collectionRunService)
                .requireAttributableRun(eq("run-unknown"), eq(MEMBER_ID), eq("gw-3f2a"), anyLong());

        // when
        assertThatThrownBy(() -> service().ingest(
                MEMBER_ID, researchBatchWithRunId("run-unknown").getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_NOT_FOUND);
    }

    @Test
    @DisplayName("심박 배치를 저장하면 샘플 저장이 인덱스 행보다 먼저 일어난다")
    void 심박_배치를_저장하면_샘플_저장이_인덱스_행보다_먼저_일어난다() {
        // given
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(sensorBatchRepository.findByBatchId(SensorBatchFixture.HR_BATCH_ID)).willReturn(Optional.empty());
        given(heartRateBatchService.storeAndAssess(eq(member), eq(SensorBatchFixture.HR_BATCH_ID), any(), anyLong()))
                .willReturn(new com.widyu.heart.application.HeartRateBatchService.BatchOutcome(3, 0, 1));
        byte[] payload = SensorBatchFixture.heartRateBatch(SensorBatchFixture.HR_SAMPLES).getBytes(UTF_8);

        // when
        SensorBatchResultResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response).isEqualTo(SensorBatchResultResponse.of(
                SensorBatchFixture.HR_BATCH_ID, 1041L, SensorBatchResult.STORED));

        // 인덱스 행이 먼저 생기면 샘플 저장 실패 시 재전송이 DUPLICATE로 막힌다(ADR-0031 결정 4).
        InOrder inOrder = inOrder(s3Service, heartRateBatchService, sensorBatchRepository);
        inOrder.verify(s3Service).uploadBytes(anyString(), any(), anyString());
        inOrder.verify(heartRateBatchService).storeAndAssess(eq(member), eq(SensorBatchFixture.HR_BATCH_ID), any(), anyLong());
        inOrder.verify(sensorBatchRepository).save(any(SensorBatch.class));

        SensorBatch saved = savedBatch();
        assertThat(saved.getStream()).isEqualTo("hr");
        assertThat(saved.getS3Key())
                .isEqualTo("sensor/1/gw-3f2a/hr/01j8zk3v9x2q4m7n8p1r5s6t7v-%s.json"
                        .formatted(sha256Hex(payload)));
        assertThat(saved.getSampleCount()).isEqualTo(3);
        // 심박은 시계 환산 없이 샘플의 ts_ms 최소·최대를 쓴다.
        assertThat(saved.getMeasuredAtStartMs()).isEqualTo(1_760_000_000_123L);
        assertThat(saved.getMeasuredAtEndMs()).isEqualTo(1_760_000_002_118L);
        assertThat(saved.getOnBody()).isTrue();
        assertThat(saved.getWatchBatteryPct()).isEqualTo(63);
        // 축·충격·설정 대조는 IMU만의 개념이다.
        assertThat(saved.getAccN()).isNull();
        assertThat(saved.getGyroN()).isNull();
        assertThat(saved.getCollectionMode()).isNull();
        assertThat(saved.getGyroMode()).isNull();
        assertThat(saved.getConfigMismatch()).isFalse();
        // 지연은 서버 수신 시각에서 마지막 잰 시각을 뺀 값이다(정책 1.1.8).
        assertThat(saved.getServerReceivedAtMs()).isGreaterThan(saved.getMeasuredAtEndMs());
    }

    @Test
    @DisplayName("심박 샘플이 61개면 축 오류로 거부한다")
    void 심박_샘플이_61개면_축_오류로_거부한다() {
        // given
        givenMemberExists();
        StringBuilder samples = new StringBuilder("[");
        for (int i = 0; i < 61; i++) {
            samples.append("{\"bpm\": 71, \"ts_ms\": %d, \"accuracy\": \"HIGH\"}"
                    .formatted(1_760_000_000_000L + i));
            if (i < 60) {
                samples.append(",");
            }
        }
        samples.append("]");

        // when & then
        assertHeartRateRejected(samples.toString(), ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("bpm이 0인데 신뢰할 수 있다고 오면 축 오류로 거부한다")
    void bpm이_0인데_신뢰할_수_있다고_오면_축_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertHeartRateRejected(
                "[{\"bpm\": 0, \"ts_ms\": 1760000000123, \"accuracy\": \"HIGH\"}]",
                ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("심박 샘플 시각이 역행하면 축 오류로 거부한다")
    void 심박_샘플_시각이_역행하면_축_오류로_거부한다() {
        // given
        givenMemberExists();

        // when & then
        assertHeartRateRejected("""
                [
                          { "bpm": 71, "ts_ms": 1760000001120, "accuracy": "HIGH" },
                          { "bpm": 72, "ts_ms": 1760000000123, "accuracy": "HIGH" }
                        ]""", ErrorCode.SENSOR_SAMPLE_INVALID);
    }

    @Test
    @DisplayName("계약에서 삭제된 location이 실려 오면 필드 오류로 거부한다")
    void 계약에서_삭제된_location이_실려_오면_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String withLocation = SensorBatchFixture.heartRateBatch(SensorBatchFixture.HR_SAMPLES)
                .replace("\"on_body\": true", "\"location\": \"서울시\", \"on_body\": true");

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, withLocation.getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_BATCH_INVALID);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(heartRateBatchService).should(never()).storeAndAssess(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("모르는 스트림이면 필드 오류로 거부한다")
    void 모르는_스트림이면_필드_오류로_거부한다() {
        // given
        givenMemberExists();
        String unknownStream = SensorBatchFixture.heartRateBatch(SensorBatchFixture.HR_SAMPLES)
                .replace("\"stream\": \"hr\"", "\"stream\": \"ecg\"");

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, unknownStream.getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_BATCH_INVALID);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("S3 업로드가 실패하면 인덱스 행을 저장하지 않는다")
    void S3_업로드가_실패하면_인덱스_행을_저장하지_않는다() {
        // given
        givenMemberExists();
        willThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED))
                .given(s3Service).uploadBytes(anyString(), any(), anyString());

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_UPLOAD_FAILED);
        then(sensorBatchRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("회원이 없으면 예외가 발생한다")
    void 회원이_없으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, batch(ACC, "null").getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    private SensorBatchService service() {
        return new SensorBatchService(
                sensorBatchRepository,
                clockMappingService,
                heartRateBatchService,
                fallAssessmentService,
                collectionRunService,
                memberRepository,
                s3Service,
                VALIDATOR,
                SensorConfigFixture.properties(),
                new ObjectMapper());
    }

    private String researchBatchWithRunId(String runId) {
        return batch(ACC, "null")
                .replace("\"run_id\": null", "\"run_id\": \"%s\"".formatted(runId))
                .replace("\"collection_mode\": \"product\"", "\"collection_mode\": \"research\"");
    }

    /** 심박 검증 실패는 S3·DB·샘플 저장에 아무것도 남기지 않아야 한다. */
    private void assertHeartRateRejected(String samples, ErrorCode expected) {
        byte[] payload = SensorBatchFixture.heartRateBatch(samples).getBytes(UTF_8);
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, payload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", expected);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
        then(heartRateBatchService).should(never()).storeAndAssess(any(), anyString(), any(), anyLong());
    }

    private void givenMemberExists() {
        given(memberRepository.findById(MEMBER_ID))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01012345678")));
    }

    private SensorBatch savedBatch() {
        ArgumentCaptor<SensorBatch> saved = ArgumentCaptor.forClass(SensorBatch.class);
        then(sensorBatchRepository).should().save(saved.capture());
        return saved.getValue();
    }

    /** 검증 실패는 S3·DB에 아무것도 남기지 않아야 한다. */
    private void assertRejected(String json, ErrorCode expected) {
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, json.getBytes(UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", expected);
        then(s3Service).should(never()).uploadBytes(anyString(), any(), anyString());
        then(sensorBatchRepository).should(never()).save(any());
    }

    private String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String expectedKey(byte[] payload) {
        return "sensor/1/gw-3f2a/imu_watch/%s-%s.json".formatted(BATCH_ID, sha256Hex(payload));
    }

    private SensorBatch storedBatchWithHash(byte[] payload) {
        return SensorBatch.builder().payloadSha256(sha256Hex(payload)).build();
    }
}
