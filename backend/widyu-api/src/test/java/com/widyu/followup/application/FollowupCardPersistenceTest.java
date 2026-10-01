package com.widyu.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.followup.FollowupAnswer;
import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupCardState;
import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;
import com.widyu.followup.FollowupQ3;
import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.dto.response.CurrentFollowupResponse;
import com.widyu.followup.repository.FollowupAnswerRepository;
import com.widyu.followup.repository.FollowupCardRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, FollowupCardService.class})
class FollowupCardPersistenceTest {
    @Autowired private FollowupCardService service;
    @Autowired private FollowupCardRepository cards;
    @Autowired private FollowupAnswerRepository answers;
    @Autowired private IncidentRepository incidents;
    @Autowired private MemberRepository members;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private SensorProperties properties;
    @MockBean private JPAQueryFactory queryFactory;
    @MockBean private FollowupRewardService rewardService;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("후보 조회 뒤 제출이 먼저 커밋되면 방문 키 배정은 실패하고 답변 상태를 유지한다")
    void 후보_조회_뒤_제출이_먼저_커밋되면_방문_키_배정이_실패하고_답변을_유지한다() {
        // given
        Member senior = senior("01098110011");
        long nowMs = System.currentTimeMillis();
        FollowupCard card = cards.save(FollowupCard.issue("inc-followup-race", senior.getId(),
                "HR_V1", 1L, nowMs));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String visitKey = "00000000-0000-0000-0000-000000000011";

        try {
            Long candidateId = transaction.execute(status -> cards
                    .findFirstBySeniorIdAndStateAndVisitKeyIsNullAndExpiresAtMsGreaterThanOrderByIssuedAtMsAscIdAsc(
                            senior.getId(), FollowupCardState.ISSUED, nowMs)
                    .orElseThrow().getId());

            // when: 조회 후 제출 트랜잭션을 먼저 커밋한다.
            int submitted = transaction.execute(status -> cards.submit(candidateId, senior.getId(),
                    FollowupCardState.ISSUED, FollowupCardState.EXPIRED_NO_RESPONSE,
                    FollowupCardState.ANSWERED, nowMs, nowMs));
            int assigned = transaction.execute(status -> cards.assignVisitIfIssued(candidateId,
                    senior.getId(), visitKey, FollowupCardState.ISSUED, nowMs));

            // then
            assertThat(submitted).isEqualTo(1);
            assertThat(assigned).isZero();
            FollowupCard persisted = cards.findById(card.getId()).orElseThrow();
            assertThat(persisted.getState()).isEqualTo(FollowupCardState.ANSWERED);
            assertThat(persisted.getVisitKey()).isNull();
        } finally {
            cards.deleteById(card.getId());
            members.deleteById(senior.getId());
        }
    }

    @Test
    @DisplayName("OK 종료 사건에 카드를 발급하면 12시간 만료시각과 문항 판본을 저장한다")
    void OK_종료_사건에_카드를_발급하면_만료시각과_판본을_저장한다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member senior = senior("01098110001");
        Incident incident = incident(senior.getId(), "inc-followup-1", IncidentKind.HR_ANOMALY);

        // when
        service.issueIfEnabled(incident);
        service.issueIfEnabled(incident);

