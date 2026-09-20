package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.decision.DecisionRecord;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.application.FamilyAccessService;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentService 단위 테스트")
class IncidentServiceTest {

    private static final Long SENIOR_ID = 1023L;
    private static final Long GUARDIAN_ID = 2048L;
    private static final String DECISION_ID = "dec-7f3a";
    private static final String INCIDENT_REF = "inc-9c21";
    private static final String RUN_ID = "run-0f3a";
    private static final long OPENED_AT_MS = 1_760_000_000_000L;
    private static final long SELF_CHECK_MS = 45_000L;

    @Mock private IncidentRepository incidentRepository;
    @Mock private FcmService fcmService;
    @Mock private FamilyAccessService familyAccessService;

    @Test
    @DisplayName("위급 판정으로 사건을 열면 45초 마감과 본인확인 푸시가 함께 만들어진다")
    void 위급_판정으로_사건을_열면_45초_마감과_본인확인_푸시가_함께_만들어진다() {
        // given
        given(incidentRepository.findByDecisionId(DECISION_ID)).willReturn(Optional.empty());
        givenRepositoryReturnsSavedIncident();

        // when
        Incident opened = service().openForAlert(alertDecision(), IncidentKind.HR_ANOMALY);

        // then
        assertThat(opened.getMemberId()).isEqualTo(SENIOR_ID);
        assertThat(opened.getDecisionId()).isEqualTo(DECISION_ID);
        assertThat(opened.getRunId()).isEqualTo(RUN_ID);
        assertThat(opened.getKind()).isEqualTo(IncidentKind.HR_ANOMALY);
        assertThat(opened.getLevel()).isEqualTo("EMERGENCY");
        assertThat(opened.getIncidentRef()).startsWith("inc-");
        // 45초는 계약값이다(형식서 §3.7 SELF_CHECK_SEC). 검사기 L2가 ±1초로 잰다.
        assertThat(opened.getRespondByMs() - opened.getOpenedAtMs()).isEqualTo(SELF_CHECK_MS);
        assertThat(opened.getState()).isEqualTo(IncidentState.CHECKING);

        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(SENIOR_ID), message.capture());
        assertThat(message.getValue().fcmCategory()).isEqualTo(FcmCategory.INCIDENT_SELF_CHECK);
        assertThat(message.getValue().scheme()).isEqualTo("widyu://incident/" + opened.getIncidentRef());
        assertThat(message.getValue().emergency()).isTrue();
    }

    @Test
    @DisplayName("같은 판정으로 두 번 열면 사건이 하나만 남고 푸시도 한 번만 간다")
    void 같은_판정으로_두_번_열면_사건이_하나만_남는다() {
        // given
        Incident alreadyOpen = openIncident();
        given(incidentRepository.findByDecisionId(DECISION_ID)).willReturn(Optional.of(alreadyOpen));

        // when
        Incident opened = service().openForAlert(alertDecision(), IncidentKind.HR_ANOMALY);

        // then
        assertThat(opened).isSameAs(alreadyOpen);
        then(incidentRepository).should(never()).save(any());
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any());
    }

    @Test
    @DisplayName("본인이 OK로 답하면 사건이 닫히고 응답 시각과 경로가 남는다")
    void 본인이_OK로_답하면_사건이_닫힌다() {
        // given
        Incident incident = openIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));

        // when
        IncidentResponse response = service().respond(
                SENIOR_ID, INCIDENT_REF, new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH));

        // then
        assertThat(response.state()).isEqualTo(IncidentState.OK_CLOSED);
        assertThat(response.response()).isEqualTo(IncidentResponseValue.OK);
        assertThat(response.responseVia()).isEqualTo(ResponseVia.WATCH);
        assertThat(response.respondedAtMs()).isNotNull();
    }

    @Test
    @DisplayName("본인이 HELP로 답하면 사건이 ESCALATED로 올라간다")
    void 본인이_HELP로_답하면_사건이_올라간다() {
        // given
        Incident incident = openIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));

        // when
        IncidentResponse response = service().respond(
                SENIOR_ID, INCIDENT_REF, new IncidentRespondRequest(IncidentResponseValue.HELP, ResponseVia.PHONE));

        // then
        assertThat(response.state()).isEqualTo(IncidentState.ESCALATED);
        assertThat(response.response()).isEqualTo(IncidentResponseValue.HELP);
    }

    @Test
    @DisplayName("무응답으로 올라간 뒤 늦게 온 OK는 응답만 남기고 상태를 되돌리지 않는다")
    void 무응답으로_올라간_뒤_늦게_온_OK는_상태를_되돌리지_않는다() {
        // given
        Incident incident = escalatedIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));

        // when
        IncidentResponse response = service().respond(
                SENIOR_ID, INCIDENT_REF, new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH));

        // then
        // 보호자에게 이미 알림이 나간 사건을 「괜찮았던 일」로 되돌리면 그 알림을 설명할 자료가 없어진다.
        assertThat(response.state()).isEqualTo(IncidentState.ESCALATED);
        assertThat(response.response()).isEqualTo(IncidentResponseValue.OK);
    }

    @Test
    @DisplayName("이미 답한 사건에 다시 응답하면 예외가 발생한다")
    void 이미_답한_사건에_다시_응답하면_예외가_발생한다() {
        // given
        Incident incident = openIncident();
        incident.respond(IncidentResponseValue.OK, ResponseVia.WATCH, OPENED_AT_MS + 1_000L);
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));
        IncidentService service = service();
        IncidentRespondRequest request = new IncidentRespondRequest(IncidentResponseValue.HELP, ResponseVia.WATCH);

        // when & then
        assertThatThrownBy(() -> service.respond(SENIOR_ID, INCIDENT_REF, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_ALREADY_ANSWERED);
    }

    @Test
    @DisplayName("다른 회원이 응답하면 사건의 존재를 알리지 않고 찾을 수 없다고 한다")
    void 다른_회원이_응답하면_찾을_수_없다고_한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        IncidentService service = service();
        IncidentRespondRequest request = new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH);

        // when & then
        assertThatThrownBy(() -> service.respond(GUARDIAN_ID, INCIDENT_REF, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_NOT_FOUND);
    }

    @Test
    @DisplayName("보호자가 사후 판정을 넣으면 판정자와 119 신고 시각이 남고 사건이 종결된다")
    void 보호자가_사후_판정을_넣으면_사건이_종결된다() {
        // given
        Incident incident = escalatedIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));
        long calledAtMs = OPENED_AT_MS + 120_000L;

        // when
        IncidentResponse response = service().resolve(GUARDIAN_ID, INCIDENT_REF,
                new IncidentResolveRequest(IncidentOutcome.TRUE_EMERGENCY, calledAtMs));

        // then
        assertThat(response.state()).isEqualTo(IncidentState.RESOLVED);
        assertThat(response.outcome()).isEqualTo(IncidentOutcome.TRUE_EMERGENCY);
        assertThat(response.resolvedBy()).isEqualTo(GUARDIAN_ID);
        assertThat(response.resolvedAtMs()).isNotNull();
        assertThat(response.emergencyCalledAtMs()).isEqualTo(calledAtMs);
        then(familyAccessService).should().verifyFamilyAccess(GUARDIAN_ID, SENIOR_ID);
    }

    @Test
    @DisplayName("이미 판정한 사건을 다시 판정하면 예외가 발생한다")
    void 이미_판정한_사건을_다시_판정하면_예외가_발생한다() {
        // given
        Incident incident = escalatedIncident();
        incident.resolve(IncidentOutcome.FALSE_ALARM, GUARDIAN_ID, OPENED_AT_MS + 60_000L, null);
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));
        IncidentService service = service();
        IncidentResolveRequest request = new IncidentResolveRequest(IncidentOutcome.TRUE_EMERGENCY, null);

        // when & then
        // 라벨은 한 번만 붙인다. 덮어쓰면 어느 쪽이 사람의 판단이었는지 사후에 갈라지지 않는다.
        assertThatThrownBy(() -> service.resolve(GUARDIAN_ID, INCIDENT_REF, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_ALREADY_RESOLVED);
    }

    @Test
    @DisplayName("다른 가족의 보호자가 사후 판정을 시도하면 가족 접근 검증에서 막힌다")
    void 다른_가족의_보호자가_사후_판정을_시도하면_막힌다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        willThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .given(familyAccessService).verifyFamilyAccess(GUARDIAN_ID, SENIOR_ID);
        IncidentService service = service();
        IncidentResolveRequest request = new IncidentResolveRequest(IncidentOutcome.FALSE_ALARM, null);

        // when & then
        assertThatThrownBy(() -> service.resolve(GUARDIAN_ID, INCIDENT_REF, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("상태 필터가 값 집합 밖이면 잘못된 요청으로 막는다")
    void 상태_필터가_값_집합_밖이면_막는다() {
        // given
        IncidentService service = service();

        // when & then
        assertThatThrownBy(() -> service.findForSenior(SENIOR_ID, "CLOSED"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_REQUEST_INVALID);
    }

    @Test
    @DisplayName("응답 대기 목록을 조회하면 아직 답하지 않은 사건만 최근순으로 나온다")
    void 응답_대기_목록을_조회하면_아직_답하지_않은_사건만_나온다() {
        // given
        given(incidentRepository.findTop50ByMemberIdAndResponseIsNullOrderByOpenedAtMsDesc(SENIOR_ID))
                .willReturn(List.of(openIncident()));

        // when
        List<IncidentResponse> responses = service().findPending(SENIOR_ID);

        // then
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).incidentId()).isEqualTo(INCIDENT_REF);
        assertThat(responses.get(0).response()).isNull();
    }

    @Test
    @DisplayName("응답 DTO에는 심박 값과 판정 사유와 좌표가 없다")
    void 응답_DTO에는_심박_값과_판정_사유와_좌표가_없다() {
        // given
        RecordComponent[] components = IncidentResponse.class.getRecordComponents();

        // when
        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();

        // then
        // 사유와 심박 값이 사는 곳은 판정 기록 행뿐이다(정책 1.5.5·1.5.11).
        assertThat(names).doesNotContain("bpm", "hrBpm", "reason", "latitude", "longitude");
    }

    private IncidentService service() {
        return new IncidentService(incidentRepository, fcmService, familyAccessService,
                new SensorProperties(32_768, null, null, null, null,
                        new SensorProperties.Incident(45, 5000L)));
    }

    /** 저장 서비스는 받은 행을 그대로 돌려준다. 식별자는 서비스가 붙이므로 그대로 흘려보낸다. */
    private void givenRepositoryReturnsSavedIncident() {
        willAnswer(invocation -> invocation.getArgument(0)).given(incidentRepository).save(any());
    }

    private DecisionRecord alertDecision() {
        return DecisionRecord.builder()
                .decisionId(DECISION_ID)
                .memberId(SENIOR_ID)
                .runId(RUN_ID)
                .streamIdsUsed("[\"01j8zhr0000000000000001010\"]")
                .decisionAtMs(OPENED_AT_MS)
                .decisionOutput("ALERT")
                .deciderId("widyu-ai-hr")
                .deciderVersion("ver7")
                .inputCutoffMs(OPENED_AT_MS)
                .featureSupportEndMs(OPENED_AT_MS)
                .modelAvailableAtServerMaxMs(OPENED_AT_MS)
                .windowStartMs(OPENED_AT_MS)
                .windowEndMs(OPENED_AT_MS)
                .severity("EMERGENCY")
                .triggerBatchId("01j8zhr0000000000000001010")
                .build();
    }

    private Incident openIncident() {
        Incident incident = Incident.builder()
                .incidentRef(INCIDENT_REF)
                .memberId(SENIOR_ID)
                .runId(RUN_ID)
                .decisionId(DECISION_ID)
                .kind(IncidentKind.HR_ANOMALY)
                .level("EMERGENCY")
                .openedAtMs(OPENED_AT_MS)
                .respondByMs(OPENED_AT_MS + SELF_CHECK_MS)
                .build();
        incident.markChecking();
        return incident;
    }

    /** 무응답 스케줄러가 벌크 UPDATE로 올려 둔 상태 — 상태만 ESCALATED이고 응답은 비어 있다. */
    private Incident escalatedIncident() {
        Incident incident = openIncident();
        ReflectionTestUtils.setField(incident, "state", IncidentState.ESCALATED);
        return incident;
    }
}
