package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.incident.repository.IncidentRepository;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class IncidentTimeoutSchedulerTest {
    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentEscalation escalation;

    @Test
    @DisplayName("재시작 뒤 마감된 2차 후보를 조회하면 같은 스케줄러가 발송 경로로 넘긴다")
    void 재시작_뒤_마감된_이차_후보를_조회하면_발송_경로로_넘긴다() {
        // given
        given(incidentRepository.findSecondAlertDueIds(anyLong(), eq(0L), any(Pageable.class)))
                .willReturn(List.of(7L));

        // when
        new IncidentTimeoutScheduler(incidentRepository, escalation).escalateTimedOut();

        // then
        ArgumentCaptor<Long> now = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().findSecondAlertDueIds(now.capture(), eq(0L), any(Pageable.class));
        then(escalation).should().sendSecondAlertIfDue(7L);
    }

    @Test
    @DisplayName("무응답 전환과 미발송 후보를 조회하면 각 사건을 별도 처리 경로로 넘긴다")
    void 무응답_전환과_미발송_후보를_조회하면_각_사건을_처리한다() {
        // given
        long before = System.currentTimeMillis();
        // 첫 사건은 플래그 OFF로 이미 발송한 무응답, 둘째는 플래그 ON의 미발송 후보다.
        given(incidentRepository.findDueIds(anyLong(), eq(0L), any(Pageable.class)))
                .willReturn(List.of(1L, 2L));

        // when
        new IncidentTimeoutScheduler(incidentRepository, escalation).escalateTimedOut();

        // then
        ArgumentCaptor<Long> now = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().findDueIds(now.capture(), eq(0L), any(Pageable.class));
        assertThat(now.getValue()).isBetween(before, System.currentTimeMillis());
        then(escalation).should().escalateIfDue(eq(1L), eq(now.getValue()));
        then(escalation).should().escalateIfDue(eq(2L), eq(now.getValue()));
        then(escalation).should().escalateFallTimedOut(eq(now.getValue()));
    }

    @Test
    @DisplayName("마감 후보가 없으면 발송 경로를 호출하지 않는다")
    void 마감_후보가_없으면_발송_경로를_호출하지_않는다() {
        // given
        given(incidentRepository.findDueIds(anyLong(), eq(0L), any(Pageable.class)))
                .willReturn(List.of());

        // when
        new IncidentTimeoutScheduler(incidentRepository, escalation).escalateTimedOut();

        // then
        then(escalation).should(never()).escalateIfDue(anyLong(), anyLong());
        then(escalation).should().escalateFallTimedOut(anyLong());
    }

    @Test
    @DisplayName("앞 페이지 사건의 발송이 실패해도 다음 페이지 사건을 같은 폴링에서 처리한다")
    void 앞_페이지_발송이_실패해도_다음_페이지를_처리한다() {
        // given
        List<Long> firstPage = LongStream.rangeClosed(1, 100).boxed().toList();
        given(incidentRepository.findDueIds(anyLong(), eq(0L), any(Pageable.class)))
                .willReturn(firstPage);
        given(incidentRepository.findDueIds(anyLong(), eq(100L), any(Pageable.class)))
                .willReturn(List.of(101L));
        given(escalation.escalateIfDue(eq(1L), anyLong()))
                .willThrow(new IllegalStateException("enqueue failed"));

        // when
        new IncidentTimeoutScheduler(incidentRepository, escalation).escalateTimedOut();

        // then
        ArgumentCaptor<Long> now = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().findDueIds(now.capture(), eq(0L), any(Pageable.class));
        then(incidentRepository).should().findDueIds(eq(now.getValue()), eq(100L), any(Pageable.class));
        then(escalation).should().escalateIfDue(eq(2L), eq(now.getValue()));
        then(escalation).should().escalateIfDue(eq(101L), eq(now.getValue()));
        then(escalation).should().escalateFallTimedOut(eq(now.getValue()));
    }
}
