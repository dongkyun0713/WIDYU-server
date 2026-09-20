package com.widyu.study;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 현재 참여 행을 변경해도 동의·보관·철회 상태를 추적할 수 있는 snapshot. */
@Entity
@Getter
@Table(name = "study_participation_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyParticipationHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "study_participation_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_participation_id", nullable = false)
    private StudyParticipation participation;

    @Enumerated(EnumType.STRING)
    @Column(name = "history_type", nullable = false, length = 24)
    private StudyParticipationHistoryType historyType;

    @Column(name = "data_policy", length = 50)
    private String dataPolicy;

    @Column(name = "identified_until")
    private LocalDate identifiedUntil;

    @Column(name = "pseudonymized_at")
    private LocalDate pseudonymizedAt;

    @Column(name = "research_until")
    private LocalDate researchUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StudyParticipationStatus status;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_scope", length = 24)
    private WithdrawalScope withdrawalScope;

    @Column(name = "deletion_processed_at")
    private LocalDateTime deletionProcessedAt;

    @Builder
    private StudyParticipationHistory(
            StudyParticipation participation, StudyParticipationHistoryType historyType,
            String dataPolicy, LocalDate identifiedUntil, LocalDate pseudonymizedAt,
            LocalDate researchUntil, StudyParticipationStatus status, LocalDateTime withdrawnAt,
            WithdrawalScope withdrawalScope, LocalDateTime deletionProcessedAt) {
        this.participation = participation;
        this.historyType = historyType;
        this.dataPolicy = dataPolicy;
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
        this.status = status;
        this.withdrawnAt = withdrawnAt;
        this.withdrawalScope = withdrawalScope;
        this.deletionProcessedAt = deletionProcessedAt;
    }

    public static StudyParticipationHistory snapshotOf(
            StudyParticipation participation, StudyParticipationHistoryType historyType) {
        return StudyParticipationHistory.builder()
                .participation(participation)
                .historyType(historyType)
                .dataPolicy(participation.getDataPolicy())
                .identifiedUntil(participation.getIdentifiedUntil())
                .pseudonymizedAt(participation.getPseudonymizedAt())
                .researchUntil(participation.getResearchUntil())
                .status(participation.getStatus())
                .withdrawnAt(participation.getWithdrawnAt())
                .withdrawalScope(participation.getWithdrawalScope())
                .deletionProcessedAt(participation.getDeletionProcessedAt())
                .build();
    }
}
