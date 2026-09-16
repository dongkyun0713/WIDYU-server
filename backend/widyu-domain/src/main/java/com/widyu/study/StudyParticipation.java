package com.widyu.study;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 국내 실증(IRB) 연구 참여 등록 정보(정책서 1.5.1~1.5.3).
 * 보존 정책은 이 행의 {@code study_id + participation_id}가 정한다.
 * {@code member}는 재식별 키이며 파기 시 null 처리한다.
 */
@Entity
@Getter
@Table(
    name = "study_participation",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_study_participation_id",
        columnNames = {"participation_id"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyParticipation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "study_participation_id")
    private Long id;

    @Column(name = "study_id", nullable = false, length = 50)
    private String studyId;

    @Column(name = "participation_id", nullable = false, length = 50)
    private String participationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_policy", nullable = false, length = 20)
    private DataPolicy dataPolicy;

    @Column(name = "identified_until", nullable = false)
    private LocalDate identifiedUntil;

    @Column(name = "pseudonymized_at", nullable = false)
    private LocalDate pseudonymizedAt;

    @Column(name = "research_until", nullable = false)
    private LocalDate researchUntil;

    @Column(name = "consent_version", nullable = false, length = 50)
    private String consentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StudyParticipationStatus status;

    @Builder(access = AccessLevel.PRIVATE)
    private StudyParticipation(
            String studyId,
            String participationId,
            Member member,
            DataPolicy dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil,
            String consentVersion
    ) {
        this.studyId = studyId;
        this.participationId = participationId;
        this.member = member;
        this.dataPolicy = dataPolicy;
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
        this.consentVersion = consentVersion;
        this.status = StudyParticipationStatus.ACTIVE;
    }

    public static StudyParticipation of(
            String studyId,
            String participationId,
            Member member,
            DataPolicy dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil,
            String consentVersion
    ) {
        validateRetentionOrder(identifiedUntil, pseudonymizedAt, researchUntil);
        return StudyParticipation.builder()
                .studyId(studyId)
                .participationId(participationId)
                .member(member)
                .dataPolicy(dataPolicy)
                .identifiedUntil(identifiedUntil)
                .pseudonymizedAt(pseudonymizedAt)
                .researchUntil(researchUntil)
                .consentVersion(consentVersion)
                .build();
    }

    /** 보존 기간 변경(정책서 1.5.3). ACTIVE 상태에서만 허용하며 이력은 호출자가 남긴다. */
    public void changeRetention(
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil,
            String consentVersion
    ) {
        if (status != StudyParticipationStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE);
        }
        validateRetentionOrder(identifiedUntil, pseudonymizedAt, researchUntil);
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
        this.consentVersion = consentVersion;
    }

    public boolean isActive() {
        return status == StudyParticipationStatus.ACTIVE;
    }

    /** identified_until <= pseudonymized_at <= research_until (정책서 1.5.2). */
    private static void validateRetentionOrder(
            LocalDate identifiedUntil, LocalDate pseudonymizedAt, LocalDate researchUntil) {
        if (identifiedUntil.isAfter(pseudonymizedAt) || pseudonymizedAt.isAfter(researchUntil)) {
            throw new BusinessException(ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        }
    }
}
