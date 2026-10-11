package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmEligibility;
import com.widyu.fcm.application.FcmOutboxDispatcher;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import com.widyu.incident.application.IncidentService;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, HeartRateEmergencyNotificationService.class,
        IncidentService.class, IncidentEscalation.class, FcmOutboxService.class})
class HeartRateEmergencyAfterCommitOutboxTest {
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private MemberRepository members;
    @Autowired private MemberFcmTokenRepository tokens;
    @Autowired private HeartRateEventRepository heartEvents;
    @Autowired private IncidentRepository incidents;
    @Autowired private FcmOutboxRepository outbox;
    @Autowired private FcmNotificationRepository notifications;
    @Autowired private FamilyRepository families;
    @Autowired private FamilyMembershipRepository memberships;
    @Autowired private SeniorProfileRepository seniorProfiles;
    @Autowired private FcmOutboxService outboxService;
    @MockBean private FcmService fcmService;
    @MockBean private FamilyAccessService familyAccessService;
    @MockBean private SensorProperties sensorProperties;
    @MockBean private FcmDeliveryProperties deliveryProperties;
    @MockBean private FcmEligibility eligibility;
    @MockBean private FcmOutboxDispatcher dispatcher;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("심박 저장이 커밋되면 새 트랜잭션에서 사건과 본인확인 outbox가 남는다")
    void 심박_저장이_커밋되면_사건과_본인확인_outbox가_남는다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long memberId = transactions.execute(status -> {
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "01000000001"));
            tokens.save(MemberFcmToken.builder().member(senior).token("test-token").active(true).build());
            return senior.getId();
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5));
        given(deliveryProperties.ttl(true)).willReturn(Duration.ofMinutes(5));
        willAnswer(invocation -> {
            outboxService.enqueue(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(fcmService).sendMessageToUser(anyLong(), any());
        long previousHeartEvents = heartEvents.count();
        long previousIncidents = incidents.count();
        long previousOutbox = outbox.count();

        // when
        transactions.executeWithoutResult(status -> {
            Member member = members.findById(memberId).orElseThrow();
            heartEvents.save(HeartRateEvent.of(member, 180, LocalDateTime.of(2026, 10, 2, 2, 1),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(memberId, null));
        });

        // then
        assertThat(heartEvents.count()).isEqualTo(previousHeartEvents + 1);
        assertThat(incidents.count()).isEqualTo(previousIncidents + 1);
        assertThat(incidents.findAll().stream().filter(i -> i.getMemberId().equals(memberId))
                .findFirst().orElseThrow().getDecisionId()).isNull();
        assertThat(outbox.count()).isEqualTo(previousOutbox + 1);
        assertThat(outbox.findAll().stream().filter(row -> row.getRecipientMember().getId().equals(memberId))
                .findFirst().orElseThrow().getDataType()).isEqualTo("SAFETY_SELF_CHECK");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("6분 전 ESCALATED 사건이 있어도 새 단건 위급은 새 사건과 S01·S04를 남긴다")
    void 육분_전_ESCALATED_사건이_있어도_새_사건과_S01_S04를_남긴다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long[] ids = transactions.execute(status -> {
            Family family = families.save(Family.createFamily("FAM706"));
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "01000000011"));
            Member guardian = members.save(Member.createMember(MemberType.GUARDIAN, "보호자", "01000000012"));
            seniorProfiles.save(SeniorProfile.createSeniorProfile(senior, family, "서울", "INV0706",
                    LocalDate.of(1950, 1, 1)));
            memberships.save(FamilyMembership.createMembership(family, guardian));
            tokens.save(MemberFcmToken.builder().member(senior).token("senior-token").active(true).build());
            tokens.save(MemberFcmToken.builder().member(guardian).token("guardian-token").active(true).build());
            long oldOpenedAtMs = System.currentTimeMillis() - Duration.ofMinutes(6).toMillis();
            Incident old = Incident.builder().incidentRef("inc-old-706").memberId(senior.getId())
                    .kind(IncidentKind.HR_ANOMALY).openedAtMs(oldOpenedAtMs)
                    .respondByMs(oldOpenedAtMs + 60_000L).build();
            ReflectionTestUtils.setField(old, "state", IncidentState.ESCALATED);
            old.markInitialAlertSent(oldOpenedAtMs);
            incidents.save(old);
            return new Long[] {senior.getId(), guardian.getId(), family.getId(), old.getId()};
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(deliveryProperties.ttl(true)).willReturn(Duration.ofMinutes(5));
        given(eligibility.familyId(ids[0])).willReturn(ids[2]);
        given(eligibility.sameActiveFamily(ids[1], ids[0], ids[2])).willReturn(true);
        willAnswer(invocation -> {
            outboxService.enqueue(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(fcmService).sendMessageToUser(anyLong(), any());
        long previousHeartEvents = heartEvents.count();
        long previousIncidents = incidents.count();
        long previousOutbox = outbox.count();
        long previousNotifications = notifications.count();

        // when
        transactions.executeWithoutResult(status -> {
            Member member = members.findById(ids[0]).orElseThrow();
            heartEvents.save(HeartRateEvent.of(member, 180, LocalDateTime.of(2026, 10, 2, 2, 2),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(ids[0], null));
        });

        // then
        assertThat(heartEvents.count()).isEqualTo(previousHeartEvents + 1);
        assertThat(incidents.count()).isEqualTo(previousIncidents + 1);
        var incident = incidents.findAll().stream().filter(i -> i.getMemberId().equals(ids[0]))
                .filter(i -> !i.getId().equals(ids[3]))
                .findFirst().orElseThrow();
        assertThat(incident.getId()).isNotEqualTo(ids[3]);
        assertThat(incidents.findById(ids[3]).orElseThrow().getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(incident.getInitialAlertSentAtMs()).isNotNull();
        assertThat(outbox.count()).isEqualTo(previousOutbox + 2);
        assertThat(outbox.findAll().stream().filter(row -> row.getRecipientMember().getId().equals(ids[0]))
                .findFirst().orElseThrow().getDataType()).isEqualTo("SAFETY_SELF_CHECK");
        assertThat(notifications.count()).isEqualTo(previousNotifications + 1);
        var center = notifications.findByRecipientMemberIdAndEventId(ids[1], incident.getIncidentRef())
                .orElseThrow();
        assertThat(center.getType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(center.getSeniorId()).isEqualTo(ids[0]);
        assertThat(center.getEventId()).isEqualTo(incident.getIncidentRef());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("보호자가 없는 시니어에게 위급이 생기면 사건과 본인확인은 남고 최초 알림 게이트만 닫힌다")
    void 보호자가_없는_시니어에게_위급이_생기면_사건과_본인확인은_남는다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long memberId = transactions.execute(status -> {
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "미연결 시니어", "01000000013"));
            tokens.save(MemberFcmToken.builder().member(senior).token("unlinked-token").active(true).build());
            return senior.getId();
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));
        given(deliveryProperties.ttl(true)).willReturn(Duration.ofMinutes(5));
        willAnswer(invocation -> {
            outboxService.enqueue(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(fcmService).sendMessageToUser(anyLong(), any());
        long previousIncidents = incidents.count();
        long previousOutbox = outbox.count();
        long previousNotifications = notifications.count();

        // when
        transactions.executeWithoutResult(status -> {
            Member senior = members.findById(memberId).orElseThrow();
            heartEvents.save(HeartRateEvent.of(senior, 180, LocalDateTime.of(2026, 10, 2, 2, 3),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(memberId, null));
        });

        // then
        assertThat(incidents.count()).isEqualTo(previousIncidents + 1);
        var incident = incidents.findAll().stream().filter(i -> i.getMemberId().equals(memberId))
                .findFirst().orElseThrow();
        assertThat(incident.getInitialAlertSentAtMs()).isNotNull();
        assertThat(outbox.count()).isEqualTo(previousOutbox + 1);
        assertThat(outbox.findAll().stream().filter(row -> row.getRecipientMember().getId().equals(memberId))
                .findFirst().orElseThrow().getDataType()).isEqualTo("SAFETY_SELF_CHECK");
        assertThat(notifications.count()).isEqualTo(previousNotifications);
    }
}
