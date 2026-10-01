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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "incident_label", uniqueConstraints =
        @UniqueConstraint(name = "uk_incident_label_ref", columnNames = "incident_ref"))
public class IncidentLabel extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_ref", nullable = false, length = 40)
    private String incidentRef;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_occurrence", nullable = false, length = 24)
    private EventOccurrence eventOccurrence;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "help_need", nullable = false, length = 24)
    private HelpNeed helpNeed;

    @Column(name = "labeled_at")
    private Long labeledAt;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 24)
    private IncidentLabelSource source;

    @Column(name = "current_annotation_id")
    private Long currentAnnotationId;

    public static IncidentLabel unreviewed(String incidentRef) {
        IncidentLabel label = new IncidentLabel();
        label.incidentRef = incidentRef;
        label.eventOccurrence = EventOccurrence.NOT_ASSESSED;
        label.helpNeed = HelpNeed.NOT_ASSESSED;
        label.source = IncidentLabelSource.UNREVIEWED;
        return label;
    }

    public void select(LabelAnnotation annotation, long nowMs) {
        this.eventOccurrence = annotation.getEventOccurrence();
        this.helpNeed = annotation.getHelpNeed();
        this.source = IncidentLabelSource.valueOf(annotation.getAnnotatorType().name());
        this.labeledAt = nowMs;
        this.currentAnnotationId = annotation.getId();
    }
}
