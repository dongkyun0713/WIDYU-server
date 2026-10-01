package com.widyu.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.followup.FollowupAnswer;
import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupCardState;
import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;
import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.dto.response.CurrentFollowupResponse;
import com.widyu.followup.repository.FollowupAnswerRepository;
import com.widyu.followup.repository.FollowupCardRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FollowupCardServiceTest {
    @Mock private FollowupCardRepository cards;
    @Mock private FollowupAnswerRepository answers;
    @Mock private MemberRepository members;
    @Mock private SensorProperties properties;
    @Mock private FollowupRewardService rewards;

    @Test
    @DisplayName("미노출 카드를 조회하면 조건부 방문 키 배정 뒤 다시 읽어 반환한다")
    void 미노출_카드를_조회하면_조건부_배정_뒤_다시_읽어_반환한다() {
        // given
        String visitKey = "00000000-0000-0000-0000-000000000001";
        FollowupCard card = FollowupCard.issue("inc-current", 1L, "HR_V1", 1L,
                System.currentTimeMillis());
        ReflectionTestUtils.setField(card, "id", 10L);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        given(members.findByIdForUpdate(1L))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01098110009")));
        given(cards.findBySeniorIdAndVisitKey(1L, visitKey))
                .willReturn(Optional.empty(), Optional.of(card));
        given(cards.findFirstBySeniorIdAndStateAndVisitKeyIsNullAndExpiresAtMsGreaterThanOrderByIssuedAtMsAscIdAsc(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED),
                anyLong())).willReturn(Optional.of(card));
        given(cards.assignVisitIfIssued(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(visitKey),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED), anyLong())).willReturn(1);
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when
        CurrentFollowupResponse response = service.current(1L, visitKey);

        // then
        assertThat(response.card().id()).isEqualTo(10L);
        then(cards).should(times(2)).findBySeniorIdAndVisitKey(1L, visitKey);
        then(cards).should().assignVisitIfIssued(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(visitKey),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED), anyLong());
    }

    @Test
    @DisplayName("방문 키 배정에서 경합에 지면 같은 방문을 다시 확인하고 빈 카드를 반환한다")
    void 방문_키_배정에서_경합에_지면_재조회하고_빈_카드를_반환한다() {
        // given
        String visitKey = "00000000-0000-0000-0000-000000000002";
        FollowupCard card = FollowupCard.issue("inc-current-race", 1L, "HR_V1", 1L,
                System.currentTimeMillis());
        ReflectionTestUtils.setField(card, "id", 11L);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        given(members.findByIdForUpdate(1L))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01098110010")));
        given(cards.findBySeniorIdAndVisitKey(1L, visitKey)).willReturn(Optional.empty());
        given(cards.findFirstBySeniorIdAndStateAndVisitKeyIsNullAndExpiresAtMsGreaterThanOrderByIssuedAtMsAscIdAsc(
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED),
                anyLong())).willReturn(Optional.of(card));
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when
        CurrentFollowupResponse response = service.current(1L, visitKey);

        // then
        assertThat(response.card()).isNull();
        then(cards).should(times(2)).findBySeniorIdAndVisitKey(1L, visitKey);
    }

    @Test
    @DisplayName("후속 기능을 끄면 발급과 만료가 저장소에 접근하지 않는다")
    void 후속_기능을_끄면_발급과_만료가_저장소에_접근하지_않는다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(false, false));
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);
        Incident incident = Incident.builder().incidentRef("inc-off").memberId(1L)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1L).respondByMs(60_001L).build();

        // when
        service.issueIfEnabled(incident);
        service.expireIfDue(1L, 43_200_001L);

        // then
        verifyNoInteractions(cards, answers, members);
    }

    @Test
    @DisplayName("후속 기능을 끄면 시니어 API가 404로 종료한다")
    void 후속_기능을_끄면_시니어_API가_404로_종료한다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(false, false));
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when / then
        assertThatThrownBy(() -> service.current(1L, "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.answer(1L, 1L, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.decline(1L, 1L, 1L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(cards, answers, members);
    }

    @Test
    @DisplayName("첫 답변 전이에 성공하면 보상을 한 번 요청한다")
    void 첫_답변_전이에_성공하면_보상을_한_번_요청한다() {
        // given
        long submittedAtMs = System.currentTimeMillis();
        FollowupCard card = FollowupCard.issue("inc-reward-answer", 17L, "HR_V1", 1L, submittedAtMs);
        ReflectionTestUtils.setField(card, "id", 71L);
        FollowupAnswer answer = FollowupAnswer.of(card, FollowupQ1.DONT_KNOW,
                FollowupQ2.NOT_NEEDED, null, submittedAtMs, submittedAtMs);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, true));
        given(members.findById(17L)).willReturn(Optional.of(
                Member.createMember(MemberType.SENIOR, "시니어", "01098110117")));
        given(cards.findById(71L)).willReturn(Optional.of(card));
        given(cards.submit(org.mockito.ArgumentMatchers.eq(71L), org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.EXPIRED_NO_RESPONSE),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.ANSWERED), anyLong(),
                org.mockito.ArgumentMatchers.eq(submittedAtMs))).willReturn(1);
        given(answers.save(org.mockito.ArgumentMatchers.any(FollowupAnswer.class))).willReturn(answer);
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when
        service.answer(17L, 71L, new FollowupAnswerRequest("HR_V1", FollowupQ1.DONT_KNOW,
                FollowupQ2.NOT_NEEDED, null, submittedAtMs));

        // then
        then(rewards).should().rewardIfEnabled(71L, 17L);
    }

    @Test
    @DisplayName("첫 전체 거절 전이에 성공하면 보상을 한 번 요청한다")
    void 첫_전체_거절_전이에_성공하면_보상을_한_번_요청한다() {
        // given
        long submittedAtMs = System.currentTimeMillis();
        FollowupCard card = FollowupCard.issue("inc-reward-decline", 17L, "HR_V1", 1L, submittedAtMs);
        ReflectionTestUtils.setField(card, "id", 72L);
        FollowupAnswer answer = FollowupAnswer.of(card, null, null, null,
                submittedAtMs, submittedAtMs);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, true));
        given(members.findById(17L)).willReturn(Optional.of(
                Member.createMember(MemberType.SENIOR, "시니어", "01098110118")));
        given(cards.findById(72L)).willReturn(Optional.of(card));
        given(cards.submit(org.mockito.ArgumentMatchers.eq(72L), org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.ISSUED),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.EXPIRED_NO_RESPONSE),
                org.mockito.ArgumentMatchers.eq(FollowupCardState.DECLINED), anyLong(),
                org.mockito.ArgumentMatchers.eq(submittedAtMs))).willReturn(1);
        given(answers.save(org.mockito.ArgumentMatchers.any(FollowupAnswer.class))).willReturn(answer);
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when
        service.decline(17L, 72L, submittedAtMs);

        // then
        then(rewards).should().rewardIfEnabled(72L, 17L);
    }

    @Test
    @DisplayName("이미 제출한 카드를 다시 제출하면 보상을 요청하지 않는다")
    void 이미_제출한_카드를_다시_제출하면_보상을_요청하지_않는다() {
        // given
        FollowupCard card = FollowupCard.issue("inc-reward-repeat", 17L, "HR_V1", 1L,
                System.currentTimeMillis());
        ReflectionTestUtils.setField(card, "id", 73L);
        ReflectionTestUtils.setField(card, "state", FollowupCardState.ANSWERED);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, true));
        given(members.findById(17L)).willReturn(Optional.of(
                Member.createMember(MemberType.SENIOR, "시니어", "01098110119")));
        given(cards.findById(73L)).willReturn(Optional.of(card));
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when / then
        assertThatThrownBy(() -> service.decline(17L, 73L, System.currentTimeMillis()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(rewards);
    }

    @Test
    @DisplayName("동시 제출의 조건부 전이에 지면 보상을 요청하지 않는다")
    void 동시_제출의_조건부_전이에_지면_보상을_요청하지_않는다() {
        // given
        long submittedAtMs = System.currentTimeMillis();
        FollowupCard issued = FollowupCard.issue("inc-reward-race", 17L, "HR_V1", 1L, submittedAtMs);
        FollowupCard answered = FollowupCard.issue("inc-reward-race", 17L, "HR_V1", 1L, submittedAtMs);
        ReflectionTestUtils.setField(issued, "id", 74L);
        ReflectionTestUtils.setField(answered, "id", 74L);
        ReflectionTestUtils.setField(answered, "state", FollowupCardState.ANSWERED);
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, true));
        given(members.findById(17L)).willReturn(Optional.of(
                Member.createMember(MemberType.SENIOR, "시니어", "01098110120")));
        given(cards.findById(74L)).willReturn(Optional.of(issued), Optional.of(answered));
        FollowupCardService service = new FollowupCardService(cards, answers, members, properties, rewards);

        // when / then
        assertThatThrownBy(() -> service.answer(17L, 74L,
                new FollowupAnswerRequest("HR_V1", FollowupQ1.YES, FollowupQ2.NEEDED,
                        null, submittedAtMs)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(rewards);
    }
}
