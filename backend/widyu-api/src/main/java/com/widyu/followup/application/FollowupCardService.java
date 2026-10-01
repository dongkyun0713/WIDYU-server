package com.widyu.followup.application;

import com.widyu.followup.FollowupAnswer;
import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupCardState;
import com.widyu.followup.FollowupQ3;
import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.dto.response.CurrentFollowupResponse;
import com.widyu.followup.dto.response.FollowupSubmissionResponse;
import com.widyu.followup.repository.FollowupAnswerRepository;
import com.widyu.followup.repository.FollowupCardRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FollowupCardService {
    private final FollowupCardRepository cardRepository;
    private final FollowupAnswerRepository answerRepository;
    private final MemberRepository memberRepository;
    private final SensorProperties sensorProperties;

    @Transactional
    public void issueIfEnabled(Incident incident) {
        if (!enabled()) {
            return;
        }
        if (cardRepository.existsByIncidentRef(incident.getIncidentRef())) {
            return;
        }
        String version = "HR_V1";
        if (incident.getKind() == IncidentKind.FALL_SUSPECTED) {
            version = "FALL_V1";
        }
        if (incident.getKind() == IncidentKind.SAFE_ZONE_EXIT) {
            version = "SAFE_ZONE_V1";
        }
        cardRepository.save(FollowupCard.issue(incident.getIncidentRef(), incident.getMemberId(),
                version, incident.getOpenedAtMs(), System.currentTimeMillis()));
    }

    @Transactional
    public CurrentFollowupResponse current(Long seniorId, String visitKey) {
        requireEnabled();
        requireSenior(seniorId, true);
        String normalizedVisitKey = normalizeVisitKey(visitKey);
        long nowMs = System.currentTimeMillis();
        FollowupCard assigned = cardRepository.findBySeniorIdAndVisitKey(seniorId, normalizedVisitKey).orElse(null);
        if (assigned != null) {
            if (assigned.getState() == FollowupCardState.ISSUED && assigned.getExpiresAtMs() > nowMs) {
                return CurrentFollowupResponse.of(assigned, nowMs);
            }
            return CurrentFollowupResponse.of(null, nowMs);
        }
        FollowupCard available = cardRepository
                .findFirstBySeniorIdAndStateAndVisitKeyIsNullAndExpiresAtMsGreaterThanOrderByIssuedAtMsAscIdAsc(
                        seniorId, FollowupCardState.ISSUED, nowMs).orElse(null);
        if (available == null) {
            return CurrentFollowupResponse.of(null, nowMs);
        }
        cardRepository.assignVisitIfIssued(available.getId(), seniorId, normalizedVisitKey,
                FollowupCardState.ISSUED, nowMs);
        FollowupCard refreshed = cardRepository.findBySeniorIdAndVisitKey(seniorId, normalizedVisitKey).orElse(null);
        if (refreshed != null && refreshed.getState() == FollowupCardState.ISSUED
                && refreshed.getExpiresAtMs() > nowMs) {
            return CurrentFollowupResponse.of(refreshed, nowMs);
        }
        return CurrentFollowupResponse.of(null, nowMs);
    }

    @Transactional
    public FollowupSubmissionResponse answer(Long seniorId, Long cardId, FollowupAnswerRequest request) {
        requireEnabled();
        requireSenior(seniorId, false);
        FollowupCard card = ownedCard(seniorId, cardId);
        requireNotSubmitted(card);
        if (request == null || request.q1() == null || request.q2() == null
                || request.deviceSubmittedAtMs() == null || request.deviceSubmittedAtMs() <= 0
                || !card.getQuestionSetVersion().equals(request.questionSetVersion())) {
            throw new BusinessException(ErrorCode.FOLLOWUP_REQUEST_INVALID);
        }
        String q3 = q3Value(request.q3());
        long receivedAtMs = System.currentTimeMillis();
        claim(card, seniorId, FollowupCardState.ANSWERED, receivedAtMs, request.deviceSubmittedAtMs());
        FollowupCard updated = ownedCard(seniorId, cardId);
        FollowupAnswer saved = answerRepository.save(FollowupAnswer.of(updated, request.q1(), request.q2(), q3,
                request.deviceSubmittedAtMs(), receivedAtMs));
        return FollowupSubmissionResponse.of(updated, saved);
    }

    @Transactional
    public FollowupSubmissionResponse decline(Long seniorId, Long cardId, Long deviceSubmittedAtMs) {
        requireEnabled();
        requireSenior(seniorId, false);
        FollowupCard card = ownedCard(seniorId, cardId);
        requireNotSubmitted(card);
        if (deviceSubmittedAtMs == null || deviceSubmittedAtMs <= 0) {
            throw new BusinessException(ErrorCode.FOLLOWUP_REQUEST_INVALID);
        }
        long receivedAtMs = System.currentTimeMillis();
        claim(card, seniorId, FollowupCardState.DECLINED, receivedAtMs, deviceSubmittedAtMs);
        FollowupCard updated = ownedCard(seniorId, cardId);
        FollowupAnswer saved = answerRepository.save(FollowupAnswer.of(updated, null, null, null,
                deviceSubmittedAtMs, receivedAtMs));
        return FollowupSubmissionResponse.of(updated, saved);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfDue(Long cardId, long nowMs) {
        if (!enabled()) {
            return;
        }
        cardRepository.expire(cardId, FollowupCardState.ISSUED, FollowupCardState.EXPIRED_NO_RESPONSE, nowMs);
    }

    public boolean enabled() {
        return sensorProperties.followup() != null && sensorProperties.followup().enabled();
    }

    private void claim(FollowupCard card, Long seniorId, FollowupCardState target,
            long receivedAtMs, long deviceSubmittedAtMs) {
        requireNotSubmitted(card);
        if (receivedAtMs >= card.getExpiresAtMs() && deviceSubmittedAtMs >= card.getExpiresAtMs()) {
            throw new BusinessException(ErrorCode.FOLLOWUP_EXPIRED);
        }
        int updated = cardRepository.submit(card.getId(), seniorId, FollowupCardState.ISSUED,
                FollowupCardState.EXPIRED_NO_RESPONSE, target, receivedAtMs, deviceSubmittedAtMs);
        if (updated == 0) {
            FollowupCard refreshed = ownedCard(seniorId, card.getId());
            requireNotSubmitted(refreshed);
            throw new BusinessException(ErrorCode.FOLLOWUP_EXPIRED);
        }
    }

    private void requireNotSubmitted(FollowupCard card) {
        if (card.getState() == FollowupCardState.ANSWERED || card.getState() == FollowupCardState.DECLINED) {
            throw new BusinessException(ErrorCode.FOLLOWUP_ALREADY_SUBMITTED);
        }
    }

    private String q3Value(List<FollowupQ3> q3) {
        if (q3 == null || q3.isEmpty()) {
            return null;
        }
        if (q3.size() > 2 || new HashSet<>(q3).size() != q3.size() || q3.stream().anyMatch(Objects::isNull)
                || (q3.contains(FollowupQ3.DONT_KNOW) && q3.size() > 1)) {
            throw new BusinessException(ErrorCode.FOLLOWUP_REQUEST_INVALID);
        }
        return String.join(",", q3.stream().map(Enum::name).toList());
    }

    private FollowupCard ownedCard(Long seniorId, Long cardId) {
        return cardRepository.findById(cardId)
                .filter(card -> card.getSeniorId().equals(seniorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWUP_NOT_FOUND));
    }

    private void requireSenior(Long seniorId, boolean forUpdate) {
        Member member;
        if (forUpdate) {
            member = memberRepository.findByIdForUpdate(seniorId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWUP_NOT_FOUND));
        } else {
            member = memberRepository.findById(seniorId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOWUP_NOT_FOUND));
        }
        if (member.getType() != MemberType.SENIOR) {
            throw new BusinessException(ErrorCode.FOLLOWUP_NOT_FOUND);
        }
    }

    private String normalizeVisitKey(String visitKey) {
        try {
            if (visitKey == null || visitKey.length() != 36) {
                throw new BusinessException(ErrorCode.FOLLOWUP_REQUEST_INVALID);
            }
            return UUID.fromString(visitKey).toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FOLLOWUP_REQUEST_INVALID);
        }
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new BusinessException(ErrorCode.FOLLOWUP_NOT_FOUND);
        }
    }
}
