package com.widyu.followup;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "followup_answer", uniqueConstraints =
        @UniqueConstraint(name = "uk_followup_answer_card", columnNames = "card_id"))
public class FollowupAnswer extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private FollowupCard card;

    @Column(name = "question_set_version", nullable = false, length = 32)
    private String questionSetVersion;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "q1", length = 32)
    private FollowupQ1 q1;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "q2", length = 32)
    private FollowupQ2 q2;

    @Column(name = "q3", length = 128)
    private String q3;

    @Column(name = "device_submitted_at_ms", nullable = false)
    private long deviceSubmittedAtMs;

    @Column(name = "server_received_at_ms", nullable = false)
    private long serverReceivedAtMs;

    public static FollowupAnswer of(FollowupCard card, FollowupQ1 q1, FollowupQ2 q2, String q3,
            long deviceSubmittedAtMs, long serverReceivedAtMs) {
        FollowupAnswer answer = new FollowupAnswer();
        answer.card = card;
        answer.questionSetVersion = card.getQuestionSetVersion();
        answer.q1 = q1;
        answer.q2 = q2;
        answer.q3 = q3;
        answer.deviceSubmittedAtMs = deviceSubmittedAtMs;
        answer.serverReceivedAtMs = serverReceivedAtMs;
        return answer;
    }
}
