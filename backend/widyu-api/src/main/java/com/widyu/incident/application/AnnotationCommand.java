package com.widyu.incident.application;

import com.widyu.incident.AnnotatorType;
import com.widyu.incident.EventOccurrence;
import com.widyu.incident.HelpNeed;

public record AnnotationCommand(String incidentRef, AnnotatorType annotatorType, Long reviewerId,
        String annotatorRef, String rubricVersion, EventOccurrence eventOccurrence, HelpNeed helpNeed,
        String note, String sourceEvidenceRef, Long supersedesAnnotationId) {
    public static AnnotationCommand of(String incidentRef, AnnotatorType annotatorType, Long reviewerId,
            String annotatorRef, String rubricVersion, EventOccurrence eventOccurrence, HelpNeed helpNeed,
            String note, String sourceEvidenceRef, Long supersedesAnnotationId) {
        return new AnnotationCommand(incidentRef, annotatorType, reviewerId, annotatorRef,
                rubricVersion, eventOccurrence, helpNeed, note, sourceEvidenceRef, supersedesAnnotationId);
    }
}
