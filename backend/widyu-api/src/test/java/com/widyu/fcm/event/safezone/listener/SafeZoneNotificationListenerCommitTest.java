package com.widyu.fcm.event.safezone.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentService;
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
    @MockBean private IncidentService incidentService;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("안심구역 이탈 위치 갱신을 커밋하면 사건을 열고 롤백하면 열지 않는다")
    void 안심구역_이탈_위치_갱신을_커밋하면_사건을_열고_롤백하면_열지_않는다() {
        // given
        given(incidentService.openForAlert(1L, IncidentKind.SAFE_ZONE_EXIT)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        });

        // when
        transactions.executeWithoutResult(status -> {
            events.publishEvent(new SafeZoneExitEvent(1L));
            status.setRollbackOnly();
        });

        // then
        then(incidentService).shouldHaveNoInteractions();

        // when
        transactions.executeWithoutResult(status -> events.publishEvent(new SafeZoneExitEvent(1L)));

        // then
        then(incidentService).should(times(1)).openForAlert(1L, IncidentKind.SAFE_ZONE_EXIT);
    }
}
