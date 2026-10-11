package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmDeliveryProperties;
import com.widyu.fcm.application.FcmDelivery;
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
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.application.IncidentService;
import com.widyu.followup.application.FollowupCardService;
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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
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
    @Autowired private IncidentEscalation escalation;
    @Autowired private IncidentService incidentService;
    @MockBean private FollowupCardService followupCardService;
    @MockBean private FcmService fcmService;
    @MockBean private FamilyAccessService familyAccessService;
    @MockBean private SensorProperties sensorProperties;
    @MockBean private FcmDeliveryProperties deliveryProperties;
    @MockBean private FcmEligibility eligibility;
    @MockBean private FcmOutboxDispatcher dispatcher;
    @MockBean private JPAQueryFactory jpaQueryFactory;
    @MockBean private RedisTemplate<String, Object> redisTemplate;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("심박 저장 커밋 뒤 60초 무응답을 처리하면 현재 방장에게만 최초 알림을 남긴다")
    void 심박_저장_커밋_뒤_무응답을_처리하면_현재_방장에게만_최초_알림을_남긴다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long[] ids = transactions.execute(status -> {
            Family family = families.save(Family.createFamily("FAM735"));
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "01000000735"));
            Member leader = members.save(Member.createMember(MemberType.GUARDIAN, "방장", "01000000736"));
            Member nonLeader = members.save(Member.createMember(MemberType.GUARDIAN, "보호자", "01000000737"));
            seniorProfiles.save(SeniorProfile.createSeniorProfile(senior, family, "서울", "INV0735",
                    LocalDate.of(1950, 1, 1)));
            memberships.save(FamilyMembership.createLeaderMembership(family, leader));
            memberships.save(FamilyMembership.createMembership(family, nonLeader));
            tokens.save(MemberFcmToken.builder().member(senior).token("senior-735").active(true).build());
            tokens.save(MemberFcmToken.builder().member(leader).token("leader-735").active(true).build());
            tokens.save(MemberFcmToken.builder().member(nonLeader).token("guardian-735").active(true).build());
            return new Long[] {senior.getId(), leader.getId(), nonLeader.getId(), family.getId()};
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5));
        given(deliveryProperties.ttl(true)).willReturn(Duration.ofMinutes(5));
        given(eligibility.familyId(ids[0])).willReturn(ids[3]);
        given(eligibility.sameActiveFamily(ids[2], ids[0], ids[3])).willReturn(true);
        willAnswer(invocation -> {
            outboxService.enqueue(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(fcmService).sendMessageToUser(anyLong(), any());

        // when
        transactions.executeWithoutResult(status -> {
            Member senior = members.findById(ids[0]).orElseThrow();
            heartEvents.save(HeartRateEvent.of(senior, 180, LocalDateTime.of(2026, 10, 2, 3, 5),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(ids[0], null));
        });
        Incident opened = incidents.findAll().stream().filter(row -> row.getMemberId().equals(ids[0]))
                .findFirst().orElseThrow();
        assertThat(opened.getInitialAlertSentAtMs()).isNull();
        assertThat(opened.getPolicyRevision()).isEqualTo(20261005L);
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[1], opened.getIncidentRef())).isEmpty();
        transactions.executeWithoutResult(status -> {
            for (FamilyMembership membership : memberships.findAllByFamilyIdWithGuardian(ids[3])) {
                if (membership.getGuardian().getId().equals(ids[1])) {
                    membership.setLeader(false);
                } else {
                    membership.setLeader(true);
                }
            }
        });
        assertThat(escalation.escalateIfDue(opened.getId(), opened.getRespondByMs() + 1)).isTrue();

        // then
        Incident alerted = incidents.findById(opened.getId()).orElseThrow();
        assertThat(alerted.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(alerted.getInitialAlertSentAtMs()).isEqualTo(opened.getRespondByMs() + 1);
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[2], opened.getIncidentRef()))
                .hasValueSatisfying(center -> assertThat(center.getType())
                        .isEqualTo(NotificationType.HEART_RATE_EMERGENCY));
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[1], opened.getIncidentRef())).isEmpty();
        List<FcmOutbox> alerts = outbox.findAll().stream()
                .filter(row -> row.getNotificationType() == NotificationType.HEART_RATE_EMERGENCY)
                .filter(row -> row.getRelatedMemberId().equals(ids[0]))
                .toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().getRecipientMember().getId()).isEqualTo(ids[2]);
        FcmDelivery restored = transactions.execute(status ->
                FcmDelivery.from(outbox.findById(alerts.getFirst().getId()).orElseThrow()));
        assertThat(restored.message().data())
                .containsEntry("deliveryStage", "INITIAL_ALERT")
                .containsEntry("safetyEventId", opened.getIncidentRef())
                .containsEntry("eventId", opened.getIncidentRef())
                .containsEntry("foregroundPresentation", "MODAL");
        FcmDelivery restoredAgain = transactions.execute(status ->
                FcmDelivery.from(outbox.findById(alerts.getFirst().getId()).orElseThrow()));
        assertThat(restoredAgain.message().data()).isEqualTo(restored.message().data());
        assertThat(escalation.escalateIfDue(opened.getId(), opened.getRespondByMs() + 2)).isFalse();
        assertThat(outbox.findAll().stream().filter(row -> row.getNotificationType()
                == NotificationType.HEART_RATE_EMERGENCY && row.getRelatedMemberId().equals(ids[0]))).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("커밋 뒤 같은 심박 감지가 이어지고 OK로 답하면 사건 하나와 S08 센터 한 건이 남는다")
    void 커밋_뒤_같은_심박_감지가_이어지고_OK로_답하면_사건_하나와_S08이_남는다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long[] ids = transactions.execute(status -> {
            Family family = families.save(Family.createFamily("FAM708"));
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "01000000708"));
            Member guardian = members.save(Member.createMember(MemberType.GUARDIAN, "보호자", "01000000709"));
            seniorProfiles.save(SeniorProfile.createSeniorProfile(senior, family, "서울", "INV0708",
                    LocalDate.of(1950, 1, 1)));
            memberships.save(FamilyMembership.createLeaderMembership(family, guardian));
            tokens.save(MemberFcmToken.builder().member(senior).token("senior-708").active(true).build());
            tokens.save(MemberFcmToken.builder().member(guardian).token("guardian-708").active(true).build());
            return new Long[] {senior.getId(), guardian.getId(), family.getId()};
        });
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5));
        given(deliveryProperties.ttl(true)).willReturn(Duration.ofMinutes(5));
        given(deliveryProperties.ttl(false)).willReturn(Duration.ofHours(1));
        given(eligibility.familyId(ids[0])).willReturn(ids[2]);
        given(eligibility.sameActiveFamily(ids[1], ids[0], ids[2])).willReturn(true);
        willAnswer(invocation -> {
            outboxService.enqueue(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(fcmService).sendMessageToUser(anyLong(), any());
        long previousIncidents = incidents.count();

        // when
        transactions.executeWithoutResult(status -> {
            Member senior = members.findById(ids[0]).orElseThrow();
            heartEvents.save(HeartRateEvent.of(senior, 180, LocalDateTime.of(2026, 10, 2, 3, 1),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(ids[0], null));
        });
        long firstOutboxCount = outbox.count();
        transactions.executeWithoutResult(status -> {
            Incident first = incidents.findAll().stream()
                    .filter(row -> row.getMemberId().equals(ids[0])).findFirst().orElseThrow();
            ReflectionTestUtils.setField(first, "policyRevision", 20261004L);
        });
        transactions.executeWithoutResult(status -> {
            Member senior = members.findById(ids[0]).orElseThrow();
            heartEvents.save(HeartRateEvent.of(senior, 181, LocalDateTime.of(2026, 10, 2, 3, 2),
                    HeartRateStatus.EMERGENCY, null, null));
            publisher.publishEvent(new HeartRateEmergencyEvent(ids[0], null));
        });
        Incident incident = incidents.findAll().stream().filter(row -> row.getMemberId().equals(ids[0]))
                .findFirst().orElseThrow();
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[1], incident.getIncidentRef())).isEmpty();
        transactions.executeWithoutResult(status -> incidentService.respond(ids[0], incident.getIncidentRef(),
                new IncidentRespondRequest(IncidentResponseValue.OK, ResponseVia.PHONE)));

        // then
        Incident answered = incidents.findById(incident.getId()).orElseThrow();
        assertThat(incidents.count()).isEqualTo(previousIncidents + 1);
        assertThat(answered.getDetectionCount()).isEqualTo(2);
        assertThat(answered.getPolicyRevision()).isEqualTo(20261004L);
        assertThat(answered.getState()).isEqualTo(IncidentState.OK_CLOSED);
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[1], incident.getIncidentRef())).isEmpty();
        assertThat(answered.getOkNoticeSentAtMs()).isNotNull();
        assertThat(outbox.count()).isEqualTo(firstOutboxCount + 1);
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[1], incident.getIncidentRef() + ":OK")
                .orElseThrow().getType()).isEqualTo(NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART);
    }

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
                .findFirst().orElseThrow().getDataType()).isEqualTo("EMERGENCY_CONFIRM_REQUEST");
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
            Member nonLeader = members.save(Member.createMember(MemberType.GUARDIAN, "비방장", "01000000014"));
            seniorProfiles.save(SeniorProfile.createSeniorProfile(senior, family, "서울", "INV0706",
                    LocalDate.of(1950, 1, 1)));
            memberships.save(FamilyMembership.createLeaderMembership(family, guardian));
            memberships.save(FamilyMembership.createMembership(family, nonLeader));
            tokens.save(MemberFcmToken.builder().member(senior).token("senior-token").active(true).build());
            tokens.save(MemberFcmToken.builder().member(guardian).token("guardian-token").active(true).build());
            tokens.save(MemberFcmToken.builder().member(nonLeader).token("nonleader-token").active(true).build());
            long oldOpenedAtMs = System.currentTimeMillis() - Duration.ofMinutes(6).toMillis();
            Incident old = Incident.builder().incidentRef("inc-old-706").memberId(senior.getId())
                    .kind(IncidentKind.HR_ANOMALY).openedAtMs(oldOpenedAtMs)
                    .respondByMs(oldOpenedAtMs + 60_000L).build();
            ReflectionTestUtils.setField(old, "state", IncidentState.ESCALATED);
            old.markInitialAlertSent(oldOpenedAtMs);
            incidents.save(old);
            return new Long[] {senior.getId(), guardian.getId(), family.getId(), old.getId(), nonLeader.getId()};
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
                .findFirst().orElseThrow().getDataType()).isEqualTo("EMERGENCY_CONFIRM_REQUEST");
        assertThat(notifications.count()).isEqualTo(previousNotifications + 1);
        var center = notifications.findByRecipientMemberIdAndEventId(ids[1], incident.getIncidentRef())
                .orElseThrow();
        assertThat(center.getType()).isEqualTo(NotificationType.HEART_RATE_EMERGENCY);
        assertThat(center.getSeniorId()).isEqualTo(ids[0]);
        assertThat(center.getEventId()).isEqualTo(incident.getIncidentRef());
        assertThat(notifications.findByRecipientMemberIdAndEventId(ids[4], incident.getIncidentRef())).isEmpty();
        assertThat(outbox.findAll().stream().filter(row -> row.getRecipientMember().getId().equals(ids[4]))
                .toList()).isEmpty();
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
                .findFirst().orElseThrow().getDataType()).isEqualTo("EMERGENCY_CONFIRM_REQUEST");
        assertThat(notifications.count()).isEqualTo(previousNotifications);
    }
}
