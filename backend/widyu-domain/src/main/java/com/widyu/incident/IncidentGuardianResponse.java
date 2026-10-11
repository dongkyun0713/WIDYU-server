package com.widyu.incident;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(name = "incident_guardian_response", uniqueConstraints =
        @UniqueConstraint(name = "uk_incident_guardian_response_kind",
                columnNames = {"incident_id", "guardian_member_id", "response_type"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentGuardianResponse extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "guardian_member_id", nullable = false)
    private Long guardianMemberId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "response_type", nullable = false, length = 20)
    private GuardianResponseType responseType;

    @Column(name = "responded_at_ms", nullable = false)
    private Long respondedAtMs;

    private IncidentGuardianResponse(Long incidentId, Long guardianMemberId,
            GuardianResponseType responseType, long respondedAtMs) {
        this.incidentId = incidentId;
        this.guardianMemberId = guardianMemberId;
        this.responseType = responseType;
        this.respondedAtMs = respondedAtMs;
    }

    public static IncidentGuardianResponse of(Long incidentId, Long guardianMemberId,
            GuardianResponseType responseType, long respondedAtMs) {
        return new IncidentGuardianResponse(incidentId, guardianMemberId, responseType, respondedAtMs);
    }
}
