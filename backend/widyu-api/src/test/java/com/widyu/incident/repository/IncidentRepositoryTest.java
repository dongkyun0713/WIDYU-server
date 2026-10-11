package com.widyu.incident.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.incident.Incident;
import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.IncidentGuardianResponse;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Autowired private IncidentGuardianResponseRepository guardianResponses;
    @Autowired private EntityManager entityManager;
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
    @DisplayName("정확히 마감 시각에 OK로 답하면 ESCALATED를 유지한다")
    void 정확히_마감_시각에_OK로_답하면_ESCALATED를_유지한다() {
        // given
        incidentRepository.save(checkingIncident());

        // when
        int updated = incidentRepository.respond(INCIDENT_REF, SENIOR_ID, IncidentResponseValue.OK,
                ResponseVia.PHONE, RESPOND_BY_MS, null, IncidentState.OK_CLOSED);

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(incidentRepository.findByIncidentRef(INCIDENT_REF).orElseThrow().getState())
                .isEqualTo(IncidentState.ESCALATED);
    }

    @Test
    @DisplayName("새 판정을 열린 사건에 붙이면 마지막 판정과 감지 수가 갱신된다")
    void 새_판정을_열린_사건에_붙이면_마지막_판정과_감지_수가_갱신된다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());

        // when
        int attached = incidentRepository.attachDetection(incident.getId(), "dec-02", OPENED_AT_MS + 30_000L);
        int repeated = incidentRepository.attachDetection(incident.getId(), "dec-02", OPENED_AT_MS + 31_000L);

        // then
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(attached).isEqualTo(1);
        assertThat(repeated).isZero();
        assertThat(found.getLastDecisionId()).isEqualTo("dec-02");
        assertThat(found.getLastDetectedAtMs()).isEqualTo(OPENED_AT_MS + 30_000L);
        assertThat(found.getDetectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("배치 판정 뒤 단건 감지를 붙이면 마지막 판정을 유지하고 배치 재시도를 집계하지 않는다")
    void 배치_판정_뒤_단건_감지를_붙이면_마지막_판정을_유지하고_재시도를_집계하지_않는다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());
        int batchAttached = incidentRepository.attachDetection(incident.getId(), "dec-02", OPENED_AT_MS + 10_000L);

        // when
        int singleAttached = incidentRepository.attachDetection(incident.getId(), null, OPENED_AT_MS + 20_000L);
        int batchRepeated = incidentRepository.attachDetection(incident.getId(), "dec-02", OPENED_AT_MS + 30_000L);

        // then
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(batchAttached).isEqualTo(1);
        assertThat(singleAttached).isEqualTo(1);
        assertThat(batchRepeated).isZero();
        assertThat(found.getLastDecisionId()).isEqualTo("dec-02");
        assertThat(found.getLastDetectedAtMs()).isEqualTo(OPENED_AT_MS + 20_000L);
        assertThat(found.getDetectionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 보호자의 다른 종류와 다른 보호자의 멈춤은 각각 한 행을 남긴다")
    void 다른_종류와_다른_보호자의_멈춤은_각각_한_행을_남긴다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());

        // when
        guardianResponses.save(IncidentGuardianResponse.of(incident.getId(), OTHER_ID,
                GuardianResponseType.MESSAGE_SENT, OPENED_AT_MS + 10_000L));
        guardianResponses.save(IncidentGuardianResponse.of(incident.getId(), OTHER_ID,
                GuardianResponseType.CALL_INITIATED, OPENED_AT_MS + 20_000L));
        guardianResponses.save(IncidentGuardianResponse.of(incident.getId(), SENIOR_ID,
                GuardianResponseType.ACKNOWLEDGED, OPENED_AT_MS + 30_000L));

        // then
        assertThat(guardianResponses.count()).isEqualTo(3);
        assertThat(guardianResponses.existsByIncidentId(incident.getId())).isTrue();
        assertThat(incidentRepository.findById(incident.getId()).orElseThrow().getGuardianResponseType()).isNull();
    }

    @Test
    @DisplayName("같은 보호자의 같은 멈춤 종류를 두 번 넣으면 고유 제약이 막는다")
    void 같은_보호자의_같은_멈춤_종류를_두_번_넣으면_고유_제약이_막는다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());
        guardianResponses.saveAndFlush(IncidentGuardianResponse.of(incident.getId(), OTHER_ID,
                GuardianResponseType.ACKNOWLEDGED, OPENED_AT_MS));

        // when / then
        assertThatThrownBy(() -> guardianResponses.saveAndFlush(IncidentGuardianResponse.of(
                incident.getId(), OTHER_ID, GuardianResponseType.ACKNOWLEDGED,
                OPENED_AT_MS + 1_000L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("첫 알림 게이트를 얻으면 같은 UPDATE에 2차 마감을 기록한다")
    void 첫_알림_게이트를_얻으면_같은_UPDATE에_2차_마감을_기록한다() {
        // given
        Incident incident = incidentRepository.save(checkingIncident());
        long nowMs = RESPOND_BY_MS + 1L;

        // when
        int first = incidentRepository.claimInitialAlertIfDue(incident.getId(), nowMs);
        int repeated = incidentRepository.claimInitialAlertIfDue(incident.getId(), nowMs);

        // then
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(first).isEqualTo(1);
        assertThat(repeated).isZero();
        assertThat(found.getInitialAlertSentAtMs()).isEqualTo(nowMs);
        assertThat(found.getSecondAlertDueAtMs()).isEqualTo(nowMs + 180_000L);
    }

    @Test
    @DisplayName("2차 게이트가 먼저 성공하면 멈춤 취소는 사건을 바꾸지 않는다")
    void 이차_게이트가_먼저_성공하면_멈춤_취소는_사건을_바꾸지_않는다() {
        // given
        Incident incident = checkingIncident();
        incident.markInitialAlertSent(OPENED_AT_MS + 1_000L);
        incident = incidentRepository.save(incident);
        long dueMs = OPENED_AT_MS + 181_000L;

        // when
        int sent = incidentRepository.claimSecondAlertIfDue(incident.getId(), dueMs);
        int repeated = incidentRepository.claimSecondAlertIfDue(incident.getId(), dueMs);
        int cancelled = incidentRepository.cancelSecondAlertIfPending(incident.getId(), dueMs);

        // then
        entityManager.clear();
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(sent).isEqualTo(1);
        assertThat(repeated).isZero();
        assertThat(cancelled).isZero();
        assertThat(found.getSecondAlertSentAtMs()).isEqualTo(dueMs);
        assertThat(found.getSecondAlertCancelledAtMs()).isNull();
    }

    @Test
    @DisplayName("멈춤 취소가 먼저 성공하면 2차 게이트는 사건을 바꾸지 않는다")
    void 멈춤_취소가_먼저_성공하면_이차_게이트는_사건을_바꾸지_않는다() {
        // given
        Incident incident = checkingIncident();
        incident.markInitialAlertSent(OPENED_AT_MS + 1_000L);
        incident = incidentRepository.save(incident);
        long dueMs = OPENED_AT_MS + 181_000L;

        // when
        int cancelled = incidentRepository.cancelSecondAlertIfPending(incident.getId(), dueMs);
        int sent = incidentRepository.claimSecondAlertIfDue(incident.getId(), dueMs);

        // then
        entityManager.clear();
        Incident found = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(cancelled).isEqualTo(1);
        assertThat(sent).isZero();
        assertThat(found.getSecondAlertCancelledAtMs()).isEqualTo(dueMs);
        assertThat(found.getSecondAlertSentAtMs()).isNull();
    }

    @Test
    @DisplayName("재시작 뒤에도 DB에 남은 2차 마감 사건을 키셋으로 조회한다")
    void 재시작_뒤에도_DB에_남은_이차_마감_사건을_조회한다() {
        // given
        Incident incident = checkingIncident();
        incident.markInitialAlertSent(OPENED_AT_MS + 1_000L);
        incident = incidentRepository.saveAndFlush(incident);
        entityManager.clear();

        // when
        var due = incidentRepository.findSecondAlertDueIds(OPENED_AT_MS + 181_000L,
                0L, PageRequest.of(0, 10));
        var after = incidentRepository.findSecondAlertDueIds(OPENED_AT_MS + 181_000L,
                incident.getId(), PageRequest.of(0, 10));

        // then
        assertThat(due).containsExactly(incident.getId());
        assertThat(after).isEmpty();
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
    @DisplayName("과거 안심구역 사건이 만료되어도 공통 최초 알림 후보와 상태 변경 대상에서 제외한다")
    void 과거_안심구역_사건이_만료되어도_최초_알림_대상에서_제외한다() {
        // given
        Incident safeZone = incidentRepository.save(safeZoneIncident());

        // when
        var due = incidentRepository.findDueIds(RESPOND_BY_MS + 1L, 0L, PageRequest.of(0, 10));
        int escalated = incidentRepository.escalateTimedOutIfDue(safeZone.getId(), RESPOND_BY_MS + 1L);
        int claimed = incidentRepository.claimInitialAlertIfDue(safeZone.getId(), RESPOND_BY_MS + 1L);
        int repeated = incidentRepository.claimInitialAlertIfDue(safeZone.getId(), RESPOND_BY_MS + 2L);

        // then
        assertThat(due).isEmpty();
        assertThat(escalated).isZero();
        assertThat(claimed).isZero();
        assertThat(repeated).isZero();
        Incident found = incidentRepository.findById(safeZone.getId()).orElseThrow();
        assertThat(found.getState()).isEqualTo(IncidentState.CHECKING);
        assertThat(found.getInitialAlertSentAtMs()).isNull();
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
