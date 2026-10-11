package com.widyu.incident.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.entity.Status;
import com.widyu.global.util.SecurityUtil;
import com.widyu.incident.AnnotatorType;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentLabel;
import com.widyu.incident.LabelAnnotation;
import com.widyu.incident.repository.IncidentLabelRepository;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.incident.repository.LabelAnnotationRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내부 판독 저장 경계. 관리자 API와 실제 LLM 호출은 별도 작업이다. */
@Service
@RequiredArgsConstructor
public class IncidentLabelService {
    private final IncidentRepository incidentRepository;
    private final IncidentLabelRepository labelRepository;
    private final LabelAnnotationRepository annotationRepository;
    private final MemberRepository memberRepository;
    private final SecurityUtil securityUtil;

    @Transactional
    public LabelAnnotation recordAnnotation(AnnotationCommand command) {
        validate(command);
        Incident incident = incidentRepository.findByIncidentRef(command.incidentRef())
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        memberRepository.findByIdForUpdate(incident.getMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        IncidentLabel label = labelRepository.findByIncidentRefForUpdate(command.incidentRef())
                .orElseGet(() -> labelRepository.saveAndFlush(IncidentLabel.unreviewed(command.incidentRef())));
        int revision = annotationRepository.findFirstByLabelIdOrderByRevisionDesc(label.getId())
                .map(previous -> previous.getRevision() + 1).orElse(1);
        if (command.supersedesAnnotationId() != null) {
            LabelAnnotation previous = annotationRepository.findById(command.supersedesAnnotationId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID));
            if (!previous.getLabel().getId().equals(label.getId())) {
                throw new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID);
            }
        }
        return annotationRepository.save(LabelAnnotation.of(label, command.annotatorType(), command.reviewerId(),
                command.annotatorRef(), command.rubricVersion(), command.eventOccurrence(), command.helpNeed(),
                command.note(), command.sourceEvidenceRef(), revision, command.supersedesAnnotationId()));
    }

    @Transactional
    public IncidentLabel setCurrentLabel(String incidentRef, Long annotationId, Long expectedCurrentAnnotationId) {
        IncidentLabel label = labelRepository.findByIncidentRefForUpdate(incidentRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        if (!Objects.equals(label.getCurrentAnnotationId(), expectedCurrentAnnotationId)) {
            throw new BusinessException(ErrorCode.INCIDENT_LABEL_CONFLICT);
        }
        LabelAnnotation annotation = annotationRepository.findById(annotationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID));
        if (!annotation.getLabel().getId().equals(label.getId())) {
            throw new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID);
        }
        label.select(annotation, System.currentTimeMillis());
        return label;
    }

    private void validate(AnnotationCommand command) {
        if (command == null || command.incidentRef() == null || command.incidentRef().isBlank()
                || command.annotatorType() == null || command.eventOccurrence() == null
                || command.helpNeed() == null || command.rubricVersion() == null
                || command.rubricVersion().isBlank()) {
            throw new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID);
        }
        if (command.annotatorType() == AnnotatorType.HUMAN) {
            if (command.reviewerId() == null || command.annotatorRef() != null) {
                throw new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID);
            }
            if (!command.reviewerId().equals(securityUtil.getCurrentMemberId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            Member reviewer = memberRepository.findById(command.reviewerId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
            if (reviewer.getRole() != MemberRole.ADMIN || reviewer.getStatus() != Status.ACTIVE) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }
        if (command.annotatorType() == AnnotatorType.LLM_JUDGE) {
            if (command.reviewerId() != null || command.annotatorRef() == null
                    || command.annotatorRef().isBlank()) {
                throw new BusinessException(ErrorCode.INCIDENT_LABEL_INVALID);
            }
        }
    }
}
