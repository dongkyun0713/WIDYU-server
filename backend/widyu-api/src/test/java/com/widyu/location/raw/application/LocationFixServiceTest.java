package com.widyu.location.raw.application;

import static com.widyu.location.raw.application.LocationFixFixture.DEVICE_ID;
import static com.widyu.location.raw.application.LocationFixFixture.MEMBER_ID;
import static com.widyu.location.raw.application.LocationFixFixture.SEQ;
import static com.widyu.location.raw.application.LocationFixFixture.SESSION_ID;
import static com.widyu.location.raw.application.LocationFixFixture.TS_MS;
import static com.widyu.location.raw.application.LocationFixFixture.rawFix;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.location.raw.LocationFix;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.application.CollectionRunService;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationFixService 단위 테스트")
class LocationFixServiceTest {

    @Mock private LocationFixRepository locationFixRepository;
    @Mock private CollectionRunService collectionRunService;

    @InjectMocks private LocationFixService locationFixService;

    @Test
    @DisplayName("v2 위치를 저장하면 원문과 해시·봉투 시각·회차 귀속이 담긴 행을 남긴다")
    void v2_위치를_저장하면_원문과_해시와_봉투_시각이_담긴_행을_남긴다() {
        // given
        LocationUpdateRequest request = rawFix("move", 37.5551, 8.5, false);
        byte[] payload = "{\"v\":2,\"lat\":37.5551}".getBytes(UTF_8);
        CollectionRun run = CollectionRun.builder()
                .runId("run-0f3a")
                .member(Member.createMember(MemberType.SENIOR, "시니어", "01012345678"))
                .studyId("STUDY-2026")
                .participationId("P-001")
                .startedAtMs(1_760_000_000_000L)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(locationFixRepository.existsByDeviceIdAndSessionIdAndSeq(DEVICE_ID, SESSION_ID, SEQ))
                .willReturn(false);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS))
                .willReturn(Optional.of(run));
        long before = System.currentTimeMillis();

        // when
        locationFixService.store(MEMBER_ID, request, payload, 1_000L, 2_000L);

        // then
        LocationFix saved = savedFix();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getSeq()).isEqualTo(SEQ);
        assertThat(saved.getTsMs()).isEqualTo(TS_MS);
        assertThat(saved.getLat()).isEqualTo(37.5551);
        assertThat(saved.getLon()).isEqualTo(126.9707);
        assertThat(saved.getAccuracyM()).isEqualTo(8.5);
        assertThat(saved.getSpeedMps()).isEqualTo(0.9);
        assertThat(saved.getSpeedAccuracyMps()).isEqualTo(0.5);
        assertThat(saved.getHeadingDeg()).isEqualTo(212.0);
        assertThat(saved.getAltitudeM()).isEqualTo(41.2);
        assertThat(saved.getProvider()).isEqualTo("fused");
        assertThat(saved.getIsMock()).isFalse();
        assertThat(saved.getReason()).isEqualTo("move");
        assertThat(saved.getPayload()).isEqualTo(new String(payload, UTF_8));
        assertThat(saved.getPayloadSha256()).isEqualTo(sha256(payload));
        assertThat(saved.getServerReceivedAtMs()).isEqualTo(1_000L);
        assertThat(saved.getAcceptedAtMs()).isEqualTo(2_000L);
        assertThat(saved.getPersistedAtMs()).isGreaterThanOrEqualTo(before);
        assertThat(saved.getRunId()).isEqualTo("run-0f3a");
        assertThat(saved.getStudyId()).isEqualTo("STUDY-2026");
        assertThat(saved.getParticipationId()).isEqualTo("P-001");
    }

    @Test
    @DisplayName("같은 기기·세션·seq를 다시 보내면 저장하지 않는다")
    void 같은_기기와_세션과_seq를_다시_보내면_저장하지_않는다() {
        // given
        LocationUpdateRequest request = rawFix("move", 37.5551, 8.5, false);
        given(locationFixRepository.existsByDeviceIdAndSessionIdAndSeq(DEVICE_ID, SESSION_ID, SEQ))
                .willReturn(true);

        // when
        locationFixService.store(MEMBER_ID, request, "{}".getBytes(UTF_8), 1_000L, 2_000L);

        // then
        then(locationFixRepository).should(never()).save(any(LocationFix.class));
    }

    @Test
    @DisplayName("reason이 정해진 세 값 밖이면 LOCATION_FIX_INVALID 예외가 발생한다")
    void reason이_정해진_세_값_밖이면_예외가_발생한다() {
        // given
        LocationUpdateRequest request = rawFix("teleport", 37.5551, 8.5, false);

        // when & then
        assertThatThrownBy(() -> locationFixService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOCATION_FIX_INVALID);
    }

    @Test
    @DisplayName("위도가 ±90을 벗어나면 LOCATION_FIX_INVALID 예외가 발생한다")
    void 위도가_범위를_벗어나면_예외가_발생한다() {
        // given
        LocationUpdateRequest request = rawFix("move", 91.0, 8.5, false);

        // when & then
        assertThatThrownBy(() -> locationFixService.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOCATION_FIX_INVALID);
    }

    @Test
    @DisplayName("정확도가 250m인 위치도 값 그대로 저장한다")
    void 정확도가_낮은_위치도_값_그대로_저장한다() {
        // given
        LocationUpdateRequest request = rawFix("keepalive", 37.5551, 250.0, false);
        given(locationFixRepository.existsByDeviceIdAndSessionIdAndSeq(DEVICE_ID, SESSION_ID, SEQ))
                .willReturn(false);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());

        // when
        locationFixService.validate(request);
        locationFixService.store(MEMBER_ID, request, "{}".getBytes(UTF_8), 1_000L, 2_000L);

        // then
        LocationFix saved = savedFix();
        assertThat(saved.getAccuracyM()).isEqualTo(250.0);
        assertThat(saved.getReason()).isEqualTo("keepalive");
        assertThat(saved.getRunId()).isNull();
    }

    @Test
    @DisplayName("is_mock이 true인 위치도 값 그대로 저장한다")
    void is_mock이_true인_위치도_값_그대로_저장한다() {
        // given
        LocationUpdateRequest request = rawFix("move", 37.5551, 8.5, true);
        given(locationFixRepository.existsByDeviceIdAndSessionIdAndSeq(DEVICE_ID, SESSION_ID, SEQ))
                .willReturn(false);
        given(collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, TS_MS)).willReturn(Optional.empty());

        // when
        locationFixService.store(MEMBER_ID, request, "{}".getBytes(UTF_8), 1_000L, 2_000L);

        // then
        assertThat(savedFix().getIsMock()).isTrue();
    }

    private LocationFix savedFix() {
        ArgumentCaptor<LocationFix> captor = ArgumentCaptor.forClass(LocationFix.class);
        then(locationFixRepository).should().save(captor.capture());
        return captor.getValue();
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
