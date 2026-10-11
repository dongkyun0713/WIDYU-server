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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(name = "incident_response_attempt", indexes =
        @Index(name = "idx_incident_response_attempt_incident", columnList = "incident_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentResponseAttempt extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response", nullable = false, length = 8)
    private IncidentResponseValue response;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response_via", nullable = false, length = 16)
    private ResponseVia responseVia;

    @Column(name = "device_responded_at_ms")
    private Long deviceRespondedAtMs;

    @Column(name = "received_at_ms", nullable = false)
    private Long receivedAtMs;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "outcome", nullable = false, length = 16)
    private ResponseAttemptOutcome outcome;

    private IncidentResponseAttempt(Long incidentId, IncidentResponseValue response, ResponseVia responseVia,
            Long deviceRespondedAtMs, long receivedAtMs, ResponseAttemptOutcome outcome) {
        this.incidentId = incidentId;
        this.response = response;
        this.responseVia = responseVia;
        this.deviceRespondedAtMs = deviceRespondedAtMs;
        this.receivedAtMs = receivedAtMs;
        this.outcome = outcome;
    }

    public static IncidentResponseAttempt of(Long incidentId, IncidentResponseValue response,
            ResponseVia responseVia, Long deviceRespondedAtMs, long receivedAtMs, ResponseAttemptOutcome outcome) {
        return new IncidentResponseAttempt(incidentId, response, responseVia, deviceRespondedAtMs,
                receivedAtMs, outcome);
    }
}
