package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import com.widyu.sensor.repository.ClockMappingRepository;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClockMappingService 단위 테스트")
class ClockMappingServiceTest {

    private static final String MAPPING_ID = "cm-01";
    private static final String DEVICE_ID = "gw-3f2a";
    private static final String BOOT_ID = "b7c1";
    private static final long ANCHOR_ELAPSED_NS = 993_847_100_000_001L;
    private static final long ANCHOR_EPOCH_MS = 1_760_000_000_000L;
    private static final double UNCERTAINTY_MS = 2.0;
    private static final long OBSERVED_MIN_NS = 993_847_110_000_001L;
    private static final long OBSERVED_MAX_NS = 993_847_152_341_423L;

    @Mock private ClockMappingRepository clockMappingRepository;
    @Mock private ClockMappingInsertService clockMappingInsertService;

    @InjectMocks private ClockMappingService clockMappingService;

    @Test
    @DisplayName("처음 보는 매핑이면 다섯 값과 관측 범위로 새 행을 저장한다")
    void 처음_보는_매핑이면_다섯_값과_관측_범위로_새_행을_저장한다() {
        // given
        given(clockMappingRepository.findByClockMappingId(MAPPING_ID)).willReturn(Optional.empty());
        long before = System.currentTimeMillis();

        // when
        clockMappingService.register(clock(), DEVICE_ID, OBSERVED_MIN_NS, OBSERVED_MAX_NS);

        // then
        ArgumentCaptor<ClockMapping> saved = ArgumentCaptor.forClass(ClockMapping.class);
        then(clockMappingInsertService).should().insert(saved.capture());
        ClockMapping mapping = saved.getValue();
        assertThat(mapping.getClockMappingId()).isEqualTo(MAPPING_ID);
        assertThat(mapping.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(mapping.getBootId()).isEqualTo(BOOT_ID);
        assertThat(mapping.getAnchorElapsedNs()).isEqualTo(ANCHOR_ELAPSED_NS);
        assertThat(mapping.getAnchorEpochMs()).isEqualTo(ANCHOR_EPOCH_MS);
        assertThat(mapping.getUncertaintyMs()).isEqualTo(UNCERTAINTY_MS);
        assertThat(mapping.getObservedMinElapsedNs()).isEqualTo(OBSERVED_MIN_NS);
        assertThat(mapping.getObservedMaxElapsedNs()).isEqualTo(OBSERVED_MAX_NS);
        assertThat(mapping.getFirstSeenAtMs()).isBetween(before, System.currentTimeMillis());
        assertThat(mapping.getLastSeenAtMs()).isEqualTo(mapping.getFirstSeenAtMs());
        // 새 행의 관측 범위가 곧 이 배치의 범위라 넓힐 것이 없다.
        then(clockMappingRepository).should(never())
                .widenObservedRange(anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("같은 값으로 다시 오면 저장하지 않고 관측 범위만 넓힌다")
    void 같은_값으로_다시_오면_저장하지_않고_관측_범위만_넓힌다() {
        // given
        given(clockMappingRepository.findByClockMappingId(MAPPING_ID))
                .willReturn(Optional.of(registeredMapping()));
        long laterMinNs = 993_847_200_000_001L;
        long laterMaxNs = 993_847_260_000_001L;
        long before = System.currentTimeMillis();

        // when
        clockMappingService.register(clock(), DEVICE_ID, laterMinNs, laterMaxNs);

        // then
        then(clockMappingInsertService).should(never()).insert(any());
        ArgumentCaptor<Long> now = ArgumentCaptor.forClass(Long.class);
        then(clockMappingRepository).should()
                .widenObservedRange(eq(MAPPING_ID), eq(laterMinNs), eq(laterMaxNs), now.capture());
        assertThat(now.getValue()).isBetween(before, System.currentTimeMillis());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("등록된_값과_다른_매핑")
    @DisplayName("등록된 매핑과 다른 값이 오면 충돌 예외가 발생한다")
    void 등록된_매핑과_다른_값이_오면_충돌_예외가_발생한다(
            String 다른_필드, SensorBatchRequest.Clock clock, String deviceId) {
        // given
        given(clockMappingRepository.findByClockMappingId(MAPPING_ID))
                .willReturn(Optional.of(registeredMapping()));

        // when & then
        assertThatThrownBy(() ->
                clockMappingService.register(clock, deviceId, OBSERVED_MIN_NS, OBSERVED_MAX_NS))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_CLOCK_MAPPING_CONFLICT);
        then(clockMappingRepository).should(never())
                .widenObservedRange(anyString(), anyLong(), anyLong(), anyLong());
    }

    private static Stream<Arguments> 등록된_값과_다른_매핑() {
        return Stream.of(
                Arguments.of("anchor_elapsed_ns",
                        new SensorBatchRequest.Clock(BOOT_ID, MAPPING_ID, "993847100000002",
                                ANCHOR_EPOCH_MS, UNCERTAINTY_MS),
                        DEVICE_ID),
                Arguments.of("anchor_epoch_ms",
                        new SensorBatchRequest.Clock(BOOT_ID, MAPPING_ID, String.valueOf(ANCHOR_ELAPSED_NS),
                                1_760_000_000_001L, UNCERTAINTY_MS),
                        DEVICE_ID),
                Arguments.of("boot_id",
                        new SensorBatchRequest.Clock("b7c2", MAPPING_ID, String.valueOf(ANCHOR_ELAPSED_NS),
                                ANCHOR_EPOCH_MS, UNCERTAINTY_MS),
                        DEVICE_ID),
                Arguments.of("uncertainty_ms",
                        new SensorBatchRequest.Clock(BOOT_ID, MAPPING_ID, String.valueOf(ANCHOR_ELAPSED_NS),
                                ANCHOR_EPOCH_MS, 3.0),
                        DEVICE_ID),
                // 기기가 참가자 사이를 돌아다니므로 매핑 공유는 자료 귀속을 깨뜨린다(검사기 B2).
                Arguments.of("device_id",
                        new SensorBatchRequest.Clock(BOOT_ID, MAPPING_ID, String.valueOf(ANCHOR_ELAPSED_NS),
                                ANCHOR_EPOCH_MS, UNCERTAINTY_MS),
                        "gw-other"));
    }

    @Test
    @DisplayName("동시에 처음 보는 매핑이 들어와 유니크 키 경합이 나면 재조회해 대조하고 통과한다")
    void 동시에_처음_보는_매핑이_들어와_유니크_키_경합이_나면_재조회해_대조하고_통과한다() {
        // given
        given(clockMappingRepository.findByClockMappingId(MAPPING_ID))
                .willReturn(Optional.empty(), Optional.of(registeredMapping()));
        org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("uk_clock_mapping_id"))
                .given(clockMappingInsertService).insert(any(ClockMapping.class));

        // when & then
        assertThatCode(() ->
                clockMappingService.register(clock(), DEVICE_ID, OBSERVED_MIN_NS, OBSERVED_MAX_NS))
                .doesNotThrowAnyException();
        then(clockMappingRepository).should()
                .widenObservedRange(eq(MAPPING_ID), eq(OBSERVED_MIN_NS), eq(OBSERVED_MAX_NS), anyLong());
    }

    @Test
    @DisplayName("경합 뒤에도 매핑 행이 없으면 원래 예외가 전파된다")
    void 경합_뒤에도_매핑_행이_없으면_원래_예외가_전파된다() {
        // given
        given(clockMappingRepository.findByClockMappingId(MAPPING_ID)).willReturn(Optional.empty());
        org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("not_a_unique_key_conflict"))
                .given(clockMappingInsertService).insert(any(ClockMapping.class));

        // when & then
        assertThatThrownBy(() ->
                clockMappingService.register(clock(), DEVICE_ID, OBSERVED_MIN_NS, OBSERVED_MAX_NS))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private SensorBatchRequest.Clock clock() {
        return new SensorBatchRequest.Clock(
                BOOT_ID, MAPPING_ID, String.valueOf(ANCHOR_ELAPSED_NS), ANCHOR_EPOCH_MS, UNCERTAINTY_MS);
    }

    private ClockMapping registeredMapping() {
        return ClockMapping.builder()
                .clockMappingId(MAPPING_ID)
                .deviceId(DEVICE_ID)
                .bootId(BOOT_ID)
                .anchorElapsedNs(ANCHOR_ELAPSED_NS)
                .anchorEpochMs(ANCHOR_EPOCH_MS)
                .uncertaintyMs(UNCERTAINTY_MS)
                .observedMinElapsedNs(OBSERVED_MIN_NS)
                .observedMaxElapsedNs(OBSERVED_MAX_NS)
                .firstSeenAtMs(1_760_000_000_500L)
                .lastSeenAtMs(1_760_000_000_500L)
                .build();
    }
}
