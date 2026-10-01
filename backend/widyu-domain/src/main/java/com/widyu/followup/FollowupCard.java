package com.widyu.followup;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "followup_card", uniqueConstraints = {
        @UniqueConstraint(name = "uk_followup_card_incident", columnNames = "incident_ref"),
        @UniqueConstraint(name = "uk_followup_card_visit", columnNames = {"senior_id", "visit_key"})
}, indexes = {
        @Index(name = "idx_followup_card_senior_state_expiry", columnList = "senior_id,state,expires_at_ms"),
        @Index(name = "idx_followup_card_expiry", columnList = "state,expires_at_ms,id")
})
public class FollowupCard extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_ref", nullable = false, length = 40)
    private String incidentRef;

    @Column(name = "senior_id", nullable = false)
    private Long seniorId;

    @Column(name = "issued_at_ms", nullable = false)
    private long issuedAtMs;

    @Column(name = "event_at_ms", nullable = false)
    private long eventAtMs;

    @Column(name = "expires_at_ms", nullable = false)
    private long expiresAtMs;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 32)
    private FollowupCardState state;

    @Column(name = "question_set_version", nullable = false, length = 32)
    private String questionSetVersion;

    @Column(name = "visit_key", length = 36)
    private String visitKey;

    public static FollowupCard issue(String incidentRef, Long seniorId, String questionSetVersion,
            long eventAtMs, long nowMs) {
        FollowupCard card = new FollowupCard();
        card.incidentRef = incidentRef;
        card.seniorId = seniorId;
        card.questionSetVersion = questionSetVersion;
        card.eventAtMs = eventAtMs;
        card.issuedAtMs = nowMs;
        card.expiresAtMs = nowMs + 43_200_000L;
        card.state = FollowupCardState.ISSUED;
        return card;
    }

}
