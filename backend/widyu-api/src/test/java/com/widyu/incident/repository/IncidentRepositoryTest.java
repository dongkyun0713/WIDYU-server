package com.widyu.incident.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
    private static final long RESPOND_BY_MS = OPENED_AT_MS + 60_000L;

    @Autowired private IncidentRepository incidentRepository;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("마감 안에 OK로 답하면 사건이 정상 종료로 닫힌다")
    void 마감_안에_OK로_답하면_사건이_정상_종료로_닫힌다() {
        // given
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, null, IncidentState.OK_CLOSED);

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
                ResponseVia.WATCH, RESPOND_BY_MS + 1_500L, 123L, IncidentState.OK_CLOSED);

        // then
        // 여기서 OK_CLOSED로 적으면 무응답이던 사건이 정상 종료로 둔갑하고 스케줄러 대상에서도 빠진다.
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getResponse()).isEqualTo(IncidentResponseValue.OK);
        assertThat(found.getRespondedAtMs()).isEqualTo(RESPOND_BY_MS + 1_500L);
        assertThat(found.getDeviceRespondedAtMs()).isEqualTo(123L);
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
                ResponseVia.PHONE, RESPOND_BY_MS - 1_000L, null, IncidentState.OK_CLOSED);

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
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, null, IncidentState.OK_CLOSED);

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.HELP,
                ResponseVia.PHONE, RESPOND_BY_MS - 1_000L, null, IncidentState.ESCALATED);

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
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, null, IncidentState.OK_CLOSED);

        // then
        assertThat(updated).isZero();
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getResponse()).isNull();
    }

    @Test
    @DisplayName("사후 판정을 넣으면 라벨과 판정자와 119 신고 시각이 함께 남는다")
    void 사후_판정을_넣으면_라벨과_판정자와_119_신고_시각이_함께_남는다() {
        // given
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.resolve(INCIDENT_REF, IncidentOutcome.TRUE_EMERGENCY,
                OTHER_ID, RESPOND_BY_MS + 120_000L, RESPOND_BY_MS + 60_000L);

        // then
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.RESOLVED);
        assertThat(found.getOutcome()).isEqualTo(IncidentOutcome.TRUE_EMERGENCY);
        assertThat(found.getResolvedBy()).isEqualTo(OTHER_ID);
        assertThat(found.getResolvedAtMs()).isEqualTo(RESPOND_BY_MS + 120_000L);
        assertThat(found.getEmergencyCalledAtMs()).isEqualTo(RESPOND_BY_MS + 60_000L);
    }

    @Test
    @DisplayName("이미 판정한 사건을 다시 판정하면 아무 행도 바뀌지 않고 첫 라벨이 남는다")
    void 이미_판정한_사건을_다시_판정하면_첫_라벨이_남는다() {
        // given
        incidentRepository.save(checkingIncident());
        incidentRepository.resolve(INCIDENT_REF, IncidentOutcome.TRUE_EMERGENCY,
                OTHER_ID, RESPOND_BY_MS + 120_000L, RESPOND_BY_MS + 60_000L);

        // when
        int updated = incidentRepository.resolve(INCIDENT_REF, IncidentOutcome.FALSE_ALARM,
                SENIOR_ID, RESPOND_BY_MS + 300_000L, null);

        // then
        // 라벨은 사람이 쓴 사실이다. 덮어쓰면 어느 쪽이 실제 판단이었는지 남지 않는다.
        assertThat(updated).isZero();
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getOutcome()).isEqualTo(IncidentOutcome.TRUE_EMERGENCY);
        assertThat(found.getResolvedBy()).isEqualTo(OTHER_ID);
        assertThat(found.getEmergencyCalledAtMs()).isEqualTo(RESPOND_BY_MS + 60_000L);
    }

    @Test
    @DisplayName("플래그 ON의 미응답 사건이 마감을 넘기면 상태 전환과 최초 알림 게이트가 각각 성공한다")
    void 플래그_ON의_미응답_사건은_상태_전환과_알림_게이트가_성공한다() {
        // given
        incidentRepository.save(checkingIncident());
        Incident answered = incident("inc-0002", SENIOR_ID, "dec-02");
        incidentRepository.save(answered);
        incidentRepository.respond("inc-0002", SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS - 3_000L, null, IncidentState.OK_CLOSED);

        // when
        Long id = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getId();
        int escalated = incidentRepository.escalateTimedOutIfDue(id, RESPOND_BY_MS + 1L);
        int claimed = incidentRepository.claimInitialAlertIfDue(id, RESPOND_BY_MS + 1L);

        // then
        // 답한 사건은 OPEN도 CHECKING도 아니라 대상이 아니다.
        assertThat(escalated).isEqualTo(1);
        assertThat(claimed).isEqualTo(1);
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getState())
                .isEqualTo(IncidentState.ESCALATED);
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getInitialAlertSentAtMs())
                .isEqualTo(RESPOND_BY_MS + 1L);
        assertThat(incidentRepository.findByIncidentRef("inc-0002").orElseThrow().getState())
                .isEqualTo(IncidentState.OK_CLOSED);
    }

    @Test
    @DisplayName("마감 시각에 OK가 도착하면 늦은 응답으로 남고 최초 알림 후보가 된다")
    void 마감_시각에_OK가_도착하면_최초_알림_후보가_된다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());

        // when
        incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.PHONE, RESPOND_BY_MS, 77L, IncidentState.OK_CLOSED);
        int sent = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS);

        // then
        assertThat(sent).isEqualTo(1);
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getDeviceRespondedAtMs()).isEqualTo(77L);
        assertThat(found.getInitialAlertSentAtMs()).isEqualTo(RESPOND_BY_MS);
    }

    @Test
    @DisplayName("늦은 OK로 상태가 올라가면 다음 폴링이 최초 알림을 한 번만 예약한다")
    void 늦은_OK로_상태가_올라가면_최초_알림을_한_번만_예약한다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());
        incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.WATCH, RESPOND_BY_MS + 1_000L, 12L, IncidentState.OK_CLOSED);

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS + 1_000L, 0L, PageRequest.of(0, 10));
        int first = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS + 1_000L);
        int second = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS + 2_000L);

        // then
        assertThat(due).containsExactly(incident.getId());
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    @DisplayName("HELP로 이미 올라간 미발송 사건은 상태 전환 없이 최초 알림만 한 번 예약한다")
    void HELP로_이미_올라간_미발송_사건은_최초_알림만_예약한다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());
        incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.HELP,
                ResponseVia.WATCH, RESPOND_BY_MS - 2_000L, null, IncidentState.ESCALATED);

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS - 1_000L, 0L, PageRequest.of(0, 10));
        int escalated = incidentRepository.escalateTimedOutIfDue(incident.getId(), RESPOND_BY_MS - 1_000L);
        int sent = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS - 1_000L);
        int repeated = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS - 500L);

        // then
        assertThat(due).containsExactly(incident.getId());
        assertThat(escalated).isZero();
        assertThat(sent).isEqualTo(1);
        assertThat(repeated).isZero();
        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getInitialAlertSentAtMs())
                .isEqualTo(RESPOND_BY_MS - 1_000L);
    }

    @Test
    @DisplayName("플래그 OFF로 이미 알림을 보낸 사건이 60초 무응답이면 상태만 올라간다")
    void 플래그_OFF로_이미_알림을_보낸_사건은_무응답에_상태만_올라간다() {
        // given
        Incident incident = checkingIncident();
        incident.markInitialAlertSent(OPENED_AT_MS + 1L);
        incidentRepository.save(incident);

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, 0L, PageRequest.of(0, 10));
        int escalated = incidentRepository.escalateTimedOutIfDue(incident.getId(), RESPOND_BY_MS + 1L);
        int claimed = incidentRepository.claimInitialAlertIfDue(incident.getId(), RESPOND_BY_MS + 1L);

        // then
        assertThat(due).containsExactly(incident.getId());
        assertThat(escalated).isEqualTo(1);
        assertThat(claimed).isZero();
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getInitialAlertSentAtMs()).isEqualTo(OPENED_AT_MS + 1L);
        assertThat(incidentRepository.findDueIds(RESPOND_BY_MS + 2L, 0L, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    @DisplayName("후보를 키셋으로 조회하면 마지막 사건 ID 뒤의 사건부터 순서대로 반환한다")
    void 후보를_키셋으로_조회하면_마지막_ID_뒤부터_반환한다() {
        // given
        Incident first = incidentRepository.save(checkingIncident());
        Incident second = incidentRepository.save(incident("inc-0002", SENIOR_ID, "dec-02"));
        Incident third = incidentRepository.save(incident("inc-0003", SENIOR_ID, "dec-03"));

        // when
        var firstPage = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, 0L, PageRequest.of(0, 1));
        var nextPage = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, first.getId(), PageRequest.of(0, 2));

        // then
        assertThat(firstPage).containsExactly(first.getId());
        assertThat(nextPage).containsExactly(second.getId(), third.getId());
    }

    @Test
    @DisplayName("낙상 사건이 마감을 넘기면 상태만 올라가고 심박 알림 후보에서는 빠진다")
    void 낙상_사건이_마감을_넘기면_심박_알림_후보에서는_빠진다() {
        // given
        Incident fall = checkingIncident();
        ReflectionTestUtils.setField(fall, "kind", IncidentKind.FALL_SUSPECTED);
        incidentRepository.save(fall);

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, 0L, PageRequest.of(0, 10));
        int updated = incidentRepository.escalateFallTimedOut(RESPOND_BY_MS + 1L);

        // then
        assertThat(due).isEmpty();
        assertThat(updated).isEqualTo(1);
        Incident found = incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getInitialAlertSentAtMs()).isNull();
    }

    @Test
    @DisplayName("안심구역에 재진입하면 열린 사건의 상황 종료 시각만 한 번 기록한다")
    void 안심구역에_재진입하면_열린_사건의_상황_종료_시각만_한_번_기록한다() {
        // given
        Incident safeZone = incidentRepository.save(safeZoneIncident());
        Incident heart = incidentRepository.save(incident("inc-heart", SENIOR_ID, "dec-heart"));

        // when
        int ended = incidentRepository.endOpenSafeZoneSituation(SENIOR_ID, RESPOND_BY_MS + 1L);
        int repeated = incidentRepository.endOpenSafeZoneSituation(SENIOR_ID, RESPOND_BY_MS + 2L);

        // then
        assertThat(ended).isEqualTo(1);
        assertThat(repeated).isZero();
        Incident found = incidentRepository.findById(safeZone.getId()).orElseThrow();
        assertThat(found.getSituationEndedAtMs()).isEqualTo(RESPOND_BY_MS + 1L);
        assertThat(found.getState()).isEqualTo(IncidentState.CHECKING);
        assertThat(incidentRepository.findById(heart.getId()).orElseThrow().getSituationEndedAtMs()).isNull();
    }

    @Test
    @DisplayName("안심구역에 재진입하면 OK로 닫힌 사건에도 상황 종료 시각을 기록한다")
    void 안심구역에_재진입하면_OK로_닫힌_사건에도_상황_종료_시각을_기록한다() {
        // given
        Incident safeZone = incidentRepository.save(safeZoneIncident());
        incidentRepository.respond(safeZone.getIncidentRef(), SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.PHONE, RESPOND_BY_MS - 1_000L, null, IncidentState.OK_CLOSED);

        // when
        int ended = incidentRepository.endOpenSafeZoneSituation(SENIOR_ID, RESPOND_BY_MS + 1L);

        // then
        assertThat(ended).isEqualTo(1);
        Incident found = incidentRepository.findById(safeZone.getId()).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.OK_CLOSED);
        assertThat(found.getSituationEndedAtMs()).isEqualTo(RESPOND_BY_MS + 1L);
    }

    @Test
    @DisplayName("안심구역 이탈 사건이 만료되면 공통 최초 알림 후보가 되고 한 번만 예약된다")
    void 안심구역_이탈_사건이_만료되면_공통_최초_알림_후보가_되고_한_번만_예약된다() {
        // given
        Incident safeZone = incidentRepository.save(safeZoneIncident());

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, 0L, PageRequest.of(0, 10));
        int escalated = incidentRepository.escalateTimedOutIfDue(safeZone.getId(), RESPOND_BY_MS + 1L);
        int claimed = incidentRepository.claimInitialAlertIfDue(safeZone.getId(), RESPOND_BY_MS + 1L);
        int repeated = incidentRepository.claimInitialAlertIfDue(safeZone.getId(), RESPOND_BY_MS + 2L);

        // then
        assertThat(due).containsExactly(safeZone.getId());
        assertThat(escalated).isEqualTo(1);
        assertThat(claimed).isEqualTo(1);
        assertThat(repeated).isZero();
        Incident found = incidentRepository.findById(safeZone.getId()).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.ESCALATED);
        assertThat(found.getInitialAlertSentAtMs()).isEqualTo(RESPOND_BY_MS + 1L);
    }

    private Incident checkingIncident() {
        Incident incident = incident(INCIDENT_REF, SENIOR_ID, "dec-01");
        incident.markChecking();
        return incident;
    }

    private Incident safeZoneIncident() {
        Incident incident = Incident.builder()
                .incidentRef("inc-safe-zone").memberId(SENIOR_ID)
                .kind(IncidentKind.SAFE_ZONE_EXIT)
                .openedAtMs(OPENED_AT_MS).respondByMs(RESPOND_BY_MS).build();
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
