package com.widyu.location.access.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.location.access.repository.LocationAccessLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationAccessDigestScheduler 위치 열람 요약 통보 스케줄러 단위 테스트")
class LocationAccessDigestSchedulerTest {

    @Mock private LocationAccessLogRepository locationAccessLogRepository;
    @Mock private LocationAccessDigestSender locationAccessDigestSender;

    @InjectMocks
    private LocationAccessDigestScheduler locationAccessDigestScheduler;

    @Test
    @DisplayName("미통보 기록이 있으면 시니어마다 독립된 발송 트랜잭션을 호출한다")
    void 미통보_기록은_시니어마다_독립적으로_통보한다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of(7L, 8L));

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(locationAccessDigestSender).should().send(eq(7L), any(LocalDateTime.class));
        then(locationAccessDigestSender).should().send(eq(8L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("한 시니어 통보가 실패해도 다른 시니어 통보를 계속한다")
    void 한_시니어_실패가_다른_시니어를_막지_않는다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of(7L, 8L));
        willThrow(new IllegalStateException("FCM 장애"))
                .given(locationAccessDigestSender).send(eq(7L), any(LocalDateTime.class));

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(locationAccessDigestSender).should().send(eq(8L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("미통보 기록이 없으면 발송 트랜잭션을 호출하지 않는다")
    void 미통보_기록이_없으면_아무것도_하지_않는다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of());

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(locationAccessDigestSender).should(never()).send(any(), any(LocalDateTime.class));
    }
}
