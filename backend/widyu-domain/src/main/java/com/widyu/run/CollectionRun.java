package com.widyu.run;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.member.Member;
import com.widyu.study.StudyParticipation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * 측정회차(LLD-0045 4절, 작업지시서 B8).
 * 「누가·어떤 기기를·어디에 차고·언제부터 언제까지·몇 회차로」를 묶는 단위다.
 * 실증은 기기를 여러 참가자가 돌려 쓰므로 기기 번호만으로는 자료가 누구 것인지 알 수 없고,
 * 이 회차가 그 다리를 놓는다.
 *
 * <p>사람이 읽는 회차 번호는 {@code protocol_ref}에 두고 {@code run_id}는 서버가 발급한다.
 */
@Entity
@Getter
@Table(
    name = "collection_run",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_collection_run_run_id", columnNames = "run_id"),
        @UniqueConstraint(name = "uk_collection_run_member_open", columnNames = {"member_id", "open_marker"})
    },
    indexes = @Index(name = "idx_collection_run_member_status", columnList = "member_id, status")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRun extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false, length = 40)
    private String runId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /**
     * 연구 회차의 연구 메타데이터 정본(LLD-0052 4절). {@code product} 회차와
     * 참여 기록 도입 전에 열린 회차는 비어 있다.
     *
     * <p>회차를 읽는 쪽(응답·내보내기·자료 귀속)이 저마다 트랜잭션 경계가 달라 즉시 로딩한다.
     * 회차는 건 단위로 조회하므로 목록 N+1 문제가 없다.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "study_participation_id")
    private StudyParticipation participation;

    @Column(name = "study_id", length = 64)
    private String studyId;

    @Column(name = "participation_id", length = 64)
    private String participationId;

    @Column(name = "protocol_ref", length = 64)
    private String protocolRef;

    /** 서면 연구 동의 판(질의 ① 경량 테이블 확정). */
    @Column(name = "consent_version", length = 50)
    private String consentVersion;

    @Column(name = "collection_mode", nullable = false, length = 10)
    private String collectionMode;

    @Column(name = "started_at_ms", nullable = false)
    private Long startedAtMs;

    @Column(name = "ended_at_ms")
    private Long endedAtMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CollectionRunStatus status;

    /** 열린 회차만 1이다. 회원별 열린 회차 하나를 DB 제약으로 보장한다. */
    @Column(name = "open_marker")
    private Integer openMarker;

    @Column(name = "data_policy", length = 20)
    private String dataPolicy;

    @Column(name = "identified_until")
    private LocalDate identifiedUntil;

    @Column(name = "pseudonymized_at")
    private LocalDate pseudonymizedAt;

    @Column(name = "research_until")
    private LocalDate researchUntil;

    @Column(name = "quality_notes", length = 500)
    private String qualityNotes;

    @Column(name = "missing_reason", length = 64)
    private String missingReason;

    /** 연구 회수 모드 회차의 수용 영수증 번호(K3). 아직 채우지 않는다. */
    @Column(name = "acceptance_receipt_id", length = 64)
    private String acceptanceReceiptId;

    @Builder
    private CollectionRun(
            String runId,
            Member member,
            StudyParticipation participation,
            String studyId,
            String participationId,
            String protocolRef,
            String consentVersion,
            String collectionMode,
            Long startedAtMs,
            CollectionRunStatus status,
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil,
            String qualityNotes,
            String missingReason
    ) {
        this.runId = runId;
        this.member = member;
        this.participation = participation;
        this.studyId = studyId;
        this.participationId = participationId;
        this.protocolRef = protocolRef;
        this.consentVersion = consentVersion;
        this.collectionMode = collectionMode;
        this.startedAtMs = startedAtMs;
        this.status = status;
        if (CollectionRunStatus.OPEN == status) {
            this.openMarker = 1;
        }
        this.dataPolicy = dataPolicy;
        this.identifiedUntil = identifiedUntil;
        this.pseudonymizedAt = pseudonymizedAt;
        this.researchUntil = researchUntil;
        this.qualityNotes = qualityNotes;
        this.missingReason = missingReason;
    }

    /*
     * 연구 메타데이터는 참여 기록이 정본이다(LLD-0052 4절). 참여 기록이 붙지 않은 회차
     * (product 회차, 참여 기록 도입 전 회차)만 회차에 남은 중복 컬럼에서 읽는다.
     * 중복 컬럼은 운영 백필 후 별도 승인으로 제거한다(LLD-0052 8절).
     */

    public String getStudyId() {
        if (participation == null) {
            return studyId;
        }
        return participation.getStudyId();
    }

    public String getParticipationId() {
        if (participation == null) {
            return participationId;
        }
        return participation.getParticipationId();
    }

    public String getConsentVersion() {
        if (participation == null) {
            return consentVersion;
        }
        return participation.getConsentVersion();
    }

    public String getDataPolicy() {
        if (participation == null) {
            return dataPolicy;
        }
        return participation.getDataPolicy();
    }

    public LocalDate getIdentifiedUntil() {
        if (participation == null) {
            return identifiedUntil;
        }
        return participation.getIdentifiedUntil();
    }

    public LocalDate getPseudonymizedAt() {
        if (participation == null) {
            return pseudonymizedAt;
        }
        return participation.getPseudonymizedAt();
    }

    public LocalDate getResearchUntil() {
        if (participation == null) {
            return researchUntil;
        }
        return participation.getResearchUntil();
    }

    public void close(Long endedAtMs, String qualityNotes, String missingReason) {
        this.endedAtMs = endedAtMs;
        this.qualityNotes = qualityNotes;
        this.missingReason = missingReason;
        this.status = CollectionRunStatus.CLOSED;
        this.openMarker = null;
    }

    public boolean isOpen() {
        return this.status == CollectionRunStatus.OPEN;
    }

    /** 마커가 놓일 수 있는 구간은 {@code [started, ended)}다. 열린 회차는 상한이 없다. */
    public boolean covers(long atMs) {
        if (atMs < this.startedAtMs) {
            return false;
        }
        if (this.endedAtMs == null) {
            return true;
        }
        return atMs < this.endedAtMs;
    }
}
