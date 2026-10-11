package com.widyu.incident.application;

import com.widyu.decision.DecisionRecord;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.followup.application.FollowupCardService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.entity.Status;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.GuardianResponseType;
import com.widyu.incident.dto.response.GuardianResponseResult;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위급 판정 뒤의 본인확인·사후 판정을 사건 하나로 남긴다(LLD-0054 5절).
 *
 * <p>로그에는 식별자와 건수만 남긴다. 심박 값·판정 사유·좌표는 판정 기록의 몫이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private static final String SELF_CHECK_SCHEME_PREFIX = "widyu://incident/";
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final IncidentRepository incidentRepository;
    private final FcmService fcmService;
    private final FamilyAccessService familyAccessService;
    private final SensorProperties sensorProperties;
    private final MemberRepository memberRepository;
    private final IncidentEscalation incidentEscalation;
    private final FcmOutboxService outboxService;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final FollowupCardService followupCardService;

    /** 배치 판정을 같은 심박 상황의 사건에 붙인다. 낙상은 기존 경로를 유지한다. */
    @Transactional
    public Incident openForAlert(DecisionRecord decision, IncidentKind kind) {
        if (kind == IncidentKind.HR_ANOMALY) {
            return attachOrOpen(decision.getMemberId(), kind, decision);
        }
        if (kind != IncidentKind.FALL_SUSPECTED) {
            throw new IllegalArgumentException("판정 사건은 심박 위급과 낙상만 지원합니다.");
        }
        Optional<Incident> opened = incidentRepository.findByDecisionId(decision.getDecisionId());
        if (opened.isPresent()) {
            return opened.get();
        }
        long openedAtMs = System.currentTimeMillis();
        Incident incident = incidentRepository.save(Incident.builder()
                .incidentRef("inc-" + UUID.randomUUID().toString().replace("-", ""))
                .memberId(decision.getMemberId())
                .runId(decision.getRunId())
                .decisionId(decision.getDecisionId())
                .kind(kind)
                .level(decision.getSeverity())
                .openedAtMs(openedAtMs)
                .respondByMs(openedAtMs + sensorProperties.incident().selfCheckSec() * MILLIS_PER_SECOND)
                .build());

        fcmService.sendMessageToUser(incident.getMemberId(), selfCheckMessage(incident));
        incident.markChecking();

        log.info("인시던트 열기: memberId={}, incidentRef={}, decisionId={}",
                incident.getMemberId(), incident.getIncidentRef(), incident.getDecisionId());
        return incident;
    }

    /** 판정 행이 없는 단건 심박 위급. 회원 행 잠금으로 동시 사건 생성을 직렬화한다. */
    @Transactional
    public Incident openForAlert(Long memberId, IncidentKind kind) {
        if (kind != IncidentKind.HR_ANOMALY) {
            throw new IllegalArgumentException("단건 사건은 심박 위급만 지원합니다.");
        }
        return attachOrOpen(memberId, kind, null);
    }

    private Incident attachOrOpen(Long memberId, IncidentKind kind, DecisionRecord decision) {
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        String decisionId = null;
        if (decision != null) {
            decisionId = decision.getDecisionId();
            Optional<Incident> repeated = incidentRepository.findByDecisionId(decisionId);
            if (repeated.isPresent()) {
                return repeated.get();
            }
        }
        long openedAtMs = System.currentTimeMillis();
        List<IncidentState> openStates = List.of(IncidentState.CHECKING, IncidentState.ESCALATED);
        Optional<Incident> opened = incidentRepository
                .findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                        memberId, kind, openStates);
        if (decisionId != null) {
            Optional<Incident> repeatCandidate = opened;
            if (repeatCandidate.isEmpty()) {
                repeatCandidate = incidentRepository.findFirstByMemberIdAndKindOrderByOpenedAtMsDesc(memberId, kind);
            }
            if (repeatCandidate.isPresent()) {
                Incident recent = repeatCandidate.get();
                if (decisionId.equals(recent.getDecisionId()) || decisionId.equals(recent.getLastDecisionId())) {
                    return recent;
                }
            }
        }
        if (opened.isPresent()) {
            Incident current = opened.get();
            long windowMs = sensorProperties.incident().situationWindowMin() * MILLIS_PER_MINUTE;
            long lastDetectedAtMs = current.lastDetectedAtOrOpenedAt();
            if (openedAtMs - lastDetectedAtMs < windowMs) {
                if (incidentRepository.attachDetection(current.getId(), decisionId, openedAtMs) == 1) {
                    return incidentRepository.findById(current.getId()).orElseThrow();
                }
                Incident refreshed = incidentRepository.findById(current.getId()).orElseThrow();
                if (refreshed.getSituationEndedAtMs() == null
                        && (refreshed.getState() == IncidentState.CHECKING
                        || refreshed.getState() == IncidentState.ESCALATED)) {
                    return refreshed;
                }
            } else {
                current.endSituation(lastDetectedAtMs + windowMs);
            }
        }
        Incident.IncidentBuilder builder = Incident.builder()
                .incidentRef("inc-" + UUID.randomUUID().toString().replace("-", ""))
                .memberId(memberId)
                .kind(kind)
                .openedAtMs(openedAtMs)
                .respondByMs(openedAtMs + sensorProperties.incident().selfCheckSec() * MILLIS_PER_SECOND);
        if (decision != null) {
            builder.decisionId(decision.getDecisionId()).runId(decision.getRunId()).level(decision.getSeverity());
        }
        Incident incident = incidentRepository.save(builder.build());
        fcmService.sendMessageToUser(memberId, selfCheckMessage(incident));
        incident.markChecking();
        if (!sensorProperties.incident().selfCheckFirst()) {
            incidentEscalation.sendImmediately(incident, System.currentTimeMillis());
        }
        return incident;
    }

    /**
     * 본인 응답(LLD-0054 5.2). 다른 회원이 부르면 사건의 존재를 알리지 않고 404다.
     *
     * <p>저장은 조건부 UPDATE 한 문장이 한다. 읽고 고쳐 저장하면 그 사이에 무응답 스케줄러가 올린
     * 상태를 덮고, 마감을 넘긴 응답을 정상 종료로 적을 수 있다. 갱신이 0건이면 이미 답했거나
     * 종결된 사건이다.
     */
    @Transactional
    public IncidentResponse respond(Long memberId, String incidentRef, IncidentRespondRequest request) {
        findOwned(memberId, incidentRef);
        long respondedAtMs = System.currentTimeMillis();
        int updated = incidentRepository.respond(incidentRef, memberId, request.response(), request.via(),
                respondedAtMs, request.deviceRespondedAtMs(), answeredState(request.response()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_ANSWERED);
        }
        Incident answered = findOwned(memberId, incidentRef);
        if (request.response() == IncidentResponseValue.OK && answered.getState() == IncidentState.OK_CLOSED) {
            followupCardService.issueIfEnabled(answered);
            if (answered.getKind() == IncidentKind.HR_ANOMALY) {
                answered.endSituation(respondedAtMs);
            }
            if (answered.getKind() == IncidentKind.HR_ANOMALY) {
                enqueueOkNotice(answered);
                answered.markOkNoticeSent(respondedAtMs);
            }
        }
        log.info("인시던트 응답: memberId={}, incidentRef={}, state={}",
                memberId, incidentRef, answered.getState());
        return IncidentResponse.from(answered);
    }

    /** 마감 안에 답했을 때의 상태. 마감을 넘겼는지는 행을 보는 UPDATE가 정한다. */
    private IncidentState answeredState(IncidentResponseValue response) {
        if (response == IncidentResponseValue.HELP) {
            return IncidentState.ESCALATED;
        }
        return IncidentState.OK_CLOSED;
    }

    /**
     * 보호자의 사후 판정(LLD-0054 5.4). 라벨을 덮어쓰지 않으려고 두 번째 판정은 막는다.
     *
     * <p>사건을 읽는 것은 <b>가족 접근을 확인하려고 시니어가 누구인지 알기 위해서</b>다. 종결
     * 여부는 읽은 값으로 판단하지 않고 UPDATE 조건이 정한다. 읽고 고쳐 저장하면 보호자 둘이 같은
     * 순간에 판정할 때 나중 요청이 앞선 라벨을 덮는다.
     */
    @Transactional
    public IncidentResponse resolve(Long guardianId, String incidentRef, IncidentResolveRequest request) {
        Incident incident = incidentRepository.findByIncidentRef(incidentRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        familyAccessService.verifyFamilyAccess(guardianId, incident.getMemberId());

        int updated = incidentRepository.resolve(incidentRef, request.outcome(), guardianId,
                System.currentTimeMillis(), request.emergencyCalledAtMs());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_RESOLVED);
        }
        Incident resolved = incidentRepository.findByIncidentRef(incidentRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        if (resolved.getKind() == IncidentKind.HR_ANOMALY) {
            resolved.endSituation(resolved.getResolvedAtMs());
        }
        log.info("인시던트 사후 판정: guardianId={}, incidentRef={}", guardianId, incidentRef);
        return IncidentResponse.from(resolved);
    }

    @Transactional
    public GuardianResponseResult recordGuardianResponse(Long guardianId, String incidentRef,
            GuardianResponseType type) {
        Incident incident = incidentRepository.findByIncidentRef(incidentRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        Member guardian = memberRepository.findById(guardianId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        if (guardian.getType() != MemberType.GUARDIAN) {
            throw new BusinessException(ErrorCode.INCIDENT_NOT_FOUND);
        }
        Long familyId = seniorProfileRepository.findFamilyIdByMemberId(incident.getMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        if (!familyMembershipRepository.existsByFamilyIdAndGuardianId(familyId, guardianId)) {
            throw new BusinessException(ErrorCode.INCIDENT_NOT_FOUND);
        }
        if (guardian.getStatus() != Status.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        int updated = incidentRepository.recordGuardianResponse(incidentRef, type,
                System.currentTimeMillis(), guardianId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INCIDENT_GUARDIAN_RESPONSE_ALREADY_RECORDED);
        }
        // W12: 미실행 자동전화·FINAL_ESCALATION 예약 취소.
        Incident recorded = incidentRepository.findByIncidentRef(incidentRef).orElseThrow();
        return GuardianResponseResult.from(recorded);
    }

    private void enqueueOkNotice(Incident incident) {
        NotificationType type = NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART;
        Member senior = memberRepository.findById(incident.getMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        Long familyId = seniorProfileRepository.findFamilyIdByMemberId(senior.getId()).orElse(null);
        if (familyId == null) {
            log.warn("OK 안내 수신자 없음: incidentRef={}", incident.getIncidentRef());
            return;
        }
        NotificationCopy copy = NotificationCopy.of(type, "S08", Map.of("시니어 이름", senior.getName()));
        FcmSendDto message = FcmSendDto.builder()
                .title(copy.title()).content(copy.body()).notificationType(type)
                .eventId(incident.getIncidentRef() + ":OK")
                .entityId(incident.getIncidentRef()).seniorId(senior.getId())
                .relatedMemberId(senior.getId()).image(senior.getProfileImage())
                .emergency(false).build();
        int recipients = 0;
        for (FamilyMembership membership : familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId)) {
            if (membership.getGuardian().getStatus() != Status.ACTIVE) {
                continue;
            }
            outboxService.enqueue(membership.getGuardian().getId(), message);
            recipients++;
        }
        if (recipients == 0) {
            log.warn("OK 안내 수신자 없음: incidentRef={}", incident.getIncidentRef());
        }
    }

    /** 가족 접근 검증은 컨트롤러의 {@code @ValidateFamilyAccess}가 먼저 한다(ADR-0002). */
    @Transactional(readOnly = true)
    public List<IncidentResponse> findForSenior(Long seniorId, String state) {
        if (state == null || state.isBlank()) {
            return toResponses(incidentRepository.findTop50ByMemberIdOrderByOpenedAtMsDesc(seniorId));
        }
        return toResponses(incidentRepository.findTop50ByMemberIdAndStateOrderByOpenedAtMsDesc(
                seniorId, toState(state)));
    }

    /** 시니어 본인이 아직 답하지 않은 사건. 앱이 확인 화면을 띄울 목록이다. */
    @Transactional(readOnly = true)
    public List<IncidentResponse> findPending(Long memberId) {
        return toResponses(incidentRepository.findTop50ByMemberIdAndResponseIsNullOrderByOpenedAtMsDesc(memberId));
    }

    private Incident findOwned(Long memberId, String incidentRef) {
        return incidentRepository.findByIncidentRef(incidentRef)
                .filter(incident -> incident.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
    }

    private IncidentState toState(String state) {
        try {
            return IncidentState.valueOf(state);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INCIDENT_REQUEST_INVALID);
        }
    }

    private List<IncidentResponse> toResponses(List<Incident> incidents) {
        return incidents.stream().map(IncidentResponse::from).toList();
    }

    /** 본인확인 푸시. 건강값을 담지 않고 사건 식별자만 들려 보낸다. */
    private FcmSendDto selfCheckMessage(Incident incident) {
        if (incident.getKind() == IncidentKind.FALL_SUSPECTED) {
            return FcmSendDto.builder()
                    .title("괜찮으세요?")
                    .content("지금 상태를 알려주세요. 답이 없으면 가족에게 알립니다.")
                    .fcmCategory(FcmCategory.INCIDENT_SELF_CHECK)
                    .scheme(SELF_CHECK_SCHEME_PREFIX + incident.getIncidentRef())
                    .emergency(true)
                    .build();
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.SAFETY_SELF_CHECK, "S01", null);
        return FcmSendDto.builder()
                .title(copy.title())
                .content(copy.body())
                .fcmCategory(FcmCategory.INCIDENT_SELF_CHECK)
                .scheme(SELF_CHECK_SCHEME_PREFIX + incident.getIncidentRef())
                .notificationType(NotificationType.SAFETY_SELF_CHECK)
                .eventId(incident.getIncidentRef())
                .entityId(incident.getIncidentRef())
                .emergency(true)
                .build();
    }
}
