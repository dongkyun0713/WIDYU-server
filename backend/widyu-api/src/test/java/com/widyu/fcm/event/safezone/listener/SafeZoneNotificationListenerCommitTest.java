package com.widyu.fcm.event.safezone.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.global.entity.Status;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DataJpaTest
@ActiveProfiles("test")
@Import(SafeZoneNotificationListener.class)
class SafeZoneNotificationListenerCommitTest {
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactions;
    @MockBean private FcmOutboxService outbox;
    @MockBean private MemberRepository members;
    @MockBean private SeniorProfileRepository seniors;
    @MockBean private FamilyMembershipRepository memberships;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("안심구역 이탈 이벤트를 발행하면 위치 트랜잭션 안에서 보호자 알림을 예약한다")
    void 안심구역_이탈_이벤트를_발행하면_같은_트랜잭션에서_알림을_예약한다() {
        // given
        Member senior = org.mockito.Mockito.mock(Member.class);
        Member guardian = org.mockito.Mockito.mock(Member.class);
        FamilyMembership membership = org.mockito.Mockito.mock(FamilyMembership.class);
        given(members.findById(1L)).willReturn(Optional.of(senior));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(senior.getStatus()).willReturn(Status.ACTIVE);
        given(senior.getName()).willReturn("시니어");
        given(memberships.findAllByFamilyIdWithGuardian(10L)).willReturn(List.of(membership));
        given(membership.getGuardian()).willReturn(guardian);
        given(guardian.getStatus()).willReturn(Status.ACTIVE);
        given(guardian.getId()).willReturn(2L);
        willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).given(outbox).enqueue(eq(2L), any());

        // when
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new SafeZoneExitEvent(1L));
            status.setRollbackOnly();
        });

        // then
        then(outbox).should().enqueue(eq(2L), any());
    }
}
