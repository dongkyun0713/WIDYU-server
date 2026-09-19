package com.widyu.heart.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.properties.HeartProperties;
import com.widyu.heart.repository.HeartRateEventRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRateCleanupScheduler 삭제 제외 단위 테스트")
class HeartRateCleanupSchedulerTest {

    @Mock private HeartRateEventRepository heartRateEventRepository;

    private HeartRateCleanupScheduler scheduler(List<Long> exemptMemberIds) {
        HeartProperties heartProperties = new HeartProperties(new HeartProperties.Cleanup(exemptMemberIds));
        return new HeartRateCleanupScheduler(heartRateEventRepository, heartProperties);
    }

    @Test
    @DisplayName("제외 회원이 없으면 기간 조건만으로 삭제한다")
    void 제외_회원이_없으면_기간_조건만으로_삭제한다() {
        // given
        HeartRateCleanupScheduler scheduler = scheduler(List.of());
        given(heartRateEventRepository.deleteByMeasuredAtBefore(any(LocalDateTime.class))).willReturn(7);

        // when
        scheduler.deleteOldHeartRateEvents();

        // then
        then(heartRateEventRepository).should().deleteByMeasuredAtBefore(any(LocalDateTime.class));
        then(heartRateEventRepository)
                .should(never())
                .deleteByMeasuredAtBeforeAndMemberIdNotIn(any(LocalDateTime.class), any());
    }

    @Test
    @DisplayName("제외 회원이 있으면 해당 회원을 뺀 조건으로 삭제한다")
    void 제외_회원이_있으면_해당_회원을_뺀_조건으로_삭제한다() {
        // given
        HeartRateCleanupScheduler scheduler = scheduler(List.of(1023L, 1077L));
        given(heartRateEventRepository.deleteByMeasuredAtBeforeAndMemberIdNotIn(any(LocalDateTime.class), any()))
                .willReturn(3);
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);

        // when
        scheduler.deleteOldHeartRateEvents();

        // then
        then(heartRateEventRepository)
                .should()
                .deleteByMeasuredAtBeforeAndMemberIdNotIn(any(LocalDateTime.class), captor.capture());
        assertThat(captor.getValue()).containsExactly(1023L, 1077L);
        then(heartRateEventRepository).should(never()).deleteByMeasuredAtBefore(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("삭제 기준 시각은 실행 시점의 30일 전이다")
    void 삭제_기준_시각은_실행_시점의_30일_전이다() {
        // given
        HeartRateCleanupScheduler scheduler = scheduler(List.of());
        given(heartRateEventRepository.deleteByMeasuredAtBefore(any(LocalDateTime.class))).willReturn(0);
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);

        // when
        scheduler.deleteOldHeartRateEvents();

        // then
        then(heartRateEventRepository).should().deleteByMeasuredAtBefore(captor.capture());
        assertThat(captor.getValue())
                .isCloseTo(LocalDateTime.now().minusDays(30), within(1, ChronoUnit.MINUTES));
    }
}
