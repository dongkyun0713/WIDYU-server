package com.widyu.incident.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("IncidentRepository 조건부 갱신 테스트")
class IncidentRepositoryTest {

    private static final Long SENIOR_ID = 1023L;
    private static final Long OTHER_ID = 2048L;
    private static final String INCIDENT_REF = "inc-0001";
    private static final long OPENED_AT_MS = 1_760_000_000_000L;
    private static final long RESPOND_BY_MS = OPENED_AT_MS + 45_000L;

    @Autowired private IncidentRepository incidentRepository;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("마감 안에 OK로 답하면 사건이 정상 종료로 닫힌다")
    void 마감_안에_OK로_답하면_사건이_정상_종료로_닫힌다() {
        // given
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, IncidentState.OK_CLOSED);

        // then
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.OK_CLOSED);
        assertThat(found.getResponse()).isEqualTo(IncidentResponseValue.OK);
        assertThat(found.getResponseVia()).isEqualTo(ResponseVia.WATCH);
        assertThat(found.getRespondedAtMs()).isEqualTo(RESPOND_BY_MS - 3_000L);
    }

    @Test
    @DisplayName("마감을 넘겨 OK로 답하면 스케줄러가 돌기 전이어도 무응답으로 올라간다")
    void 마감을_넘겨_OK로_답하면_스케줄러가_돌기_전이어도_무응답으로_올라간다() {
        // given
        // 마감은 지났지만 아직 폴링 전이라 상태가 CHECKING인 순간이다.
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS + 1_500L, IncidentState.OK_CLOSED);

        // then
        // 여기서 OK_CLOSED로 적으면 무응답이던 사건이 정상 종료로 둔갑하고 스케줄러 대상에서도 빠진다.
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getResponse()).isEqualTo(IncidentResponseValue.OK);
        assertThat(found.getRespondedAtMs()).isEqualTo(RESPOND_BY_MS + 1_500L);
    }

    @Test
    @DisplayName("무응답으로 올라간 사건에 마감 안 시각으로 OK가 와도 상태를 되돌리지 않는다")
    void 무응답으로_올라간_사건에_OK가_와도_상태를_되돌리지_않는다() {
        // given
        Incident escalated = checkingIncident();
        ReflectionTestUtils.setField(escalated, "state", IncidentState.ESCALATED);
        incidentRepository.save(escalated);

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.PHONE, RESPOND_BY_MS - 1_000L, IncidentState.OK_CLOSED);

        // then
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getResponse()).isEqualTo(IncidentResponseValue.OK);
    }

    @Test
    @DisplayName("이미 답한 사건에 다시 응답하면 아무 행도 바뀌지 않는다")
    void 이미_답한_사건에_다시_응답하면_아무_행도_바뀌지_않는다() {
        // given
        incidentRepository.save(checkingIncident());
        incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, IncidentState.OK_CLOSED);

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.HELP,
                ResponseVia.PHONE, RESPOND_BY_MS - 1_000L, IncidentState.ESCALATED);

        // then
        assertThat(updated).isZero();
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getResponse()).isEqualTo(IncidentResponseValue.OK);
        assertThat(found.getState()).isEqualTo(IncidentState.OK_CLOSED);
    }

    @Test
    @DisplayName("다른 회원이 응답하면 아무 행도 바뀌지 않는다")
    void 다른_회원이_응답하면_아무_행도_바뀌지_않는다() {
        // given
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, OTHER_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, IncidentState.OK_CLOSED);

        // then
        assertThat(updated).isZero();
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getResponse()).isNull();
    }

    @Test
    @DisplayName("마감을 넘긴 미응답 사건만 무응답으로 올라간다")
    void 마감을_넘긴_미응답_사건만_무응답으로_올라간다() {
        // given
        incidentRepository.save(checkingIncident());
        Incident answered = incident("inc-0002", SENIOR_ID, "dec-02");
        incidentRepository.save(answered);
        incidentRepository.respond("inc-0002", SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, IncidentState.OK_CLOSED);

        // when
        int escalated = incidentRepository.escalateTimedOut(RESPOND_BY_MS + 1L);

        // then
        // 답한 사건은 OPEN도 CHECKING도 아니라 대상이 아니다.
        assertThat(escalated).isEqualTo(1);
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getState())
                .isEqualTo(IncidentState.ESCALATED);
        assertThat(incidentRepository.findByIncidentRef("inc-0002").orElseThrow().getState())
                .isEqualTo(IncidentState.OK_CLOSED);
    }

    private Incident checkingIncident() {
        Incident incident = incident(INCIDENT_REF, SENIOR_ID, "dec-01");
        incident.markChecking();
        return incident;
    }

    private Incident incident(String incidentRef, Long memberId, String decisionId) {
        return Incident.builder()
                .incidentRef(incidentRef)
                .memberId(memberId)
                .runId("run-0f3a")
                .decisionId(decisionId)
                .kind(IncidentKind.HR_ANOMALY)
                .level("EMERGENCY")
                .openedAtMs(OPENED_AT_MS)
                .respondByMs(RESPOND_BY_MS)
                .build();
    }
}
