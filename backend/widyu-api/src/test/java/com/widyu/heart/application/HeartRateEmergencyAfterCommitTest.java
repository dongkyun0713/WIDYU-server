package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.properties.SensorProperties;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentService;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, HeartRateEmergencyNotificationService.class})
class HeartRateEmergencyAfterCommitTest {
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private MemberRepository members;
    @Autowired private HeartRateEventRepository heartEvents;
    @MockBean private IncidentService incidents;
    @MockBean private IncidentEscalation escalation;
    @MockBean private FcmService fcmService;
    @MockBean private SensorProperties sensorProperties;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("사건 열기가 커밋 후 실패하면 먼저 저장한 심박은 그대로 남는다")
    void 사건_열기가_커밋_후_실패하면_심박은_남는다() {
        // given
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Long memberId = transactions.execute(status -> members.save(
                Member.createMember(MemberType.SENIOR, "시니어", "01000000000")).getId());
        willThrow(new IllegalStateException("incident down"))
                .given(incidents).openForAlert(memberId, IncidentKind.HR_ANOMALY);

        // when
        try {
            transactions.executeWithoutResult(status -> {
                Member member = members.findById(memberId).orElseThrow();
                heartEvents.save(HeartRateEvent.of(member, 180, LocalDateTime.of(2026, 10, 2, 2, 0),
                        HeartRateStatus.EMERGENCY, null, null));
                publisher.publishEvent(new HeartRateEmergencyEvent(memberId, null));
            });
        } catch (IllegalStateException ignored) {
            // AFTER_COMMIT 리스너의 예외는 호출자에게 전파될 수 있지만 커밋은 이미 끝났다.
        }

        // then
        assertThat(heartEvents.count()).isEqualTo(1);
        then(incidents).should().openForAlert(memberId, IncidentKind.HR_ANOMALY);
    }
}
