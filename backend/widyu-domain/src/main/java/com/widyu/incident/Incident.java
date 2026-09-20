package com.widyu.incident;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 위급 판정 하나가 연 사건(LLD-0054 4절, ADR-0035 결정 4).
 *
 * <p>판정 기록({@code decision_record})과 나눠 둔다. 저쪽은 「무엇을 보고 언제 그렇게 말했는가」이고
 * 여기는 「본인이 뭐라고 답했고 보호자가 나중에 뭐라고 판정했는가」다(정책 1.8.1). 사후 판정
 * {@code outcome}이 실증의 지도학습 라벨이라, 판정 사실과 섞으면 라벨이 판정의 결과인지 사람의
 * 판단인지 사후에 갈라지지 않는다.
 *
 * <p>심박 값·판정 사유·좌표는 이 행에 두지 않는다. 그건 판정 기록의 몫이다.
 *
 * <p>enum 열은 모두 {@code VARCHAR}로 못박는다. Hibernate 6은 MySQL에서 {@code @Enumerated(STRING)}을
 * native {@code ENUM}으로 매핑해 운영 DDL(VARCHAR)과 어긋난다.
 */
@Entity
@Getter
@Table(
    name = "incident",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_incident_incident_ref", columnNames = "incident_ref"),
        @UniqueConstraint(name = "uk_incident_decision_id", columnNames = "decision_id")
    },
    indexes = {
        @Index(name = "idx_incident_member_time", columnList = "member_id, opened_at_ms"),
        @Index(name = "idx_incident_run_time", columnList = "run_id, opened_at_ms"),
        @Index(name = "idx_incident_state_deadline", columnList = "state, respond_by_ms")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "incident_id")
    private Long id;

    /** 외부에 내보내는 식별자. 내보내기의 {@code incident_id}가 이 값이다(형식서 §3.7). */
    @Column(name = "incident_ref", nullable = false, length = 40)
    private String incidentRef;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "run_id", length = 64)
    private String runId;

    /** 이 사건을 연 판정. 판정 하나 = 사건 하나라 UK다. */
    @Column(name = "decision_id", nullable = false, length = 40)
    private String decisionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private IncidentKind kind;

    /** 판정의 {@code severity}를 그대로 복사한다. 값 목록은 미정이라 검증하지 않는다(ADR-0035 후속). */
    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "opened_at_ms", nullable = false)
    private Long openedAtMs;

    @Column(name = "respond_by_ms", nullable = false)
    private Long respondByMs;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response", length = 8)
    private IncidentResponseValue response;

    @Column(name = "responded_at_ms")
    private Long respondedAtMs;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response_via", length = 16)
    private ResponseVia responseVia;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 16)
    private IncidentState state;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "outcome", length = 20)
    private IncidentOutcome outcome;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at_ms")
    private Long resolvedAtMs;

    /** 보호자가 입력한 119 신고 시각. 서버는 신고하지 않고 그 사실만 적는다(ADR-0035 결정 6). */
    @Column(name = "emergency_called_at_ms")
    private Long emergencyCalledAtMs;

    @Builder
    private Incident(
            String incidentRef, Long memberId, String runId, String decisionId,
            IncidentKind kind, String level, Long openedAtMs, Long respondByMs) {
        this.incidentRef = incidentRef;
        this.memberId = memberId;
        this.runId = runId;
        this.decisionId = decisionId;
        this.kind = kind;
        this.level = level;
        this.openedAtMs = openedAtMs;
        this.respondByMs = respondByMs;
        this.state = IncidentState.OPEN;
    }

    /** 본인확인 푸시를 큐에 넣었다는 사실. 마감을 넘겨 이미 올라간 상태는 되돌리지 않는다. */
    public void markChecking() {
        if (this.state != IncidentState.OPEN) {
            return;
        }
        this.state = IncidentState.CHECKING;
    }

    public boolean isAnswered() {
        return this.response != null;
    }

    /**
     * 본인 응답을 남긴다(LLD-0054 5.2).
     *
     * <p>마감 뒤 늦게 온 {@code OK}는 응답만 적고 상태는 {@code ESCALATED}로 둔다. 보호자에게 이미
     * 알림이 나간 사건을 「괜찮았던 일」로 되돌리면 그 알림이 왜 갔는지 설명할 자료가 없어진다.
     */
    public void respond(IncidentResponseValue response, ResponseVia responseVia, long respondedAtMs) {
        this.response = response;
        this.responseVia = responseVia;
        this.respondedAtMs = respondedAtMs;
        if (response == IncidentResponseValue.HELP) {
            this.state = IncidentState.ESCALATED;
            return;
        }
        if (this.state == IncidentState.ESCALATED) {
            return;
        }
        this.state = IncidentState.OK_CLOSED;
    }

    /** 보호자의 사후 판정. 라벨은 한 번만 붙이고 덮어쓰지 않는다(LLD-0054 5.4). */
    public void resolve(IncidentOutcome outcome, Long resolvedBy, long resolvedAtMs, Long emergencyCalledAtMs) {
        this.outcome = outcome;
        this.resolvedBy = resolvedBy;
        this.resolvedAtMs = resolvedAtMs;
        this.emergencyCalledAtMs = emergencyCalledAtMs;
        this.state = IncidentState.RESOLVED;
    }
}
