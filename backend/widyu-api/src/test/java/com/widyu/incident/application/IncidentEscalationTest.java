package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.entity.Status;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import com.widyu.incident.repository.IncidentGuardianResponseRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class IncidentEscalationTest {
    @Mock private IncidentRepository incidents;
    @Mock private IncidentGuardianResponseRepository guardianResponses;
    @Mock private SensorProperties sensorProperties;
    @Mock private FcmOutboxService outbox;
    @Mock private MemberRepository members;
    @Mock private FamilyMembershipRepository memberships;
    @Mock private SeniorProfileRepository seniorProfiles;
    @InjectMocks private IncidentEscalation escalation;

    @Test
    @DisplayName("멈춤 없이 2차 마감이 지나면 활성 보호자 전원에게 2차 알림을 예약한다")
    void 멈춤_없이_이차_마감이_지나면_활성_보호자_전원에게_예약한다() {
        // given
        Incident incident = dueActiveIncident("dec-1");
        Member senior = Mockito.mock(Member.class);
        Member leader = Mockito.mock(Member.class);
        Member other = Mockito.mock(Member.class);
        FamilyMembership leaderLink = Mockito.mock(FamilyMembership.class);
        FamilyMembership otherLink = Mockito.mock(FamilyMembership.class);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(incidents.findByIdForUpdate(3L)).willReturn(Optional.of(incident));
        given(incidents.claimSecondAlertIfDue(eq(3L), anyLong())).willReturn(1);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(leaderLink, otherLink));
        given(senior.getName()).willReturn("시니어");
        given(leaderLink.getGuardian()).willReturn(leader);
        given(otherLink.getGuardian()).willReturn(other);
        given(leader.getStatus()).willReturn(Status.ACTIVE);
        given(other.getStatus()).willReturn(Status.ACTIVE);
        given(leader.getId()).willReturn(2L);
        given(other.getId()).willReturn(3L);

        // when
        long before = System.currentTimeMillis();
        boolean sent = escalation.sendSecondAlertIfDue(3L);

        // then
        assertThat(sent).isTrue();
        ArgumentCaptor<Long> claimedAt = ArgumentCaptor.forClass(Long.class);
        then(incidents).should().claimSecondAlertIfDue(eq(3L), claimedAt.capture());
        assertThat(claimedAt.getValue()).isBetween(before, System.currentTimeMillis());
        ArgumentCaptor<FcmSendDto> messages = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), messages.capture());
        then(outbox).should().enqueue(eq(3L), messages.capture());
        assertThat(messages.getAllValues()).allSatisfy(message -> {
            assertThat(message.eventId()).isEqualTo("inc-1");
            assertThat(message.dataForEnqueue("inc-1"))
                    .containsEntry("deliveryStage", "SECOND_ALERT")
                    .containsEntry("safetyEventId", "inc-1")
                    .containsEntry("incidentRef", "inc-1")
                    .containsEntry("decisionId", "dec-1")
                    .containsEntry("deepLink", "/location?seniorId=1");
        });
    }

    @Test
    @DisplayName("2차 마감 전에 멈춤이 생기면 2차 게이트를 취소한다")
    void 이차_마감_전에_멈춤이_생기면_게이트를_취소한다() {
        // given
        Incident incident = dueActiveIncident(null);
        given(incidents.findByIdForUpdate(3L)).willReturn(Optional.of(incident));
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(guardianResponses.existsByIncidentId(3L)).willReturn(true);

        // when
        boolean sent = escalation.sendSecondAlertIfDue(3L);

        // then
        assertThat(sent).isFalse();
        then(incidents).should().cancelSecondAlertIfPending(eq(3L), anyLong());
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("마지막 감지 뒤 5분이 지나면 2차 알림을 취소한다")
    void 마지막_감지_뒤_오분이_지나면_이차_알림을_취소한다() {
        // given
        long nowMs = System.currentTimeMillis();
        Incident incident = Incident.builder().incidentRef("inc-1").memberId(1L)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(nowMs - 420_000L)
                .respondByMs(nowMs - 360_000L).build();
        incident.markChecking();
        incident.markInitialAlertSent(nowMs - 400_000L);
        org.springframework.test.util.ReflectionTestUtils.setField(
                incident, "lastDetectedAtMs", nowMs - 360_000L);
        org.springframework.test.util.ReflectionTestUtils.setField(incident, "id", 3L);
        given(incidents.findByIdForUpdate(3L)).willReturn(Optional.of(incident));
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));

        // when
        long before = System.currentTimeMillis();
        boolean sent = escalation.sendSecondAlertIfDue(3L);

        // then
        assertThat(sent).isFalse();
        ArgumentCaptor<Long> cancelledAt = ArgumentCaptor.forClass(Long.class);
        then(incidents).should().cancelSecondAlertIfPending(eq(3L), cancelledAt.capture());
        assertThat(cancelledAt.getValue()).isBetween(before, System.currentTimeMillis());
        then(outbox).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = IncidentState.class, names = {"OK_CLOSED", "RESOLVED"})
    @DisplayName("종료된 심박 사건의 2차 마감이 지나면 알림을 취소한다")
    void 종료된_심박_사건의_이차_마감이_지나면_알림을_취소한다(IncidentState state) {
        // given
        Incident incident = incident(null);
        incident.markInitialAlertSent(20L);
        org.springframework.test.util.ReflectionTestUtils.setField(incident, "id", 3L);
        org.springframework.test.util.ReflectionTestUtils.setField(incident, "state", state);
        given(incidents.findByIdForUpdate(3L)).willReturn(Optional.of(incident));
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));

        // when
        boolean sent = escalation.sendSecondAlertIfDue(3L);

        // then
        assertThat(sent).isFalse();
        then(incidents).should().cancelSecondAlertIfPending(eq(3L), anyLong());
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("플래그 OFF에서 심박 사건을 열면 현재 방장 한 명에게 S04를 enqueue한다")
    void 플래그_OFF에서_심박_사건을_열면_현재_방장에게만_S04를_enqueue한다() {
        // given
        Incident incident = incident("dec-1");
        givenHeartRecipients();

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        assertThat(incident.getSecondAlertDueAtMs()).isEqualTo(180_020L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).shouldHaveNoMoreInteractions();
        assertMessage(message.getValue(), "dec-1");
    }

    @Test
    @DisplayName("과거 안심구역 사건을 즉시 알리려 하면 보호자 알림 없이 예외가 발생한다")
    void 과거_안심구역_사건을_즉시_알리려_하면_예외가_발생한다() {
        // given
        Incident incident = safeZoneIncident();

        // when / then
        assertThatThrownBy(() -> escalation.sendImmediately(incident, 20L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(incident.getInitialAlertSentAtMs()).isNull();
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("플래그 ON 사건이 만료되면 상태 전환 뒤 최초 알림을 한 번 enqueue한다")
    void 플래그_ON_사건이_만료되면_전환_뒤_알림을_한_번_enqueue한다() {
        // given
        Incident incident = incident(null);
        given(incidents.escalateTimedOutIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.claimInitialAlertIfDue(3L, 61_000L)).willReturn(1);
        given(incidents.findById(3L)).willReturn(Optional.of(incident));
        givenHeartRecipients();

        // when
        boolean queued = escalation.escalateIfDue(3L, 61_000L);

        // then
        assertThat(queued).isTrue();
        then(incidents).should().escalateTimedOutIfDue(3L, 61_000L);
        then(incidents).should().claimInitialAlertIfDue(3L, 61_000L);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(2L), message.capture());
        then(outbox).shouldHaveNoMoreInteractions();
        assertMessage(message.getValue(), null);
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
        givenHeartRecipients();

        // when
        boolean queued = escalation.escalateIfDue(3L, 20L);

        // then
        assertThat(queued).isTrue();
        then(incidents).should().escalateTimedOutIfDue(3L, 20L);
        then(incidents).should().claimInitialAlertIfDue(3L, 20L);
        then(outbox).should().enqueue(eq(2L), org.mockito.ArgumentMatchers.any(FcmSendDto.class));
        then(outbox).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("방장이 비활성이면 활성 비방장이 있어도 최초 알림 없이 게이트를 기록한다")
    void 방장이_비활성이면_활성_비방장이_있어도_게이트만_기록한다(CapturedOutput output) {
        // given
        Incident incident = incident(null);
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member inactiveGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership active = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership inactive = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(active, inactive));
        given(senior.getName()).willReturn("시니어");
        given(inactive.isLeader()).willReturn(true);
        given(inactive.getGuardian()).willReturn(inactiveGuardian);
        given(inactiveGuardian.getStatus()).willReturn(Status.INACTIVE);

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        then(outbox).shouldHaveNoInteractions();
        assertThat(output).contains("보호자 최초 안전 알림 수신자 없음: incidentRef=inc-1");
    }

    @Test
    @DisplayName("가족에 방장이 없으면 최초 알림을 건너뛰고 게이트를 기록한다")
    void 가족에_방장이_없으면_최초_알림_없이_게이트를_기록한다(CapturedOutput output) {
        // given
        Incident incident = incident(null);
        Member senior = org.mockito.Mockito.mock(Member.class);
        FamilyMembership nonLeader = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(nonLeader));
        given(senior.getName()).willReturn("시니어");

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        then(outbox).shouldHaveNoInteractions();
        assertThat(output).contains("보호자 최초 안전 알림 수신자 없음: incidentRef=inc-1");
    }

    @Test
    @DisplayName("활성 방장이 둘이면 membership ID가 작은 방장에게만 보내고 WARN을 남긴다")
    void 활성_방장이_둘이면_작은_membership_ID의_방장에게만_보낸다(CapturedOutput output) {
        // given
        Incident incident = incident(null);
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member firstGuardian = org.mockito.Mockito.mock(Member.class);
        Member secondGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership first = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership second = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(first, second));
        given(senior.getName()).willReturn("시니어");
        given(first.isLeader()).willReturn(true);
        given(first.getGuardian()).willReturn(firstGuardian);
        given(firstGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(first.getId()).willReturn(12L);
        given(second.isLeader()).willReturn(true);
        given(second.getGuardian()).willReturn(secondGuardian);
        given(secondGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(second.getId()).willReturn(11L);
        given(secondGuardian.getId()).willReturn(3L);

        // when
        escalation.sendImmediately(incident, 20L);

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(20L);
        then(outbox).should().enqueue(eq(3L), org.mockito.ArgumentMatchers.any(FcmSendDto.class));
        then(outbox).shouldHaveNoMoreInteractions();
        assertThat(output).contains("보호자 최초 안전 알림 복수 방장: incidentRef=inc-1, familyId=10");
    }

    private Incident incident(String decisionId) {
        return Incident.builder().incidentRef("inc-1").memberId(1L).decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1L).respondByMs(60_001L).build();
    }

    private Incident dueActiveIncident(String decisionId) {
        long nowMs = System.currentTimeMillis();
        Incident incident = Incident.builder().incidentRef("inc-1").memberId(1L).decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(nowMs - 240_000L)
                .respondByMs(nowMs - 180_000L).build();
        incident.markChecking();
        incident.markInitialAlertSent(nowMs - 181_000L);
        org.springframework.test.util.ReflectionTestUtils.setField(incident, "id", 3L);
        return incident;
    }

    private Incident safeZoneIncident() {
        return Incident.builder().incidentRef("inc-1").memberId(1L)
                .kind(IncidentKind.SAFE_ZONE_EXIT).openedAtMs(1L).respondByMs(60_001L).build();
    }

    private void givenHeartRecipients() {
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member leaderGuardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership leader = org.mockito.Mockito.mock(FamilyMembership.class);
        FamilyMembership nonLeader = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfiles.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(leader, nonLeader));
        given(senior.getName()).willReturn("시니어");
        given(leader.isLeader()).willReturn(true);
        given(leader.getGuardian()).willReturn(leaderGuardian);
        given(leaderGuardian.getStatus()).willReturn(Status.ACTIVE);
        given(leaderGuardian.getId()).willReturn(2L);
    }

    private void assertMessage(FcmSendDto message, String decisionId) {
        assertThat(message.notificationType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(message.title()).isEqualTo("시니어 님의 심박 상태를 확인해주세요.");
        assertThat(message.content()).isEqualTo("평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요.");
        assertThat(message.eventId()).isEqualTo("inc-1");
        assertThat(message.relatedMemberId()).isEqualTo(1L);
        assertThat(message.seniorId()).isEqualTo(1L);
        assertThat(message.decisionId()).isEqualTo(decisionId);
        assertThat(message.dataForEnqueue("inc-1"))
                .containsEntry("deliveryStage", "INITIAL_ALERT")
                .containsEntry("safetyEventId", "inc-1")
                .containsEntry("eventId", "inc-1")
                .containsEntry("foregroundPresentation", "MODAL");
        if (decisionId != null) {
            assertThat(message.dataForEnqueue("inc-1")).containsEntry("decisionId", decisionId);
        } else {
            assertThat(message.dataForEnqueue("inc-1")).doesNotContainKey("decisionId");
        }
    }

}
