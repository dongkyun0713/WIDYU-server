package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.decision.DecisionRecord;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.GuardianResponseType;
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
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.Member;
import com.widyu.member.FamilyMembership;
import com.widyu.member.MemberType;
import com.widyu.global.entity.Status;
import com.widyu.location.realtime.dto.StayInfo;
import java.time.LocalDateTime;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
    private static final long SELF_CHECK_MS = 60_000L;

    @Mock private IncidentRepository incidentRepository;
    @Mock private FcmService fcmService;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private MemberRepository memberRepository;
    @Mock private IncidentEscalation incidentEscalation;
    @Mock private FcmOutboxService outboxService;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private FollowupCardService followupCardService;

    @Test
    @DisplayName("이탈 이벤트 처리 시 최신 위치가 안심구역 안이면 사건과 S02와 S05를 만들지 않는다")
    void 이탈_이벤트_처리_시_최신_위치가_안이면_사건과_알림을_만들지_않는다() {
        // given
        given(memberRepository.findByIdForUpdate(SENIOR_ID)).willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:" + SENIOR_ID)).willReturn(
                new StayInfo(37.0, 127.0, LocalDateTime.now(), "HOME", "집"));

        // when
        Incident opened = service().openForAlert(SENIOR_ID, IncidentKind.SAFE_ZONE_EXIT);

        // then
        assertThat(opened).isNull();
        then(incidentRepository).shouldHaveNoInteractions();
        then(fcmService).shouldHaveNoInteractions();
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("위급 판정으로 사건을 열면 60초 마감과 본인확인 푸시가 함께 만들어진다")
    void 위급_판정으로_사건을_열면_60초_마감과_본인확인_푸시가_함께_만들어진다() {
        // given
        given(memberRepository.findByIdForUpdate(SENIOR_ID)).willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));
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
        // 제품 본인확인 마감은 서버 사건 생성부터 60초다.
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
        given(memberRepository.findByIdForUpdate(SENIOR_ID)).willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));
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
    @DisplayName("마지막 귀속 판정이 5분 뒤 재시도되면 새 사건과 최초 알림을 만들지 않는다")
    void 마지막_귀속_판정이_오분_뒤_재시도되면_새_사건과_알림을_만들지_않는다() {
        // given
        given(memberRepository.findByIdForUpdate(SENIOR_ID))
                .willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));
        Incident previous = openIncident();
        ReflectionTestUtils.setField(previous, "decisionId", "dec-first");
        ReflectionTestUtils.setField(previous, "lastDecisionId", DECISION_ID);
        ReflectionTestUtils.setField(previous, "lastDetectedAtMs", System.currentTimeMillis() - 301_000L);
        ReflectionTestUtils.setField(previous, "detectionCount", 2);
        given(incidentRepository.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(SENIOR_ID), eq(IncidentKind.HR_ANOMALY), any())).willReturn(Optional.of(previous));

        // when
        Incident repeated = service().openForAlert(alertDecision(), IncidentKind.HR_ANOMALY);

        // then
        assertThat(repeated).isSameAs(previous);
        assertThat(previous.getDetectionCount()).isEqualTo(2);
        assertThat(previous.getSituationEndedAtMs()).isNull();
        then(incidentRepository).should(never()).save(any());
        then(incidentRepository).should(never()).attachDetection(any(), any(), anyLong());
        then(fcmService).shouldHaveNoInteractions();
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("열린 사건이 없어도 마지막 판정이 재시도되면 최근 닫힌 사건을 반환한다")
    void 열린_사건이_없어도_마지막_판정이_재시도되면_최근_닫힌_사건을_반환한다() {
        // given
        given(memberRepository.findByIdForUpdate(SENIOR_ID))
                .willReturn(Optional.of(org.mockito.Mockito.mock(Member.class)));
        Incident closed = openIncident();
        ReflectionTestUtils.setField(closed, "decisionId", "dec-first");
        ReflectionTestUtils.setField(closed, "lastDecisionId", DECISION_ID);
        ReflectionTestUtils.setField(closed, "state", IncidentState.OK_CLOSED);
        given(incidentRepository.findFirstByMemberIdAndKindOrderByOpenedAtMsDesc(SENIOR_ID,
                IncidentKind.HR_ANOMALY)).willReturn(Optional.of(closed));

        // when
        Incident repeated = service().openForAlert(alertDecision(), IncidentKind.HR_ANOMALY);

        // then
        assertThat(repeated).isSameAs(closed);
        then(incidentRepository).should(never()).save(any());
        then(fcmService).shouldHaveNoInteractions();
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("낙상 판정으로 사건을 열면 기존 일반 본인확인 문구를 유지한다")
    void 낙상_판정으로_사건을_열면_일반_본인확인_문구를_유지한다() {
        // given
        given(incidentRepository.findByDecisionId(DECISION_ID)).willReturn(Optional.empty());
        givenRepositoryReturnsSavedIncident();

        // when
        service().openForAlert(alertDecision(), IncidentKind.FALL_SUSPECTED);

        // then
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(SENIOR_ID), message.capture());
        assertThat(message.getValue().title()).isEqualTo("괜찮으세요?");
        assertThat(message.getValue().notificationType()).isNull();
    }

    @Test
    @DisplayName("본인이 OK로 답하면 마감 안 종료 상태로 응답 저장을 요청하고 저장된 값을 돌려준다")
    void 본인이_OK로_답하면_마감_안_종료_상태로_응답_저장을_요청한다() {
        // given
        Incident incident = openIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(incident));
        givenRespondUpdates(1);

        // when
        IncidentResponse response = service().respond(
                SENIOR_ID, INCIDENT_REF, new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH));

        // then
        // 마감을 넘겼는지는 행을 보는 UPDATE가 정한다. 서비스는 마감 안에 답했을 때의 상태만 넘긴다.
        then(incidentRepository).should().respond(eq(INCIDENT_REF), eq(SENIOR_ID),
                eq(IncidentResponseValue.OK), eq(ResponseVia.WATCH), anyLong(), isNull(), eq(IncidentState.OK_CLOSED));
        assertThat(response.incidentId()).isEqualTo(INCIDENT_REF);
    }

    @Test
    @DisplayName("심박 사건에 기한 안 OK로 답하면 보호자별 S08을 같은 사건 키로 예약한다")
    void 심박_사건에_기한_안_OK로_답하면_보호자별_S08을_예약한다() {
        // given
        Incident checking = openIncident();
        Incident answered = openIncident();
        ReflectionTestUtils.setField(answered, "state", IncidentState.OK_CLOSED);
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(checking), Optional.of(answered));
        givenRespondUpdates(1);
        Member senior = org.mockito.Mockito.mock(Member.class);
        given(senior.getId()).willReturn(SENIOR_ID);
        given(senior.getName()).willReturn("시니어");
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));
        FamilyMembership membership = org.mockito.Mockito.mock(FamilyMembership.class);
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(membership.getGuardian()).willReturn(guardian);
        given(guardian.getStatus()).willReturn(Status.ACTIVE);
        given(guardian.getId()).willReturn(GUARDIAN_ID);
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(7L)).willReturn(List.of(membership));

        // when
        IncidentResponse response = service().respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.PHONE));

        // then
        assertThat(response.state()).isEqualTo(IncidentState.OK_CLOSED);
        then(followupCardService).should().issueIfEnabled(answered);
        assertThat(answered.getOkNoticeSentAtMs()).isNotNull();
        assertThat(answered.getSituationEndedAtMs()).isNotNull();
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should().enqueue(eq(GUARDIAN_ID), message.capture());
        assertThat(message.getValue().notificationType()).isEqualTo(NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART);
        assertThat(message.getValue().eventId()).isEqualTo(INCIDENT_REF + ":OK");
        assertThat(message.getValue().title()).isEqualTo("시니어 님의 신체 지표가 평소와 달랐어요.");
        assertThat(message.getValue().emergency()).isFalse();
    }

    @Test
    @DisplayName("안심구역 사건에 기한 안 OK로 답하면 S09를 예약하고 재진입 전 상황은 유지한다")
    void 안심구역_사건에_기한_안_OK로_답하면_S09를_예약한다() {
        // given
        Incident checking = openIncident();
        Incident answered = openIncident();
        ReflectionTestUtils.setField(checking, "kind", IncidentKind.SAFE_ZONE_EXIT);
        ReflectionTestUtils.setField(answered, "kind", IncidentKind.SAFE_ZONE_EXIT);
        ReflectionTestUtils.setField(answered, "state", IncidentState.OK_CLOSED);
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(checking), Optional.of(answered));
        givenRespondUpdates(1);
        Member senior = org.mockito.Mockito.mock(Member.class);
        given(senior.getId()).willReturn(SENIOR_ID);
        given(senior.getName()).willReturn("시니어");
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));
        FamilyMembership membership = org.mockito.Mockito.mock(FamilyMembership.class);
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(membership.getGuardian()).willReturn(guardian);
        given(guardian.getStatus()).willReturn(Status.ACTIVE);
        given(guardian.getId()).willReturn(GUARDIAN_ID);
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(7L)).willReturn(List.of(membership));

        // when
        service().respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.PHONE));

        // then
        assertThat(answered.getOkNoticeSentAtMs()).isNotNull();
        assertThat(answered.getSituationEndedAtMs()).isNull();
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);
        then(outboxService).should().enqueue(eq(GUARDIAN_ID), message.capture());
        assertThat(message.getValue().notificationType())
                .isEqualTo(NotificationType.SAFETY_SENIOR_OK_NOTICE_SAFE_ZONE);
        assertThat(message.getValue().eventId()).isEqualTo(INCIDENT_REF + ":OK");
    }

    @Test
    @DisplayName("늦은 OK가 ESCALATED로 저장되면 정보성 알림을 만들지 않는다")
    void 늦은_OK가_ESCALATED로_저장되면_정보성_알림을_만들지_않는다() {
        // given
        Incident checking = openIncident();
        Incident escalated = escalatedIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(checking), Optional.of(escalated));
        givenRespondUpdates(1);

        // when
        service().respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.PHONE));

        // then
        assertThat(escalated.getOkNoticeSentAtMs()).isNull();
        then(outboxService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("같은 가족 보호자가 실제 연락을 기록하면 첫 행동을 반환한다")
    void 같은_가족_보호자가_실제_연락을_기록하면_첫_행동을_반환한다() {
        // given
        Incident incident = openIncident();
        Incident recorded = openIncident();
        ReflectionTestUtils.setField(recorded, "guardianResponseType", GuardianResponseType.MESSAGE_SENT);
        ReflectionTestUtils.setField(recorded, "guardianResponseAtMs", OPENED_AT_MS + 10_000L);
        ReflectionTestUtils.setField(recorded, "guardianResponseBy", GUARDIAN_ID);
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(incident), Optional.of(recorded));
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(guardian.getStatus()).willReturn(Status.ACTIVE);
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardian));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(7L, GUARDIAN_ID)).willReturn(true);
        given(incidentRepository.recordGuardianResponse(eq(INCIDENT_REF), eq(GuardianResponseType.MESSAGE_SENT),
                anyLong(), eq(GUARDIAN_ID))).willReturn(1);

        // when
        var response = service().recordGuardianResponse(GUARDIAN_ID, INCIDENT_REF,
                GuardianResponseType.MESSAGE_SENT);

        // then
        assertThat(response.guardianResponseType()).isEqualTo(GuardianResponseType.MESSAGE_SENT);
        assertThat(response.guardianResponseBy()).isEqualTo(GUARDIAN_ID);
    }

    @Test
    @DisplayName("다른 가족 보호자가 반응을 기록하면 사건을 찾을 수 없다고 한다")
    void 다른_가족_보호자가_반응을_기록하면_사건을_찾을_수_없다고_한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardian));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));

        // when & then
        assertThatThrownBy(() -> service().recordGuardianResponse(GUARDIAN_ID, INCIDENT_REF,
                GuardianResponseType.MESSAGE_SENT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_NOT_FOUND);
        then(incidentRepository).should(never()).recordGuardianResponse(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("비활성 보호자가 반응을 기록하면 권한 오류로 거부한다")
    void 비활성_보호자가_반응을_기록하면_권한_오류로_거부한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(guardian.getStatus()).willReturn(Status.INACTIVE);
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardian));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(7L, GUARDIAN_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service().recordGuardianResponse(GUARDIAN_ID, INCIDENT_REF,
                GuardianResponseType.MESSAGE_SENT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(incidentRepository).should(never()).recordGuardianResponse(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("타 가족의 비활성 보호자가 반응을 기록하면 사건을 찾을 수 없다고 한다")
    void 타_가족의_비활성_보호자가_반응을_기록하면_사건을_찾을_수_없다고_한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        Member guardian = Member.createMember(MemberType.GUARDIAN, "외부 보호자", "01000002048");
        ReflectionTestUtils.setField(guardian, "status", Status.INACTIVE);
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardian));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));

        // when & then
        assertThatThrownBy(() -> service().recordGuardianResponse(GUARDIAN_ID, INCIDENT_REF,
                GuardianResponseType.MESSAGE_SENT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_NOT_FOUND);
        then(incidentRepository).should(never()).recordGuardianResponse(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("시니어가 보호자 반응을 기록하면 사건을 찾을 수 없다고 한다")
    void 시니어가_보호자_반응을_기록하면_사건을_찾을_수_없다고_한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        Member senior = org.mockito.Mockito.mock(Member.class);
        given(senior.getType()).willReturn(MemberType.SENIOR);
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(senior));

        // when & then
        assertThatThrownBy(() -> service().recordGuardianResponse(SENIOR_ID, INCIDENT_REF,
                GuardianResponseType.MESSAGE_SENT))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_NOT_FOUND);
        then(incidentRepository).should(never()).recordGuardianResponse(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("보호자 반응이 이미 있으면 두 번째 요청은 409로 거부한다")
    void 보호자_반응이_이미_있으면_두_번째_요청은_409로_거부한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(guardian.getStatus()).willReturn(Status.ACTIVE);
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardian));
        given(seniorProfileRepository.findFamilyIdByMemberId(SENIOR_ID)).willReturn(Optional.of(7L));
        given(familyMembershipRepository.existsByFamilyIdAndGuardianId(7L, GUARDIAN_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service().recordGuardianResponse(GUARDIAN_ID, INCIDENT_REF,
                GuardianResponseType.CALL_INITIATED))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_GUARDIAN_RESPONSE_ALREADY_RECORDED);
    }

    @Test
    @DisplayName("본인이 HELP로 답하면 무응답과 같은 상태로 응답 저장을 요청한다")
    void 본인이_HELP로_답하면_무응답과_같은_상태로_응답_저장을_요청한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        givenRespondUpdates(1);

        // when
        service().respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.HELP, ResponseVia.PHONE));

        // then
        then(incidentRepository).should().respond(eq(INCIDENT_REF), eq(SENIOR_ID),
                eq(IncidentResponseValue.HELP), eq(ResponseVia.PHONE), anyLong(), isNull(), eq(IncidentState.ESCALATED));
    }

    @Test
    @DisplayName("응답 시각은 서버 시각으로 넘긴다")
    void 응답_시각은_서버_시각으로_넘긴다() {
        // given
        long before = System.currentTimeMillis();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        givenRespondUpdates(1);

        // when
        service().respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH, 123L));

        // then
        // 마감 판정의 기준이 되는 시각이라 단말이 보낸 값을 쓰지 않는다.
        ArgumentCaptor<Long> respondedAtMs = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().respond(any(), any(), any(), any(),
                respondedAtMs.capture(), eq(123L), any());
        assertThat(respondedAtMs.getValue()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    @DisplayName("이미 답했거나 종결된 사건이라 갱신된 행이 없으면 예외가 발생한다")
    void 갱신된_행이_없으면_예외가_발생한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(openIncident()));
        givenRespondUpdates(0);
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
        then(incidentRepository).should(never()).respond(any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("보호자가 사후 판정을 넣으면 판정자와 119 신고 시각과 함께 저장을 요청한다")
    void 보호자가_사후_판정을_넣으면_사건이_종결된다() {
        // given
        Incident resolved = resolvedIncident();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(escalatedIncident()), Optional.of(resolved));
        givenResolveUpdates(1);
        long calledAtMs = OPENED_AT_MS + 120_000L;

        // when
        IncidentResponse response = service().resolve(GUARDIAN_ID, INCIDENT_REF,
                new IncidentResolveRequest(IncidentOutcome.TRUE_EMERGENCY, calledAtMs));

        // then
        // 사건을 읽는 것은 시니어가 누구인지 알아 가족 접근을 확인하기 위해서다.
        then(familyAccessService).should().verifyFamilyAccess(GUARDIAN_ID, SENIOR_ID);
        then(incidentRepository).should().resolve(eq(INCIDENT_REF), eq(IncidentOutcome.TRUE_EMERGENCY),
                eq(GUARDIAN_ID), anyLong(), eq(calledAtMs));
        assertThat(response.state()).isEqualTo(IncidentState.RESOLVED);
        assertThat(response.outcome()).isEqualTo(IncidentOutcome.TRUE_EMERGENCY);
        assertThat(response.resolvedBy()).isEqualTo(GUARDIAN_ID);
        assertThat(response.guardianResponseType()).isNull();
        assertThat(resolved.getSituationEndedAtMs()).isEqualTo(resolved.getResolvedAtMs());
    }

    @Test
    @DisplayName("판정 시각은 서버 시각으로 넘긴다")
    void 판정_시각은_서버_시각으로_넘긴다() {
        // given
        long before = System.currentTimeMillis();
        given(incidentRepository.findByIncidentRef(INCIDENT_REF))
                .willReturn(Optional.of(escalatedIncident()), Optional.of(resolvedIncident()));
        givenResolveUpdates(1);

        // when
        service().resolve(GUARDIAN_ID, INCIDENT_REF,
                new IncidentResolveRequest(IncidentOutcome.FALSE_ALARM, null));

        // then
        ArgumentCaptor<Long> resolvedAtMs = ArgumentCaptor.forClass(Long.class);
        then(incidentRepository).should().resolve(any(), any(), any(), resolvedAtMs.capture(), any());
        assertThat(resolvedAtMs.getValue()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    @DisplayName("이미 판정한 사건이라 갱신된 행이 없으면 예외가 발생한다")
    void 이미_판정한_사건을_다시_판정하면_예외가_발생한다() {
        // given
        given(incidentRepository.findByIncidentRef(INCIDENT_REF)).willReturn(Optional.of(resolvedIncident()));
        givenResolveUpdates(0);
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
        then(incidentRepository).should(never()).resolve(any(), any(), any(), anyLong(), any());
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
                        new SensorProperties.Incident(60, 5000L, false, 5),
                        new SensorProperties.Followup(false, false),
                        new SensorProperties.Medication(true)), memberRepository,
                incidentEscalation, redisTemplate, outboxService, seniorProfileRepository,
                familyMembershipRepository, followupCardService);
    }

    private void givenResolveUpdates(int updated) {
        given(incidentRepository.resolve(any(), any(), any(), anyLong(), any())).willReturn(updated);
    }

    /** 조건부 UPDATE가 쓴 뒤 다시 읽은 행. */
    private Incident resolvedIncident() {
        Incident incident = escalatedIncident();
        ReflectionTestUtils.setField(incident, "state", IncidentState.RESOLVED);
        ReflectionTestUtils.setField(incident, "outcome", IncidentOutcome.TRUE_EMERGENCY);
        ReflectionTestUtils.setField(incident, "resolvedBy", GUARDIAN_ID);
        ReflectionTestUtils.setField(incident, "resolvedAtMs", OPENED_AT_MS + 160_000L);
        ReflectionTestUtils.setField(incident, "emergencyCalledAtMs", OPENED_AT_MS + 120_000L);
        return incident;
    }

    private void givenRespondUpdates(int updated) {
        given(incidentRepository.respond(any(), any(), any(), any(), anyLong(), nullable(Long.class), any()))
                .willReturn(updated);
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
