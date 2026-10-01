package com.widyu.incident;

import com.widyu.global.entity.BaseTimeEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "label_annotation", uniqueConstraints =
        @UniqueConstraint(name = "uk_label_annotation_revision", columnNames = {"label_id", "revision"}),
        indexes = @Index(name = "idx_label_annotation_reviewer", columnList = "reviewer_id,created_at"))
public class LabelAnnotation extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "label_id", nullable = false)
    private IncidentLabel label;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "annotator_type", nullable = false, length = 24)
    private AnnotatorType annotatorType;

    @Column(name = "rubric_version", nullable = false, length = 64)
    private String rubricVersion;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "annotator_ref", length = 128)
    private String annotatorRef;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_occurrence", nullable = false, length = 24)
    private EventOccurrence eventOccurrence;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "help_need", nullable = false, length = 24)
    private HelpNeed helpNeed;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "source_evidence_ref", length = 128)
    private String sourceEvidenceRef;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "supersedes_annotation_id")
    private Long supersedesAnnotationId;

    public static LabelAnnotation of(IncidentLabel label, AnnotatorType type, Long reviewerId,
            String annotatorRef, String rubricVersion, EventOccurrence eventOccurrence, HelpNeed helpNeed,
            String note, String sourceEvidenceRef, int revision, Long supersedesAnnotationId) {
        LabelAnnotation annotation = new LabelAnnotation();
        annotation.label = label;
        annotation.annotatorType = type;
        annotation.reviewerId = reviewerId;
        annotation.annotatorRef = annotatorRef;
        annotation.rubricVersion = rubricVersion;
        annotation.eventOccurrence = eventOccurrence;
        annotation.helpNeed = helpNeed;
        annotation.note = note;
        annotation.sourceEvidenceRef = sourceEvidenceRef;
        annotation.revision = revision;
        annotation.supersedesAnnotationId = supersedesAnnotationId;
        return annotation;
    }
}
