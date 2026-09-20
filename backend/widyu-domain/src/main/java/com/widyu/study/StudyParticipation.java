package com.widyu.study;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 한 회원의 한 번의 국내 실증 참여(LLD-0052). 실증 참여 여부와 연구 보관 정책의 정본이며,
 * 연구 회차({@link com.widyu.run.CollectionRun})가 이 행을 참조한다.
 *
 * <p>IRB 승인 전에는 보관 날짜와 정책이 비어 있을 수 있다. 날짜를 정할 때는 셋을 함께 정한다.
 */
@Entity
@Getter
@Table(
    name = "study_participation",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_study_participation_id",
            columnNames = {"participation_id"}
        ),
        @UniqueConstraint(
            name = "uk_study_participation_active",
            columnNames = {"study_id", "member_id", "active_key"}
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyParticipation extends BaseTimeEntity {

    private static final String ACTIVE_KEY = "1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "study_participation_id")
    private Long id;

    @Column(name = "study_id", nullable = false, length = 50)
    private String studyId;

    /** 서버 발급 외부 식별자. 클라이언트가 정하지 않는다. */
    @Column(name = "participation_id", nullable = false, length = 50)
    private String participationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** 서면 연구동의 판. */
    @Column(name = "consent_version", nullable = false, length = 50)
    private String consentVersion;

    /** 서면 연구동의 서명일. */
    @Column(name = "consented_at", nullable = false)
    private LocalDate consentedAt;

    /** 선택 동의 항목별 동의 여부. 직접식별자는 담지 않는다. */
    @ElementCollection
    @CollectionTable(
        name = "study_participation_consent",
        joinColumns = @JoinColumn(name = "study_participation_id")
    )
    @MapKeyColumn(name = "consent_key", length = 64)
    @Column(name = "granted", nullable = false)
    private Map<String, Boolean> consents = new LinkedHashMap<>();

    @Column(name = "data_policy", length = 50)
    private String dataPolicy;

    @Column(name = "identified_until")
    private LocalDate identifiedUntil;

    @Column(name = "pseudonymized_at")
    private LocalDate pseudonymizedAt;

    @Column(name = "research_until")
    private LocalDate researchUntil;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private StudyParticipationStatus status;

    /**
     * ACTIVE일 때만 1이다. {@code (study_id, member_id, active_key)} UK로 같은 연구·회원의 진행 중인
     * 참여 하나를 DB가 보장한다. ACTIVE가 아닌 행은 null이라 제약에서 빠지므로 철회·종료한 참여는
     * 얼마든지 쌓인다. 회차의 {@code open_marker}와 같은 방식이다.
     */
    @Column(name = "active_key", length = 1)
    private String activeKey;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "withdrawal_scope", length = 24)
    private WithdrawalScope withdrawalScope;

    /** 일부 철회의 대상 항목. 전체 철회면 비어 있다. */
    @ElementCollection
    @CollectionTable(
        name = "study_participation_withdrawal_item",
        joinColumns = @JoinColumn(name = "study_participation_id")
    )
    @Column(name = "consent_key", length = 64)
    private Set<String> withdrawalConsentKeys = new LinkedHashSet<>();

    /** 철회 후 운영자가 수동 삭제를 마친 시각. 삭제 실행은 이 도메인 밖이다. */
    @Column(name = "deletion_processed_at")
    private LocalDateTime deletionProcessedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private StudyParticipation(
            String studyId,
            String participationId,
            Member member,
            String consentVersion,
            LocalDate consentedAt,
            Map<String, Boolean> consents,
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil
    ) {
        validateRetention(dataPolicy, identifiedUntil, pseudonymizedAt, researchUntil);
        this.studyId = studyId;
        this.participationId = participationId;
        this.member = member;
        this.consentVersion = consentVersion;
        this.consentedAt = consentedAt;
        if (consents != null) {
            this.consents.putAll(consents);
        }
        this.dataPolicy = dataPolicy;
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
        changeStatus(StudyParticipationStatus.ACTIVE);
    }

    public static StudyParticipation of(
            String studyId,
            String participationId,
            Member member,
            String consentVersion,
            LocalDate consentedAt,
            Map<String, Boolean> consents,
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil
    ) {
        return StudyParticipation.builder()
                .studyId(studyId)
                .participationId(participationId)
                .member(member)
                .consentVersion(consentVersion)
                .consentedAt(consentedAt)
                .consents(consents)
                .dataPolicy(dataPolicy)
                .identifiedUntil(identifiedUntil)
                .pseudonymizedAt(pseudonymizedAt)
                .researchUntil(researchUntil)
                .build();
    }

    /** IRB 보관 계획 수정. 이력은 호출자가 snapshot으로 남긴다. */
    public void changeRetention(
            String dataPolicy, LocalDate identifiedUntil, LocalDate pseudonymizedAt, LocalDate researchUntil) {
        validateRetention(dataPolicy, identifiedUntil, pseudonymizedAt, researchUntil);
        this.dataPolicy = dataPolicy;
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
    }

    /** 철회는 상태만 바꾼다. 실제 자료 삭제는 별도 수동 절차다(LLD-0052 5절). */
    public void withdraw(WithdrawalScope scope, Set<String> consentKeys, LocalDateTime withdrawnAt) {
        if (status == StudyParticipationStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE);
        }
        if (scope == WithdrawalScope.SELECTED_CONSENTS) {
            validateWithdrawableConsents(consentKeys);
        }
        changeStatus(StudyParticipationStatus.WITHDRAWN);
        this.withdrawalScope = scope;
        this.withdrawnAt = withdrawnAt;
        this.withdrawalConsentKeys.clear();
        if (scope == WithdrawalScope.SELECTED_CONSENTS) {
            this.withdrawalConsentKeys.addAll(consentKeys);
        }
    }

    /** 받지 않았거나 이미 거절한 동의는 철회할 것이 없다. 철회 항목은 실제 부여된 동의여야 한다. */
    private void validateWithdrawableConsents(Set<String> consentKeys) {
        if (consentKeys == null || consentKeys.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.STUDY_WITHDRAWAL_CONSENT_INVALID, "일부 철회에는 철회할 동의 항목이 필요합니다.");
        }
        for (String consentKey : consentKeys) {
            if (!Boolean.TRUE.equals(consents.get(consentKey))) {
                throw new BusinessException(ErrorCode.STUDY_WITHDRAWAL_CONSENT_INVALID);
            }
        }
    }

    public void markDeletionProcessed(LocalDateTime processedAt) {
        if (status != StudyParticipationStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "철회된 참여 기록만 삭제 처리를 기록할 수 있습니다.");
        }
        this.deletionProcessedAt = processedAt;
    }

    public boolean isActive() {
        return status == StudyParticipationStatus.ACTIVE;
    }

    /** 상태와 UK 표식은 항상 함께 바뀐다. 상태를 바꾸는 경로는 모두 이 메서드를 지난다. */
    private void changeStatus(StudyParticipationStatus status) {
        this.status = status;
        if (status == StudyParticipationStatus.ACTIVE) {
            this.activeKey = ACTIVE_KEY;
            return;
        }
        this.activeKey = null;
    }

    /**
     * 보관 계획은 정책과 날짜 셋이 전부이거나 전무다. IRB 승인 전이라 아직 못 정한 상태와,
     * 일부만 정해 빠진 값이 무한 보관으로 읽히는 상태를 구분한다(LLD-0052 3절).
     * 날짜만 있으면 무슨 규정으로 지우는지 모르고, 정책만 있으면 언제까지인지 모른다.
     */
    private static void validateRetention(String dataPolicy, LocalDate identifiedUntil,
            LocalDate pseudonymizedAt, LocalDate researchUntil) {
        boolean policyAbsent = dataPolicy == null || dataPolicy.isBlank();
        if (policyAbsent && identifiedUntil == null && pseudonymizedAt == null && researchUntil == null) {
            return;
        }
        if (policyAbsent || identifiedUntil == null || pseudonymizedAt == null || researchUntil == null) {
            throw new BusinessException(ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        }
        if (identifiedUntil.isAfter(pseudonymizedAt) || pseudonymizedAt.isAfter(researchUntil)) {
            throw new BusinessException(ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        }
    }
}
