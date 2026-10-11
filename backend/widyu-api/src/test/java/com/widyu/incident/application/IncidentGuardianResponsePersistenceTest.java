package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.entity.Status;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.repository.IncidentGuardianResponseRepository;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, IncidentService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentGuardianResponsePersistenceTest {

    private static final long FIRST_RESPONSE_AT_MS = 1_760_000_123_000L;
    private static final String INCIDENT_REF = "inc-stop-snapshot-739";

    @Autowired private IncidentService service;
    @Autowired private IncidentRepository incidents;
    @Autowired private IncidentGuardianResponseRepository responses;
    @Autowired private PlatformTransactionManager transactionManager;
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

    @Test
    @DisplayName("선행 조회 뒤 다른 보호자 멈춤이 커밋되면 같은 종류는 원래 시각으로 반환하고 취소 상태를 반영한다")
    void 선행_조회_뒤_다른_트랜잭션이_멈춤을_커밋하면_원래_시각과_취소_상태를_반환한다() {
        // given
        Member first = org.mockito.Mockito.mock(Member.class);
        Member second = org.mockito.Mockito.mock(Member.class);
        given(first.getType()).willReturn(MemberType.GUARDIAN);
        given(second.getType()).willReturn(MemberType.GUARDIAN);
        given(first.getStatus()).willReturn(Status.ACTIVE);
        given(second.getStatus()).willReturn(Status.ACTIVE);
        given(members.findById(201L)).willReturn(Optional.of(first));
        given(members.findById(202L)).willReturn(Optional.of(second));
        given(seniorProfiles.findFamilyIdByMemberId(101L)).willReturn(Optional.of(10L));
        given(memberships.existsByFamilyIdAndGuardianId(10L, 201L)).willReturn(true);
        given(memberships.existsByFamilyIdAndGuardianId(10L, 202L)).willReturn(true);

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.executeWithoutResult(status -> {
            Incident incident = Incident.builder().incidentRef(INCIDENT_REF).memberId(101L)
                    .kind(IncidentKind.HR_ANOMALY).openedAtMs(FIRST_RESPONSE_AT_MS - 60_000L)
                    .respondByMs(FIRST_RESPONSE_AT_MS).build();
            incidents.saveAndFlush(incident);
        });

        // when / then
        outer.executeWithoutResult(status -> {
            Incident stale = incidents.findByIncidentRef(INCIDENT_REF).orElseThrow();
            assertThat(stale.getSecondAlertCancelledAtMs()).isNull();
            var firstResponse = committed.execute(inner -> service.recordGuardianResponse(
                    201L, INCIDENT_REF, GuardianResponseType.ACKNOWLEDGED));
            assertThat(firstResponse.secondAlertCancelled()).isTrue();
            assertThat(stale.getSecondAlertCancelledAtMs()).isNull();

            var duplicate = service.recordGuardianResponse(201L, INCIDENT_REF,
                    GuardianResponseType.ACKNOWLEDGED);
            assertThat(duplicate.guardianResponseAtMs()).isEqualTo(firstResponse.guardianResponseAtMs());
            assertThat(duplicate.secondAlertCancelled()).isTrue();
            assertThat(responses.count()).isEqualTo(1);

            var otherGuardian = service.recordGuardianResponse(202L, INCIDENT_REF,
                    GuardianResponseType.MESSAGE_SENT);
            assertThat(otherGuardian.secondAlertCancelled()).isTrue();
            assertThat(responses.count()).isEqualTo(2);
        });
    }
}
