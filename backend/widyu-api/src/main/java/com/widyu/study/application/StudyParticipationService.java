package com.widyu.study.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationHistory;
import com.widyu.study.StudyParticipationHistoryType;
import com.widyu.study.StudyParticipationStatus;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.request.StudyWithdrawalRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import com.widyu.study.repository.StudyParticipationHistoryRepository;
import com.widyu.study.repository.StudyParticipationRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 전용 실증 참여 기록 관리(LLD-0052 5절).
 * 등록·보관 계획 수정·철회·삭제 처리는 매번 같은 트랜잭션에서 불변 snapshot을 남기고,
 * 누가·언제 바꿨는지는 기존 관리자 감사 로그에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class StudyParticipationService {

    private static final String PARTICIPATION_ID_PREFIX = "part-";
    private static final String TARGET_TYPE = "StudyParticipation";

    private final StudyParticipationRepository studyParticipationRepository;
    private final StudyParticipationHistoryRepository studyParticipationHistoryRepository;
    private final MemberRepository memberRepository;
    private final AdminAuditLogService adminAuditLogService;

    @Transactional
    public StudyParticipationResponse register(StudyParticipationCreateRequest request) {
        if (studyParticipationRepository.existsByStudyIdAndMemberIdAndStatus(
                request.studyId(), request.memberId(), StudyParticipationStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        }
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        StudyParticipation participation = saveNew(StudyParticipation.of(
                request.studyId(),
                newParticipationId(),
                member,
                request.consentVersion(),
                request.consentedAt(),
                request.consents(),
                request.dataPolicy(),
                request.identifiedUntil(),
                request.pseudonymizedAt(),
                request.researchUntil()));
        return record(participation, StudyParticipationHistoryType.REGISTERED,
                AdminAction.STUDY_PARTICIPATION_REGISTER);
    }

    @Transactional(readOnly = true)
    public StudyParticipationResponse get(String participationId) {
        return StudyParticipationResponse.from(find(participationId));
    }

    @Transactional
    public StudyParticipationResponse changeRetention(
            String participationId, StudyRetentionChangeRequest request) {
        StudyParticipation participation = findForUpdate(participationId);
        participation.changeRetention(
                request.dataPolicy(),
                request.identifiedUntil(),
                request.pseudonymizedAt(),
                request.researchUntil());
        return record(participation, StudyParticipationHistoryType.RETENTION_CHANGED,
                AdminAction.STUDY_PARTICIPATION_PERIOD_CHANGE);
    }

    /** 철회는 상태·범위만 기록한다. 센서 원문·심박·위치 삭제는 이 범위 밖의 수동 절차다. */
    @Transactional
    public StudyParticipationResponse withdraw(String participationId, StudyWithdrawalRequest request) {
        StudyParticipation participation = findForUpdate(participationId);
        participation.withdraw(request.scope(), request.consentKeys(), LocalDateTime.now());
        return record(participation, StudyParticipationHistoryType.WITHDRAWN,
                AdminAction.STUDY_PARTICIPATION_WITHDRAW);
    }

    @Transactional
    public StudyParticipationResponse markDeletionProcessed(String participationId) {
        StudyParticipation participation = findForUpdate(participationId);
        participation.markDeletionProcessed(LocalDateTime.now());
        return record(participation, StudyParticipationHistoryType.DELETION_PROCESSED,
                AdminAction.STUDY_PARTICIPATION_DELETION_PROCESSED);
    }

    /**
     * 연구 회차 개설 게이트(LLD-0052 5절). 대상 회원의 ACTIVE 참여 기록만 통과시킨다.
     *
     * <p>회차를 저장하는 트랜잭션 안에서 참여 기록을 잠그고 읽는다. 검사와 저장이 다른
     * 트랜잭션이면 그 사이에 들어온 철회를 놓쳐 WITHDRAWN 참여로 연구 회차가 열린다.
     * 호출자 트랜잭션이 없으면 그 보장이 없으므로 {@code MANDATORY}로 막는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StudyParticipation requireActiveForRun(String participationId, Long memberId) {
        StudyParticipation participation = findForUpdate(participationId);
        if (!participation.getMember().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.RUN_RESEARCH_PARTICIPATION_MISMATCH);
        }
        if (!participation.isActive()) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE);
        }
        return participation;
    }

    /**
     * 무엇이 어떻게 바뀌었는지는 snapshot이, 누가 바꿨는지는 관리자 감사 로그가 답한다.
     * 감사 로그 detail에는 식별자만 남긴다 — 동의 항목·보관 날짜·이름은 남기지 않는다.
     */
    private StudyParticipationResponse record(
            StudyParticipation participation,
            StudyParticipationHistoryType historyType,
            AdminAction action) {
        studyParticipationHistoryRepository.save(
                StudyParticipationHistory.snapshotOf(participation, historyType));
        adminAuditLogService.logInCurrentTransaction(action, TARGET_TYPE, participation.getId(),
                "participationId=%s, memberId=%d, studyId=%s".formatted(
                        participation.getParticipationId(),
                        participation.getMember().getId(),
                        participation.getStudyId()));
        return StudyParticipationResponse.from(participation);
    }

    /**
     * 같은 연구·회원의 ACTIVE 참여가 하나뿐인지는 DB UK가 보장한다. 위의 조회 검사는 빠른 응답용이라
     * 동시 요청에서는 통과할 수 있고, 그때 UK에 걸린 쪽을 같은 409로 돌려준다.
     */
    private StudyParticipation saveNew(StudyParticipation participation) {
        try {
            // flush까지 해야 제약 위반이 여기서 잡힌다. 커밋까지 미루면 try 밖에서 터져 500이 된다.
            return studyParticipationRepository.saveAndFlush(participation);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        }
    }

    /** 읽기 전용 조회. 상태를 바꾸는 경로는 {@link #findForUpdate}를 쓴다. */
    private StudyParticipation find(String participationId) {
        return studyParticipationRepository.findByParticipationId(participationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_FOUND));
    }

    /**
     * 상태를 바꾸거나 상태를 보고 판단하는 경로는 모두 이 조회를 지난다. 잠그지 않고 읽으면
     * 회차 개설과 철회가 서로의 결정을 못 보고 지나가, 이미 철회된 참여로 회차가 열리거나
     * 철회가 두 번 기록된다. 잠근 뒤 엔티티가 상태를 다시 검사한다.
     */
    private StudyParticipation findForUpdate(String participationId) {
        return studyParticipationRepository.findByParticipationIdForUpdate(participationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_FOUND));
    }

    private String newParticipationId() {
        return PARTICIPATION_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
