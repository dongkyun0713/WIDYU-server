package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.entity.Status;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentEscalationTest {
    @Mock private IncidentRepository incidents;
    @Mock private FcmOutboxService outbox;
    @Mock private MemberRepository members;
    @Mock private FamilyMembershipRepository memberships;
    @Mock private SeniorProfileRepository seniorProfiles;
    @InjectMocks private IncidentEscalation escalation;

    @Test
    @DisplayName("플래그 OFF에서 사건을 열면 활성 보호자 전원에게 S04를 enqueue한다")
    void 플래그_OFF에서_사건을_열면_활성_보호자_전원에게_S04를_enqueue한다() {
        // given
        Incident incident = incident("dec-1");
        givenRecipients();

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).should().enqueue(eq(3L), message.capture());
        assertMessage(message.getAllValues().get(0), "dec-1");
        assertMessage(message.getAllValues().get(1), "dec-1");
    }

    @Test
    @DisplayName("안심구역 이탈 사건을 즉시 알리면 보호자에게 S05를 enqueue한다")
    void 안심구역_이탈_사건을_즉시_알리면_보호자에게_S05를_enqueue한다() {
        // given
        Incident incident = safeZoneIncident();
        givenRecipients();

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).should().enqueue(eq(3L), message.capture());
        assertSafeZoneMessage(message.getAllValues().get(0));
        assertSafeZoneMessage(message.getAllValues().get(1));
    }

    @Test
    @DisplayName("안심구역 이탈 사건의 확인 시간이 끝나면 공통 스케줄러가 S05를 enqueue한다")
    void 안심구역_이탈_사건의_확인_시간이_끝나면_공통_스케줄러가_S05를_enqueue한다() {
        // given
        Incident incident = safeZoneIncident();
        given(incidents.escalateTimedOutIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.claimInitialAlertIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.findById(3L)).willReturn(Optional.of(incident));
        givenRecipients();

        // when
        boolean queued = escalation.escalateIfDue(3L, 61_000L);

        // then
        assertThat(queued).isTrue();
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).should().enqueue(eq(3L), message.capture());
        assertSafeZoneMessage(message.getAllValues().get(0));
        assertSafeZoneMessage(message.getAllValues().get(1));
    }

    @Test
    @DisplayName("플래그 ON 사건이 만료되면 상태 전환 뒤 최초 알림을 한 번 enqueue한다")
    void 플래그_ON_사건이_만료되면_전환_뒤_알림을_한_번_enqueue한다() {
        // given
        Incident incident = incident(null);
        given(incidents.escalateTimedOutIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.claimInitialAlertIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.findById(3L)).willReturn(Optional.of(incident));
        givenRecipients();

        // when
        boolean queued = escalation.escalateIfDue(3L, 61_000L);

        // then
        assertThat(queued).isTrue();
        then(incidents).should().escalateTimedOutIfDue(3L, 61_000L);
        then(incidents).should().claimInitialAlertIfDue(3L, 61_000L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).should().enqueue(eq(3L), message.capture());
        assertMessage(message.getAllValues().get(0), null);
        assertMessage(message.getAllValues().get(1), null);
    }

    @Test
    @DisplayName("플래그 OFF 사건은 만료 시 상태를 올려도 최초 알림을 추가 발송하지 않는다")
    void 플래그_OFF_사건은_만료_시_상태만_올리고_알림은_추가하지_않는다() {
        // given
        given(incidents.escalateTimedOutIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.claimInitialAlertIfDue(3L, 61_000L)).willReturn(0);

        // when
        boolean queued = escalation.escalateIfDue(3L, 61_000L);

        // then
        assertThat(queued).isFalse();
        then(incidents).should().escalateTimedOutIfDue(3L, 61_000L);
        then(incidents).should().claimInitialAlertIfDue(3L, 61_000L);
        then(outbox).shouldHaveNoInteractions();
        then(members).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("HELP로 이미 올라간 미발송 사건은 상태를 다시 바꾸지 않고 최초 알림만 enqueue한다")
    void HELP로_이미_올라간_미발송_사건은_알림만_enqueue한다() {
        // given
        Incident incident = incident(null);
        given(incidents.claimInitialAlertIfDue(3L, 20L)).willReturn(1);
        given(incidents.findById(3L)).willReturn(Optional.of(incident));
        givenRecipients();

        // when
        boolean queued = escalation.escalateIfDue(3L, 20L);

        // then
        assertThat(queued).isTrue();
        then(incidents).should().escalateTimedOutIfDue(3L, 20L);
        then(incidents).should().claimInitialAlertIfDue(3L, 20L);
        then(outbox).should().enqueue(eq(2L), org.mockito.ArgumentMatchers.any(FcmSendDto.class));
        then(outbox).should().enqueue(eq(3L), org.mockito.ArgumentMatchers.any(FcmSendDto.class));
    }

    @Test
    @DisplayName("보호자가 비활성이면 그 보호자에게 최초 알림을 enqueue하지 않는다")
    void 보호자가_비활성이면_그_보호자에게_최초_알림을_enqueue하지_않는다() {
        // given
        Incident incident = incident(null);
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member activeGuardian = org.mockito.Mockito.mock(Member.class);
        Member inactiveGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership active = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership inactive = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(active, inactive));
        given(senior.getName()).willReturn("시니어");
        given(active.getGuardian()).willReturn(activeGuardian);
        given(activeGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(activeGuardian.getId()).willReturn(2L);
        given(inactive.getGuardian()).willReturn(inactiveGuardian);
        given(inactiveGuardian.getStatus()).willReturn(Status.INACTIVE);

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        then(outbox).should().enqueue(eq(2L), org.mockito.ArgumentMatchers.any(FcmSendDto.class));
        then(outbox).shouldHaveNoMoreInteractions();
    }

    private Incident incident(String decisionId) {
        return Incident.builder().incidentRef("inc-1").memberId(1L).decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1L).respondByMs(60_001L).build();
    }

    private Incident safeZoneIncident() {
        return Incident.builder().incidentRef("inc-1").memberId(1L)
                .kind(IncidentKind.SAFE_ZONE_EXIT).openedAtMs(1L).respondByMs(60_001L).build();
    }

    private void givenRecipients() {
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member firstGuardian = org.mockito.Mockito.mock(Member.class);
        Member secondGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership first = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership second = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(first, second));
        given(senior.getName()).willReturn("시니어");
        given(first.getGuardian()).willReturn(firstGuardian);
        given(firstGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(firstGuardian.getId()).willReturn(2L);
        given(second.getGuardian()).willReturn(secondGuardian);
        given(secondGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(secondGuardian.getId()).willReturn(3L);
    }

    private void assertMessage(FcmSendDto message, String decisionId) {
        assertThat(message.notificationType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(message.title()).isEqualTo("시니어 님의 심박 상태를 확인해주세요.");
        assertThat(message.content()).isEqualTo("평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요.");
        assertThat(message.eventId()).isEqualTo("inc-1");
        assertThat(message.relatedMemberId()).isEqualTo(1L);
        assertThat(message.seniorId()).isEqualTo(1L);
        assertThat(message.decisionId()).isEqualTo(decisionId);
    }

    private void assertSafeZoneMessage(FcmSendDto message) {
        assertThat(message.notificationType()).isEqualTo(NotificationType.SAFE_ZONE_EXITED);
        assertThat(message.title()).isEqualTo("시니어 님이 안심구역을 벗어났어요.");
        assertThat(message.content()).isEqualTo("현재 위치와 상태를 확인해주세요.");
        assertThat(message.eventId()).isEqualTo("inc-1");
        assertThat(message.decisionId()).isNull();
        assertThat(message.dataForEnqueue("inc-1").get("deepLink"))
                .isEqualTo("widyu-care://seniors/1/location");
    }
}
