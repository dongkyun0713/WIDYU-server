package com.widyu.location.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.mockito.InOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("SafeZoneAlertService 전이 테스트")
class SafeZoneAlertServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private SafeZoneAlertService safeZoneAlertService;

    @Test
    @DisplayName("안심구역에 재진입하면 열린 안심구역 사건의 상황 종료 시각을 기록한다")
    void 안심구역에_재진입하면_열린_사건의_상황_종료_시각을_기록한다() {
        // given
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));

        // when
        safeZoneAlertService.handleSafeZoneTransition(1L, null, "HOME");

        // then
        InOrder lockOrder = inOrder(memberRepository, incidentRepository);
        lockOrder.verify(memberRepository).findByIdForUpdate(1L);
        lockOrder.verify(incidentRepository).endOpenSafeZoneSituation(eq(1L), any(Long.class));
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("안심구역 안이나 밖에 계속 머무르면 사건을 조회하거나 이벤트를 발행하지 않는다")
    void 안심구역_안이나_밖에_계속_머무르면_사건을_조회하거나_이벤트를_발행하지_않는다() {
        // when
        safeZoneAlertService.handleSafeZoneTransition(1L, "HOME", "HOME");
        safeZoneAlertService.handleSafeZoneTransition(1L, null, null);

        // then
        then(incidentRepository).shouldHaveNoInteractions();
        then(memberRepository).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("안심구역을 이탈하면 사건 이벤트를 한 번 발행한다")
    void 안심구역을_이탈하면_사건_이벤트를_한_번_발행한다() {
        // given
        ArgumentCaptor<SafeZoneExitEvent> eventCaptor = ArgumentCaptor.forClass(SafeZoneExitEvent.class);

        // when
        safeZoneAlertService.handleSafeZoneTransition(1L, "HOME", null);

        // then
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().seniorMemberId()).isEqualTo(1L);
        then(incidentRepository).shouldHaveNoInteractions();
    }
}
