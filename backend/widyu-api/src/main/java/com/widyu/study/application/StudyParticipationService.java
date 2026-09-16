package com.widyu.study.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import com.widyu.study.repository.StudyParticipationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 국내 실증(IRB) 연구 참여 등록·조회·보존 기간 변경(LLD-0031). */
@Service
@RequiredArgsConstructor
public class StudyParticipationService {

    private static final String TARGET_TYPE = "STUDY_PARTICIPATION";

    private final StudyParticipationRepository studyParticipationRepository;
    private final MemberRepository memberRepository;
    private final AdminAuditLogService adminAuditLogService;

    @Transactional
    public StudyParticipationResponse register(StudyParticipationCreateRequest request) {
        if (studyParticipationRepository.existsByParticipationId(request.participationId())) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        }
        if (studyParticipationRepository.existsByMemberIdAndStatus(
                request.memberId(), StudyParticipationStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        }
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        StudyParticipation participation = studyParticipationRepository.save(StudyParticipation.of(
                request.studyId(),
                request.participationId(),
                member,
                request.dataPolicy(),
                request.identifiedUntil(),
                request.pseudonymizedAt(),
                request.researchUntil(),
                request.consentVersion()
        ));
        return StudyParticipationResponse.from(participation);
    }

    @Transactional(readOnly = true)
    public StudyParticipationResponse get(String participationId) {
        return StudyParticipationResponse.from(find(participationId));
    }

    /** 기간 변경은 IRB 승인 참조·동의 버전과 함께 감사 로그에 남긴다(정책서 1.5.3). */
    @Transactional
    public StudyParticipationResponse changeRetention(String participationId, StudyRetentionChangeRequest request) {
        StudyParticipation participation = find(participationId);
        String before = retentionSummary(participation);

        participation.changeRetention(
                request.identifiedUntil(),
                request.pseudonymizedAt(),
                request.researchUntil(),
                request.consentVersion()
        );

        String detail = String.format("irb=%s; before[%s]; after[%s]",
                request.irbApprovalRef(), before, retentionSummary(participation));
        adminAuditLogService.log(
                AdminAction.STUDY_PARTICIPATION_PERIOD_CHANGE, TARGET_TYPE, participation.getId(), detail);
        return StudyParticipationResponse.from(participation);
    }

    private StudyParticipation find(String participationId) {
        return studyParticipationRepository.findByParticipationId(participationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_FOUND));
    }

    private String retentionSummary(StudyParticipation p) {
        return String.format("identifiedUntil=%s, pseudonymizedAt=%s, researchUntil=%s, consentVersion=%s",
                p.getIdentifiedUntil(), p.getPseudonymizedAt(), p.getResearchUntil(), p.getConsentVersion());
    }
}