        // then
        assertThat(cards.count()).isEqualTo(1);
        FollowupCard card = cards.findAll().get(0);
        assertThat(card.getExpiresAtMs() - card.getIssuedAtMs()).isEqualTo(43_200_000L);
        assertThat(card.getEventAtMs()).isEqualTo(incident.getOpenedAtMs());
        assertThat(card.getQuestionSetVersion()).isEqualTo("HR_V1");
    }

    @Test
    @DisplayName("한 방문에서 카드를 제출하면 같은 방문의 다음 카드를 노출하지 않는다")
    void 한_방문에서_제출하면_다음_카드를_노출하지_않는다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member senior = senior("01098110002");
        service.issueIfEnabled(incident(senior.getId(), "inc-followup-2", IncidentKind.HR_ANOMALY));
        service.issueIfEnabled(incident(senior.getId(), "inc-followup-3", IncidentKind.SAFE_ZONE_EXIT));
        String visit = "AAAAAAAA-0000-0000-0000-000000000002";

        // when
        CurrentFollowupResponse first = service.current(senior.getId(), visit);
        service.answer(senior.getId(), first.card().id(),
                new FollowupAnswerRequest(first.card().questionSetVersion(), FollowupQ1.DONT_KNOW,
                        FollowupQ2.NOT_NEEDED, List.of(FollowupQ3.EXERCISE), System.currentTimeMillis()));
        CurrentFollowupResponse sameVisit = service.current(senior.getId(), visit.toLowerCase());
        CurrentFollowupResponse nextVisit = service.current(senior.getId(),
                "00000000-0000-0000-0000-000000000003");

        // then
        assertThat(sameVisit.card()).isNull();
        assertThat(nextVisit.card()).isNotNull();
        assertThat(nextVisit.card().id()).isNotEqualTo(first.card().id());
        FollowupAnswer saved = answers.findAll().get(0);
        assertThat(saved.getQ1()).isEqualTo(FollowupQ1.DONT_KNOW);
        assertThat(saved.getQ2()).isEqualTo(FollowupQ2.NOT_NEEDED);
        assertThat(saved.getQ3()).isEqualTo("EXERCISE");
        assertThat(saved.getServerReceivedAtMs()).isPositive();
    }

    @Test
    @DisplayName("만료 전 단말 제출이 늦게 도착하면 원답을 저장하고 재제출을 막는다")
    void 만료_전_단말_제출이_늦게_도착하면_원답을_저장하고_재제출을_막는다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member senior = senior("01098110004");
        FollowupCard card = cards.save(FollowupCard.issue("inc-followup-late", senior.getId(),
                "HR_V1", 1L, System.currentTimeMillis() - 43_201_000L));
        cards.expire(card.getId(), FollowupCardState.ISSUED,
                FollowupCardState.EXPIRED_NO_RESPONSE, System.currentTimeMillis());
        long beforeExpiry = card.getExpiresAtMs() - 1;

        // when
        service.answer(senior.getId(), card.getId(),
                new FollowupAnswerRequest("HR_V1", FollowupQ1.REFUSE, FollowupQ2.DONT_KNOW,
                        null, beforeExpiry));

        // then
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.ANSWERED);
        assertThat(answers.findAll()).hasSize(1);
        assertThatThrownBy(() -> service.decline(senior.getId(), card.getId(), beforeExpiry))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FOLLOWUP_ALREADY_SUBMITTED);
        assertThat(answers.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("명시적으로 건너뛰면 거절 상태와 단말 및 서버 시각을 남긴다")
    void 명시적으로_건너뛰면_거절_상태와_시각을_남긴다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member senior = senior("01098110005");
        FollowupCard card = cards.save(FollowupCard.issue("inc-followup-decline", senior.getId(),
                "HR_V1", 1L, System.currentTimeMillis()));
        long deviceTime = System.currentTimeMillis();

        // when
        service.decline(senior.getId(), card.getId(), deviceTime);

        // then
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.DECLINED);
        FollowupAnswer saved = answers.findAll().get(0);
        assertThat(saved.getQ1()).isNull();
        assertThat(saved.getQ2()).isNull();
        assertThat(saved.getDeviceSubmittedAtMs()).isEqualTo(deviceTime);
        assertThat(saved.getServerReceivedAtMs()).isPositive();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("만료 시각이 지나면 미제출 카드만 무응답으로 바꾼다")
    void 만료_시각이_지나면_미제출_카드만_무응답으로_바꾼다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member senior = senior("01098110006");
        long nowMs = System.currentTimeMillis();
        FollowupCard due = cards.save(FollowupCard.issue("inc-followup-due", senior.getId(),
                "HR_V1", 1L, nowMs - 43_200_000L));
        FollowupCard pending = cards.save(FollowupCard.issue("inc-followup-pending", senior.getId(),
                "HR_V1", 1L, nowMs));

        try {
            // when
            service.expireIfDue(due.getId(), nowMs);
            service.expireIfDue(pending.getId(), nowMs);

            // then
            assertThat(cards.findById(due.getId()).orElseThrow().getState())
                    .isEqualTo(FollowupCardState.EXPIRED_NO_RESPONSE);
            assertThat(cards.findById(pending.getId()).orElseThrow().getState())
                    .isEqualTo(FollowupCardState.ISSUED);
            assertThat(answers.count()).isZero();
        } finally {
            cards.deleteById(due.getId());
            cards.deleteById(pending.getId());
            members.deleteById(senior.getId());
        }
    }

    @Test
    @DisplayName("만료 뒤 단말 제출은 거절하고 다른 시니어에게 카드를 숨긴다")
    void 만료_뒤_제출은_거절하고_다른_시니어에게_카드를_숨긴다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));
        Member owner = senior("01098110007");
        Member other = senior("01098110008");
        FollowupCard card = cards.save(FollowupCard.issue("inc-followup-expired", owner.getId(),
                "HR_V1", 1L, System.currentTimeMillis() - 43_201_000L));
        long lateTime = card.getExpiresAtMs() + 1;

        // when / then
        assertThatThrownBy(() -> service.answer(owner.getId(), card.getId(),
                new FollowupAnswerRequest("HR_V1", FollowupQ1.NO, FollowupQ2.NOT_NEEDED,
                        null, lateTime)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FOLLOWUP_EXPIRED);
        assertThatThrownBy(() -> service.decline(other.getId(), card.getId(), lateTime))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FOLLOWUP_NOT_FOUND);
        assertThat(answers.count()).isZero();
    }

    private Member senior(String phone) {
        return members.save(Member.createMember(MemberType.SENIOR, "시니어", phone));
    }

    private Incident incident(Long seniorId, String ref, IncidentKind kind) {
        Incident incident = Incident.builder().incidentRef(ref).memberId(seniorId).kind(kind)
                .openedAtMs(System.currentTimeMillis()).respondByMs(System.currentTimeMillis() + 60_000).build();
        return incidents.save(incident);
    }
}
