package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentResponseAttempt;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseAttemptOutcome;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.dto.request.HelpRequestCreateRequest;
import com.widyu.incident.dto.response.HelpRequestResult;
import com.widyu.incident.repository.IncidentGuardianResponseRepository;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.incident.repository.IncidentResponseAttemptRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class IncidentHelpRequestServiceTest {
    @Mock private IncidentRepository incidents;
    @Mock private IncidentGuardianResponseRepository guardianResponses;
    @Mock private IncidentResponseAttemptRepository attempts;
    @Mock private IncidentRejectedAttemptRecorder rejected;
    @Mock private IncidentRespondTransaction respondTransaction;
    @Mock private FcmService fcm;
    @Mock private FamilyAccessService familyAccess;
    @Mock private SensorProperties properties;
    @Mock private MemberRepository members;
    @Mock private IncidentEscalation escalation;
    @Mock private FcmOutboxService outbox;
    @Mock private FcmNotificationRepository notifications;
    @Mock private SeniorProfileRepository seniorProfiles;
    @Mock private FamilyMembershipRepository memberships;
    @Mock private FollowupCardService followups;
    @Mock private EntityManager entityManager;
    @InjectMocks private IncidentService service;

    @Test
    @DisplayName("워치로 처음 도움을 요청하면 본인확인 없이 열린 사건을 만든다")
    void 워치로_처음_요청하면_본인확인_없이_사건을_만든다() {
        // given
        Member senior = givenActiveSenior();
        given(senior.getId()).willReturn(1L);
        given(incidents.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        // when
        HelpRequestResult result = service.openHelpRequest(1L, null);

        // then
        assertThat(result.kind()).isEqualTo(IncidentKind.SELF_HELP_REQUEST);
        assertThat(result.state()).isEqualTo(IncidentState.OPEN);
        assertThat(result.attached()).isFalse();
        ArgumentCaptor<Incident> saved = ArgumentCaptor.forClass(Incident.class);
        then(incidents).should().save(saved.capture());
        assertThat(saved.getValue().getRespondByMs()).isEqualTo(saved.getValue().getOpenedAtMs());
        assertThat(saved.getValue().getDecisionId()).isNull();
        then(fcm).shouldHaveNoInteractions();
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("열린 도움 요청이 있을 때 다시 요청하면 같은 사건을 반환한다")
    void 열린_도움_요청에_다시_요청하면_같은_사건을_반환한다() {
        // given
        givenActiveSenior();
        Incident existing = helpIncident();
        given(incidents.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(1L), eq(IncidentKind.HR_ANOMALY), any())).willReturn(Optional.empty());
        given(incidents.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(1L), eq(IncidentKind.SELF_HELP_REQUEST), any())).willReturn(Optional.of(existing));

        // when
        HelpRequestResult result = service.openHelpRequest(1L,
                new HelpRequestCreateRequest(123L, ResponseVia.WATCH));

        // then
        assertThat(result.incidentId()).isEqualTo("inc-help");
        assertThat(result.attached()).isTrue();
        then(incidents).should(never()).save(any(Incident.class));
    }

    @Test
    @DisplayName("열린 심박 사건에 첫 도움 요청이 오면 HELP 이력을 남긴다")
    void 열린_심박_사건에_첫_도움_요청이_오면_HELP_이력을_남긴다() {
        // given
        givenActiveSenior();
        Incident heart = heartIncident();
        given(properties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(incidents.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(1L), eq(IncidentKind.HR_ANOMALY), any())).willReturn(Optional.of(heart));
        given(incidents.respond(eq("inc-heart"), eq(1L), eq(IncidentResponseValue.HELP),
                eq(ResponseVia.WATCH), anyLong(), eq(123L), eq(IncidentState.ESCALATED))).willReturn(1);
        given(incidents.findById(3L)).willReturn(Optional.of(heart));

        // when
        HelpRequestResult result = service.openHelpRequest(1L,
                new HelpRequestCreateRequest(123L, ResponseVia.WATCH));

        // then
        assertThat(result.incidentId()).isEqualTo("inc-heart");
        assertThat(result.attached()).isTrue();
        ArgumentCaptor<IncidentResponseAttempt> saved = ArgumentCaptor.forClass(IncidentResponseAttempt.class);
        then(attempts).should().save(saved.capture());
        assertThat(saved.getValue().getResponse()).isEqualTo(IncidentResponseValue.HELP);
        assertThat(saved.getValue().getResponseVia()).isEqualTo(ResponseVia.WATCH);
        assertThat(saved.getValue().getOutcome()).isEqualTo(ResponseAttemptOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("이미 답한 심박 사건에 워치 요청이 오면 첫 응답을 보존하고 중복 이력을 남긴다")
    void 이미_답한_심박에_요청하면_첫_응답을_보존하고_중복_이력을_남긴다() {
        // given
        givenActiveSenior();
        Incident heart = heartIncident();
        ReflectionTestUtils.setField(heart, "response", IncidentResponseValue.OK);
        given(properties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(incidents.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(1L), eq(IncidentKind.HR_ANOMALY), any())).willReturn(Optional.of(heart));
        given(incidents.findById(3L)).willReturn(Optional.of(heart));

        // when
        HelpRequestResult result = service.openHelpRequest(1L, null);

        // then
        assertThat(result.attached()).isTrue();
        assertThat(heart.getResponse()).isEqualTo(IncidentResponseValue.OK);
        ArgumentCaptor<IncidentResponseAttempt> saved = ArgumentCaptor.forClass(IncidentResponseAttempt.class);
        then(attempts).should().save(saved.capture());
        assertThat(saved.getValue().getOutcome()).isEqualTo(ResponseAttemptOutcome.DUPLICATE);
    }

    @Test
    @DisplayName("도움 요청을 취소하면 실제 수신자에게 한 번씩 안내한다")
    void 도움_요청을_취소하면_실제_수신자에게_안내한다() {
        // given
        Member senior = givenActiveSenior();
        given(senior.getName()).willReturn("시니어");
        Incident opened = helpIncident();
        ReflectionTestUtils.setField(opened, "id", 4L);
        Incident cancelled = helpIncident();
        ReflectionTestUtils.setField(cancelled, "state", IncidentState.CANCELLED_BY_SENIOR);
        cancelled.endSituation(50L);
        given(incidents.findByIncidentRef("inc-help"))
                .willReturn(Optional.of(opened), Optional.of(cancelled));
        given(incidents.findByIdForUpdate(4L)).willReturn(Optional.of(opened));
        given(incidents.cancelHelpRequest(eq("inc-help"), eq(1L), anyLong())).willReturn(1);
        given(notifications.findRecipientMemberIdsByEventId("inc-help")).willReturn(List.of(21L, 22L));

        // when
        var result = service.cancelHelpRequest(1L, "inc-help");

        // then
        assertThat(result.state()).isEqualTo(IncidentState.CANCELLED_BY_SENIOR);
        assertThat(result.situationEndedAtMs()).isEqualTo(50L);
        ArgumentCaptor<FcmSendDto> sent = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outbox).should().enqueue(eq(21L), sent.capture());
        then(outbox).should().enqueue(eq(22L), sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(message -> {
            assertThat(message.notificationType()).isEqualTo(NotificationType.SELF_HELP_REQUEST_CANCELLED);
            assertThat(message.eventId()).isEqualTo("inc-help:CX");
        });
    }

    @Test
    @DisplayName("보호자가 도움을 요청하면 권한 예외가 발생한다")
    void 보호자가_도움을_요청하면_권한_예외가_발생한다() {
        // given
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(members.findByIdForUpdate(1L)).willReturn(Optional.of(guardian));
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);

        // when / then
        assertThatThrownBy(() -> service.openHelpRequest(1L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("워치 외 경로로 도움을 요청하면 잘못된 요청으로 거절한다")
    void 워치_외_경로로_요청하면_거절한다() {
        // given
        givenActiveSenior();

        // when / then
        assertThatThrownBy(() -> service.openHelpRequest(1L,
                new HelpRequestCreateRequest(123L, ResponseVia.PHONE)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_REQUEST_INVALID);
        then(incidents).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다른 시니어의 도움 요청을 취소하면 사건 없음으로 응답한다")
    void 다른_시니어_사건을_취소하면_사건_없음으로_응답한다() {
        // given
        givenActiveSenior();
        Incident otherSeniorIncident = Incident.builder().incidentRef("inc-other").memberId(2L)
                .kind(IncidentKind.SELF_HELP_REQUEST).openedAtMs(100L).respondByMs(100L).build();
        given(incidents.findByIncidentRef("inc-other")).willReturn(Optional.of(otherSeniorIncident));

        // when / then
        assertThatThrownBy(() -> service.cancelHelpRequest(1L, "inc-other"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_NOT_FOUND);
        then(incidents).should(never()).cancelHelpRequest(any(), any(), anyLong());
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("열린 도움 요청이 아니면 취소 안내 없이 충돌로 응답한다")
    void 열린_도움_요청이_아니면_충돌로_응답한다() {
        // given
        givenActiveSenior();
        Incident cancelled = helpIncident();
        ReflectionTestUtils.setField(cancelled, "id", 4L);
        ReflectionTestUtils.setField(cancelled, "state", IncidentState.CANCELLED_BY_SENIOR);
        cancelled.endSituation(50L);
        given(incidents.findByIncidentRef("inc-help")).willReturn(Optional.of(cancelled));
        given(incidents.findByIdForUpdate(4L)).willReturn(Optional.of(cancelled));

        // when / then
        assertThatThrownBy(() -> service.cancelHelpRequest(1L, "inc-help"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_HELP_REQUEST_NOT_OPEN);
        then(notifications).shouldHaveNoInteractions();
        then(outbox).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("심박 사건을 도움 요청 취소 API로 취소하면 충돌로 응답한다")
    void 심박_사건을_도움_요청으로_취소하면_충돌로_응답한다() {
        // given
        givenActiveSenior();
        Incident heart = heartIncident();
        given(incidents.findByIncidentRef("inc-heart")).willReturn(Optional.of(heart));
        given(incidents.findByIdForUpdate(3L)).willReturn(Optional.of(heart));

        // when / then
        assertThatThrownBy(() -> service.cancelHelpRequest(1L, "inc-heart"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_HELP_REQUEST_NOT_OPEN);
        then(notifications).shouldHaveNoInteractions();
        then(outbox).shouldHaveNoInteractions();
    }

    private Member givenActiveSenior() {
        Member senior = org.mockito.Mockito.mock(Member.class);
        given(members.findByIdForUpdate(1L)).willReturn(Optional.of(senior));
        given(senior.getType()).willReturn(MemberType.SENIOR);
        given(senior.getStatus()).willReturn(Status.ACTIVE);
        return senior;
    }

    private Incident helpIncident() {
        return Incident.builder().incidentRef("inc-help").memberId(1L)
                .kind(IncidentKind.SELF_HELP_REQUEST).openedAtMs(100L).respondByMs(100L).build();
    }

    private Incident heartIncident() {
        long nowMs = System.currentTimeMillis();
        Incident heart = Incident.builder().incidentRef("inc-heart").memberId(1L)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(nowMs - 1_000L)
                .respondByMs(nowMs + 59_000L).build();
        heart.markChecking();
        ReflectionTestUtils.setField(heart, "id", 3L);
        return heart;
    }
}
