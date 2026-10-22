package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.widyu.decision.DecisionRecord;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentEscalation;
import com.widyu.incident.application.IncidentService;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HeartRateEmergencyNotificationServiceTest {
    @Mock private DecisionRecordRepository decisionRecordRepository;
    @Mock private IncidentService incidentService;
    @Mock private IncidentEscalation incidentEscalation;
    @Mock private SensorProperties sensorProperties;
    @Mock private MemberRepository memberRepository;
    @InjectMocks private HeartRateEmergencyNotificationService service;

    @Test
    @DisplayName("플래그 OFF에서 단건 위급을 받으면 사건을 열고 공통 최초 알림 경로를 호출한다")
    void 플래그_OFF에서_단건_위급을_받으면_공통_최초_알림_경로를_호출한다() {
        // given
        givenSeniorLock();
        Incident incident = incident(null);
        given(incidentService.openForAlert(1L, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5, 90));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null));

        // then
        then(incidentEscalation).should().sendImmediately(eq(incident), anyLong());
    }

    @Test
    @DisplayName("플래그 OFF에서 배치 위급을 받으면 판정 사건으로 공통 최초 알림 경로를 호출한다")
    void 플래그_OFF에서_배치_위급을_받으면_공통_최초_알림_경로를_호출한다() {
        // given
        givenSeniorLock();
        DecisionRecord decision = DecisionRecord.builder().decisionId("dec-1").memberId(1L).build();
        Incident incident = incident("dec-1");
        given(decisionRecordRepository.findByDecisionId("dec-1")).willReturn(Optional.of(decision));
        given(incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5, 90));

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
        givenSeniorLock();
        DecisionRecord decision = DecisionRecord.builder().decisionId("dec-1").memberId(1L).build();
        Incident incident = incident("dec-1");
        given(decisionRecordRepository.findByDecisionId("dec-1")).willReturn(Optional.of(decision));
        given(incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5, 90));

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
        givenSeniorLock();
        Incident incident = incident(null);
        incident.markInitialAlertSent(1_000L);
        given(incidentService.openForAlert(1L, IncidentKind.HR_ANOMALY)).willReturn(incident);
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, false, 5, 90));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, null));

        // then
        assertThat(incident.getInitialAlertSentAtMs()).isEqualTo(1_000L);
        then(incidentEscalation).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("판정 경로 위급을 받으면 회원 행을 잠근 뒤에 판정 기록을 읽는다")
    void 판정_경로_위급을_받으면_회원_행을_잠근_뒤에_판정_기록을_읽는다() {
        // given
        givenSeniorLock();
        DecisionRecord decision = DecisionRecord.builder().decisionId("dec-2").memberId(1L).build();
        given(decisionRecordRepository.findByDecisionId("dec-2")).willReturn(Optional.of(decision));
        given(incidentService.openForAlert(decision, IncidentKind.HR_ANOMALY)).willReturn(incident("dec-2"));
        given(sensorProperties.incident()).willReturn(new SensorProperties.Incident(60, 5000, true, 5, 90));

        // when
        service.handleHeartRateEmergency(new HeartRateEmergencyEvent(1L, "dec-2"));

        // then
        // 잠금이 첫 쿼리여야 MySQL REPEATABLE READ 스냅샷이 잠금을 얻은 뒤에 잡힌다. 판정 기록을 먼저 읽으면
        // 동시 위급이 앞 트랜잭션이 연 사건을 보지 못해 사건이 둘 열린다(LLD-0103).
        InOrder inOrder = inOrder(memberRepository, decisionRecordRepository, incidentService);
        inOrder.verify(memberRepository).findByIdForUpdate(1L);
        inOrder.verify(decisionRecordRepository).findByDecisionId("dec-2");
        inOrder.verify(incidentService).openForAlert(decision, IncidentKind.HR_ANOMALY);
    }

    private void givenSeniorLock() {
        given(memberRepository.findByIdForUpdate(1L))
                .willReturn(Optional.of(Member.createMember(MemberType.SENIOR, "시니어", "01000000000")));
    }

    private Incident incident(String decisionId) {
        return Incident.builder().incidentRef("inc-1").memberId(1L).decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1_000L).respondByMs(61_000L).build();
    }
}
