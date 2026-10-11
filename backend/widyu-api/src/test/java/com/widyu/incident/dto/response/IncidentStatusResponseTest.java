package com.widyu.incident.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IncidentStatusResponseTest {
    private static final long OPENED_AT_MS = 1_760_000_000_000L;
    private static final long WINDOW_MS = 300_000L;

    @Test
    @DisplayName("마지막 감지 뒤 5분 미만에 상태를 조회하면 열린 상황을 반환한다")
    void 마지막_감지_뒤_오분_미만에_조회하면_열린_상황을_반환한다() {
        // given
        Incident incident = incident();

        // when
        IncidentStatusResponse response = IncidentStatusResponse.from(incident,
                OPENED_AT_MS + WINDOW_MS - 1L, WINDOW_MS, List.of(GuardianResponseType.ACKNOWLEDGED));

        // then
        assertThat(response.status()).isEqualTo(IncidentStatusResponse.Status.OPEN);
        assertThat(response.endedAtMs()).isNull();
        assertThat(response.endedReason()).isNull();
        assertThat(response.myResponse()).containsExactly(GuardianResponseType.ACKNOWLEDGED);
        assertThat(response.lastDetectedAtMs()).isEqualTo(OPENED_AT_MS);
    }

    @Test
    @DisplayName("마지막 감지 뒤 정확히 5분에 조회하면 DB를 바꾸지 않고 끝난 상황을 반환한다")
    void 마지막_감지_뒤_정확히_오분에_조회하면_끝난_상황을_반환한다() {
        // given
        Incident incident = incident();

        // when
        IncidentStatusResponse response = IncidentStatusResponse.from(incident,
                OPENED_AT_MS + WINDOW_MS, WINDOW_MS, List.of());

        // then
        assertThat(response.status()).isEqualTo(IncidentStatusResponse.Status.ENDED);
        assertThat(response.endedReason()).isEqualTo(IncidentStatusResponse.EndedReason.WINDOW_ELAPSED);
        assertThat(response.endedAtMs()).isEqualTo(OPENED_AT_MS + WINDOW_MS);
        assertThat(response.state()).isEqualTo(IncidentState.CHECKING);
        assertThat(incident.getSituationEndedAtMs()).isNull();
    }

    @Test
    @DisplayName("시니어 OK로 종료한 사건을 조회하면 SENIOR_OK를 반환한다")
    void 시니어_OK로_종료한_사건을_조회하면_SENIOR_OK를_반환한다() {
        // given
        Incident incident = incident();
        ReflectionTestUtils.setField(incident, "state", IncidentState.OK_CLOSED);
        ReflectionTestUtils.setField(incident, "respondedAtMs", OPENED_AT_MS + 12_000L);

        // when
        IncidentStatusResponse response = IncidentStatusResponse.from(incident,
                OPENED_AT_MS + 13_000L, WINDOW_MS, List.of());

        // then
        assertThat(response.status()).isEqualTo(IncidentStatusResponse.Status.ENDED);
        assertThat(response.endedReason()).isEqualTo(IncidentStatusResponse.EndedReason.SENIOR_OK);
        assertThat(response.endedAtMs()).isEqualTo(OPENED_AT_MS + 12_000L);
    }

    @Test
    @DisplayName("보호자 사후 판정으로 종료한 사건을 조회하면 RESOLVED를 반환한다")
    void 보호자_사후_판정으로_종료한_사건을_조회하면_RESOLVED를_반환한다() {
        // given
        Incident incident = incident();
        ReflectionTestUtils.setField(incident, "state", IncidentState.RESOLVED);
        ReflectionTestUtils.setField(incident, "resolvedAtMs", OPENED_AT_MS + 20_000L);

        // when
        IncidentStatusResponse response = IncidentStatusResponse.from(incident,
                OPENED_AT_MS + 21_000L, WINDOW_MS, List.of());

        // then
        assertThat(response.status()).isEqualTo(IncidentStatusResponse.Status.ENDED);
        assertThat(response.endedReason()).isEqualTo(IncidentStatusResponse.EndedReason.RESOLVED);
        assertThat(response.endedAtMs()).isEqualTo(OPENED_AT_MS + 20_000L);
    }

    @Test
    @DisplayName("저장된 종료 시각이 있으면 상태가 열려 있어도 그 시각으로 끝난 상황을 반환한다")
    void 저장된_종료_시각이_있으면_끝난_상황을_반환한다() {
        // given
        Incident incident = incident();
        incident.endSituation(OPENED_AT_MS + 90_000L);

        // when
        IncidentStatusResponse response = IncidentStatusResponse.from(incident,
                OPENED_AT_MS + 91_000L, WINDOW_MS, List.of());

        // then
        assertThat(response.status()).isEqualTo(IncidentStatusResponse.Status.ENDED);
        assertThat(response.endedAtMs()).isEqualTo(OPENED_AT_MS + 90_000L);
        assertThat(response.endedReason()).isEqualTo(IncidentStatusResponse.EndedReason.WINDOW_ELAPSED);
    }

    private Incident incident() {
        Incident incident = Incident.builder().incidentRef("inc-status-740").memberId(101L)
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(OPENED_AT_MS)
                .respondByMs(OPENED_AT_MS + 60_000L).build();
        incident.markChecking();
        return incident;
    }
}
