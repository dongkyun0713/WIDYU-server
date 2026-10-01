package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartRateEmergencyNotificationServiceTest {
    @Mock private DecisionRecordRepository decisionRecordRepository;
    @Mock private IncidentService incidentService;
    @Mock private IncidentEscalation incidentEscalation;
    @Mock private SensorProperties sensorProperties;
    @InjectMocks private HeartRateEmergencyNotificationService service;

    @Test
    @DisplayName("플래그 OFF에서 단건 위급을 받으면 사건을 열고 공통 최초 알림 경로를 호출한다")
    void 플래그_OFF에서_단건_위급을_받으면_공통_최초_알림_경로를_호출한다() {
        // given
        Incident incident = incident(null);
        given(incidentService.openForAlert(1L, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null));

        // then
        then(incidentEscalation).should().sendImmediately(eq(incident), anyLong());
    }

    @Test
    @DisplayName("플래그 OFF에서 배치 위급을 받으면 판정 사건으로 공통 최초 알림 경로를 호출한다")
    void 플래그_OFF에서_배치_위급을_받으면_공통_최초_알림_경로를_호출한다() {
        // given
        DecisionRecord decision = DecisionRecord.builder().decisionId("dec-1").memberId(1L).build();
        Incident incident = incident("dec-1");
        given(decisionRecordRepository.findByDecisionId("dec-1")).willReturn(Optional.of(decision));
        given(incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, "dec-1"));

        // then
        assertThat(incident.getDecisionId()).isEqualTo("dec-1");
        then(incidentEscalation).should().sendImmediately(eq(incident), anyLong());
    }

    @Test
    @DisplayName("플래그 ON에서 배치 위급을 받으면 사건만 열고 최초 알림은 기다린다")
    void 플래그_ON에서_배치_위급을_받으면_최초_알림은_기다린다() {
        // given
        DecisionRecord decision = DecisionRecord.builder().decisionId("dec-1").memberId(1L).build();
        Incident incident = incident("dec-1");
        given(decisionRecordRepository.findByDecisionId("dec-1")).willReturn(Optional.of(decision));
        given(incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, "dec-1"));

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isNull();
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("플래그 OFF에서 이미 보낸 사건을 다시 받으면 최초 알림을 중복 발송하지 않는다")
    void 플래그_OFF에서_이미_보낸_사건을_다시_받으면_중복_발송하지_않는다() {
        // given
        Incident incident = incident(null);
        incident.markInitialAlertSent(1_000L);
        given(incidentService.openForAlert(1L, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null));

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(1_000L);
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    private Incident incident(String decisionId) {
        return Incident.builder().incidentRef("inc-1").memberId(1L).decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1_000L).respondByMs(61_000L).build();
    }
}
