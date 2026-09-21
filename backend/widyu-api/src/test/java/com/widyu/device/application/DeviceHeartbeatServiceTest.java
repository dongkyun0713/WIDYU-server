package com.widyu.device.application;

import static com.widyu.device.application.DeviceHeartbeatFixture.DEVICE_ID;
import static com.widyu.device.application.DeviceHeartbeatFixture.MEMBER_ID;
import static com.widyu.device.application.DeviceHeartbeatFixture.PHONE;
import static com.widyu.device.application.DeviceHeartbeatFixture.PHONE_WITHOUT_BATTERY;
import static com.widyu.device.application.DeviceHeartbeatFixture.SESSION_ID;
import static com.widyu.device.application.DeviceHeartbeatFixture.TS_MS;
import static com.widyu.device.application.DeviceHeartbeatFixture.WATCH_CONNECTED;
import static com.widyu.device.application.DeviceHeartbeatFixture.WATCH_DISCONNECTED;
import static com.widyu.device.application.DeviceHeartbeatFixture.WATCH_OFF_BODY;
import static com.widyu.device.application.DeviceHeartbeatFixture.heartbeat;
import static com.widyu.device.application.DeviceHeartbeatFixture.heartbeatWithRun;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.device.DeviceHeartbeat;
import com.widyu.device.dto.response.DeviceHeartbeatResponse;
import com.widyu.device.dto.response.DeviceHeartbeatResult;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.application.CollectionRunService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceHeartbeatService 단위 테스트")
class DeviceHeartbeatServiceTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Mock private DeviceHeartbeatRepository deviceHeartbeatRepository;
    @Mock private CollectionRunService collectionRunService;
    @Mock private MemberRepository memberRepository;

    @Test
    @DisplayName("부록 B 하트비트를 보내면 원문 바이트와 해시·봉투 시각·회차가 담긴 행을 남긴다")
    void 부록_B_하트비트를_보내면_원문과_해시와_봉투_시각이_담긴_행을_남긴다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_CONNECTED).getBytes(UTF_8);
        CollectionRun run = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("P-001")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.of(run));
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false);
        long before = System.currentTimeMillis();

        // when
        DeviceHeartbeatResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response).isEqualTo(DeviceHeartbeatResponse.of(TS_MS, DeviceHeartbeatResult.STORED));

        DeviceHeartbeat saved = savedHeartbeat();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getTsMs()).isEqualTo(TS_MS);
        assertThat(saved.getPhoneBatteryPct()).isEqualTo(71);
        assertThat(saved.getPhoneCharging()).isFalse();
        assertThat(saved.getPhoneOs()).isEqualTo("android/14");
        assertThat(saved.getPhoneAppVer()).isEqualTo("1.4.2");
        assertThat(saved.getSocketConnected()).isTrue();
        assertThat(saved.getLocationPermission()).isEqualTo("always");
        assertThat(saved.getBackgroundRestricted()).isFalse();
        assertThat(saved.getPhoneQueueDepth()).isZero();
        assertThat(saved.getWatchConnected()).isTrue();
        assertThat(saved.getWatchDeviceId()).isEqualTo("gw-3f2a");
        assertThat(saved.getWatchBatteryPct()).isEqualTo(63);
        assertThat(saved.getWatchAppVer()).isEqualTo("1.4.2");
        assertThat(saved.getHrSession()).isEqualTo("RUNNING");
        assertThat(saved.getWatchOnBody()).isTrue();
        assertThat(saved.getLastHrTsMs()).isEqualTo(1_760_000_000_000L);
        assertThat(saved.getLastImuTsMs()).isEqualTo(1_760_000_000_100L);
        assertThat(saved.getWatchQueueDepth()).isZero();
        assertThat(saved.getPayload()).isEqualTo(new String(payload, UTF_8));
        assertThat(saved.getPayloadSha256()).isEqualTo(sha256(payload));
        assertThat(saved.getServerReceivedAtMs()).isGreaterThanOrEqualTo(before);
        assertThat(saved.getAcceptedAtMs()).isGreaterThanOrEqualTo(saved.getServerReceivedAtMs());
        assertThat(saved.getPersistedAtMs()).isGreaterThanOrEqualTo(saved.getAcceptedAtMs());
        assertThat(saved.getRunId()).isEqualTo("run-0f3a");
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("P-001");
    }

    @Test
    @DisplayName("회차가 있으면 본문이 실어 보낸 연구 식별자를 무시하고 회차 값을 저장한다")
    void 회차가_있으면_본문이_실어_보낸_연구_식별자를_무시하고_회차_값을_저장한다() {
        // given
        byte[] payload = heartbeatWithRun(
                PHONE, WATCH_CONNECTED, "run-0f3a", "요청이-주장한-연구", "요청이-주장한-참여")
                .getBytes(UTF_8);
        CollectionRun run = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("part-0f3a")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.requireAttributableRun("run-0f3a", MEMBER_ID, DEVICE_ID, TS_MS))
                .willReturn(run);
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false);

        // when
        service().ingest(MEMBER_ID, payload);

        // then
        DeviceHeartbeat saved = savedHeartbeat();
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("part-0f3a");
    }

    @Test
    @DisplayName("같은 기기·세션·측정 시각을 다시 보내면 저장하지 않고 DUPLICATE를 반환한다")
    void 같은_기기와_세션과_측정_시각을_다시_보내면_DUPLICATE를_반환한다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_CONNECTED).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(true);

        // when
        DeviceHeartbeatResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response.result()).isEqualTo(DeviceHeartbeatResult.DUPLICATE);
        assertThat(response.tsMs()).isEqualTo(TS_MS);
        then(deviceHeartbeatRepository).should(never()).save(any(DeviceHeartbeat.class));
    }

    @Test
    @DisplayName("UK 경합으로 저장이 실패해도 같은 행이 확인되면 DUPLICATE를 반환한다")
    void UK_경합이어도_같은_행이_확인되면_DUPLICATE를_반환한다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_CONNECTED).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false, true);
        willThrow(new DataIntegrityViolationException("uk_device_heartbeat_ts"))
                .given(deviceHeartbeatRepository).save(any(DeviceHeartbeat.class));

        // when
        DeviceHeartbeatResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response.result()).isEqualTo(DeviceHeartbeatResult.DUPLICATE);
    }

    @Test
    @DisplayName("폰 배터리 잔량이 없으면 HEARTBEAT_INVALID 예외가 발생한다")
    void 폰_배터리_잔량이_없으면_예외가_발생한다() {
        // given
        byte[] payload = heartbeat(PHONE_WITHOUT_BATTERY, WATCH_CONNECTED).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, payload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HEARTBEAT_INVALID);
        then(deviceHeartbeatRepository).should(never()).save(any(DeviceHeartbeat.class));
    }

    @Test
    @DisplayName("워치가 끊겨 나머지 값이 없어도 저장하고 워치 컬럼을 null로 남긴다")
    void 워치가_끊겨_나머지_값이_없어도_저장한다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_DISCONNECTED).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false);

        // when
        DeviceHeartbeatResponse response = service().ingest(MEMBER_ID, payload);

        // then
        assertThat(response.result()).isEqualTo(DeviceHeartbeatResult.STORED);
        DeviceHeartbeat saved = savedHeartbeat();
        assertThat(saved.getWatchConnected()).isFalse();
        assertThat(saved.getWatchBatteryPct()).isNull();
        assertThat(saved.getWatchQueueDepth()).isNull();
        assertThat(saved.getHrSession()).isNull();
        assertThat(saved.getWatchOnBody()).isNull();
        assertThat(saved.getLastImuTsMs()).isNull();
        assertThat(saved.getRunId()).isNull();
    }

    @Test
    @DisplayName("워치를 벗어 두면 on_body가 false 값 그대로 남는다")
    void 워치를_벗어_두면_on_body가_false로_남는다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_OFF_BODY).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false);

        // when
        service().ingest(MEMBER_ID, payload);

        // then
        DeviceHeartbeat saved = savedHeartbeat();
        assertThat(saved.getWatchOnBody()).isFalse();
        assertThat(saved.getWatchConnected()).isTrue();
        assertThat(saved.getHrSession()).isEqualTo("STOPPED");
        assertThat(saved.getWatchQueueDepth()).isEqualTo(12);
    }

    @Test
    @DisplayName("본문을 읽을 수 없으면 HEARTBEAT_INVALID 예외가 발생한다")
    void 본문을_읽을_수_없으면_예외가_발생한다() {
        // given
        byte[] payload = "not-json".getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, payload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HEARTBEAT_INVALID);
    }

    @Test
    @DisplayName("없는 회원이 보내면 MEMBER_NOT_FOUND 예외가 발생한다")
    void 없는_회원이_보내면_예외가_발생한다() {
        // given
        byte[] payload = heartbeat(PHONE, WATCH_CONNECTED).getBytes(UTF_8);
        given(memberRepository.existsById(MEMBER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> service().ingest(MEMBER_ID, payload))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        then(deviceHeartbeatRepository).should(never()).save(any(DeviceHeartbeat.class));
    }

    @Test
    @DisplayName("본문 회차가 배정 구간과 맞으면 서버 회차 식별자로 저장한다")
    void 본문_회차가_배정_구간과_맞으면_서버_회차_식별자로_저장한다() {
        // given
        byte[] payload = heartbeatWithRun(
                        PHONE, WATCH_CONNECTED, "run-0f3a", "forged-study", "forged-participation")
                .getBytes(UTF_8);
        CollectionRun run = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("P-001")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(collectionRunService.requireAttributableRun("run-0f3a", MEMBER_ID, DEVICE_ID, TS_MS))
                .willReturn(run);
        given(deviceHeartbeatRepository.existsByDeviceIdAndSessionIdAndTsMs(DEVICE_ID, SESSION_ID, TS_MS))
                .willReturn(false);

        // when
        service().ingest(MEMBER_ID, payload);

        // then
        DeviceHeartbeat saved = savedHeartbeat();
        assertThat(saved.getRunId()).isEqualTo("run-0f3a");
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("P-001");
    }

    private DeviceHeartbeat savedHeartbeat() {
        ArgumentCaptor<DeviceHeartbeat> captor = ArgumentCaptor.forClass(DeviceHeartbeat.class);
        then(deviceHeartbeatRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private DeviceHeartbeatService service() {
        return new DeviceHeartbeatService(
                deviceHeartbeatRepository,
                collectionRunService,
                memberRepository,
                VALIDATOR,
                new ObjectMapper());
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
