package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.incident.repository.IncidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentTimeoutScheduler 단위 테스트")
class IncidentTimeoutSchedulerTest {

    @Mock private IncidentRepository incidentRepository;

    @Test
    @DisplayName("폴링하면 마감을 넘긴 사건을 현재 시각 기준으로 한 번에 올린다")
    void 폴링하면_마감을_넘긴_사건을_한_번에_올린다() {
        // given
        long before = System.currentTimeMillis();
        given(incidentRepository.escalateTimedOut(anyLong())).willReturn(2);

        // when
        new IncidentTimeoutScheduler(incidentRepository).escalateTimedOut();

        // then
        ArgumentCaptor<Long> nowMs = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().escalateTimedOut(nowMs.capture());
        // 마감 판정의 기준 시각은 서버다. 단말은 무응답을 보내지 않는다(ADR-0035 결정 5).
        assertThat(nowMs.getValue()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    @DisplayName("올릴 사건이 없으면 조용히 끝난다")
    void 올릴_사건이_없으면_조용히_끝난다() {
        // given
        given(incidentRepository.escalateTimedOut(anyLong())).willReturn(0);

        // when
        new IncidentTimeoutScheduler(incidentRepository).escalateTimedOut();

        // then
        // 폴링은 5초마다 돈다. 0건까지 로그로 남기면 운영 로그가 이 한 줄로 덮인다.
        then(incidentRepository).should().escalateTimedOut(anyLong());
        then(incidentRepository).shouldHaveNoMoreInteractions();
    }
}
