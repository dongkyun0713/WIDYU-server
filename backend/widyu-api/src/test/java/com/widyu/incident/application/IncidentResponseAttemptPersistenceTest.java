package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseAttemptOutcome;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.incident.repository.IncidentResponseAttemptRepository;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import org.junit.jupiter.api.AfterEach;
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
@Import({JpaAuditingConfig.class, IncidentService.class, IncidentRespondTransaction.class,
        IncidentRejectedAttemptRecorder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentResponseAttemptPersistenceTest {
    private static final String INCIDENT_REF = "inc-attempt-740";
    private static final Long SENIOR_ID = 740L;

    @Autowired private IncidentService service;
    @Autowired private IncidentRepository incidents;
    @Autowired private IncidentResponseAttemptRepository attempts;
    @Autowired private PlatformTransactionManager transactions;
    @MockBean private JPAQueryFactory queryFactory;
    @MockBean private FcmService fcmService;
    @MockBean private FamilyAccessService familyAccessService;
    @MockBean private SensorProperties sensorProperties;
    @MockBean private MemberRepository members;
    @MockBean private IncidentEscalation escalation;
    @MockBean private FcmOutboxService outboxService;
    @MockBean private SeniorProfileRepository seniorProfiles;
    @MockBean private FamilyMembershipRepository memberships;
    @MockBean private FollowupCardService followupCards;

    @AfterEach
    void 이력과_사건을_정리한다() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            attempts.deleteAll();
            incidents.deleteAll();
        });
    }

    @Test
    @DisplayName("두 번째 기기 응답이 409로 롤백돼도 DUPLICATE 이력은 별도 트랜잭션에 남는다")
    void 두번째_기기_응답이_409이면_DUPLICATE_이력은_남는다() {
        // given
        long openedAtMs = System.currentTimeMillis();
        Long incidentId = new TransactionTemplate(transactions).execute(status -> {
            Incident incident = Incident.builder().incidentRef(INCIDENT_REF).memberId(SENIOR_ID)
                    .kind(IncidentKind.HR_ANOMALY).openedAtMs(openedAtMs)
                    .respondByMs(openedAtMs + 60_000L).build();
            incident.markChecking();
            return incidents.saveAndFlush(incident).getId();
        });

        // when
        service.respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.HELP, ResponseVia.PHONE));
        assertThatThrownBy(() -> service.respond(SENIOR_ID, INCIDENT_REF,
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.WATCH, openedAtMs + 10L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INCIDENT_ALREADY_ANSWERED);

        // then
        var saved = attempts.findByIncidentIdOrderByIdAsc(incidentId);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getOutcome()).isEqualTo(ResponseAttemptOutcome.ACCEPTED);
        assertThat(saved.get(1).getOutcome()).isEqualTo(ResponseAttemptOutcome.DUPLICATE);
        assertThat(saved.get(1).getResponseVia()).isEqualTo(ResponseVia.WATCH);
        assertThat(saved.get(1).getDeviceRespondedAtMs()).isEqualTo(openedAtMs + 10L);
        assertThat(saved.get(1).getReceivedAtMs()).isPositive();
        Incident incident = incidents.findById(incidentId).orElseThrow();
        assertThat(incident.getResponse()).isEqualTo(IncidentResponseValue.HELP);
        assertThat(incident.getState()).isEqualTo(IncidentState.ESCALATED);
    }
}
