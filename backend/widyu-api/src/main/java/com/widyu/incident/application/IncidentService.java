package com.widyu.incident.application;

import com.widyu.decision.DecisionRecord;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import com.widyu.incident.dto.request.IncidentRespondRequest;
import com.widyu.incident.dto.request.IncidentResolveRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.application.FamilyAccessService;
import java.util.List;
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

    private static final String SELF_CHECK_TITLE = "괜찮으세요?";
    private static final String SELF_CHECK_CONTENT = "지금 상태를 알려주세요. 답이 없으면 가족에게 알립니다.";
    private static final String SELF_CHECK_SCHEME_PREFIX = "widyu://incident/";
    private static final long MILLIS_PER_SECOND = 1000L;

    private final IncidentRepository incidentRepository;
    private final FcmService fcmService;
    private final FamilyAccessService familyAccessService;
    private final SensorProperties sensorProperties;

    /**
     * 위급 판정이 사건을 연다(LLD-0054 5.1).
     *
     * <p>판정 하나가 사건 하나다. 같은 판정으로 두 번 부르면 이미 연 사건을 그대로 돌려준다 —
     * 재시도가 사건을 늘리면 본인확인 푸시도 그만큼 간다.
     */
    @Transactional
    public Incident openForAlert(DecisionRecord decision, IncidentKind kind) {
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

    /** 본인 응답(LLD-0054 5.2). 다른 회원이 부르면 사건의 존재를 알리지 않고 404다. */
    @Transactional
    public IncidentResponse respond(Long memberId, String incidentRef, IncidentRespondRequest request) {
        Incident incident = findOwned(memberId, incidentRef);
        if (incident.isAnswered() || incident.getState() == IncidentState.RESOLVED) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_ANSWERED);
        }
        incident.respond(request.response(), request.via(), System.currentTimeMillis());
        log.info("인시던트 응답: memberId={}, incidentRef={}, state={}",
                memberId, incidentRef, incident.getState());
        return IncidentResponse.from(incident);
    }

    /** 보호자의 사후 판정(LLD-0054 5.4). 라벨을 덮어쓰지 않으려고 두 번째 판정은 막는다. */
    @Transactional
    public IncidentResponse resolve(Long guardianId, String incidentRef, IncidentResolveRequest request) {
        Incident incident = incidentRepository.findByIncidentRef(incidentRef)
                .orElseThrow(() -> new BusinessException(ErrorCode.INCIDENT_NOT_FOUND));
        familyAccessService.verifyFamilyAccess(guardianId, incident.getMemberId());
        if (incident.getState() == IncidentState.RESOLVED) {
            throw new BusinessException(ErrorCode.INCIDENT_ALREADY_RESOLVED);
        }
        incident.resolve(request.outcome(), guardianId, System.currentTimeMillis(), request.emergencyCalledAtMs());
        log.info("인시던트 사후 판정: guardianId={}, incidentRef={}", guardianId, incidentRef);
        return IncidentResponse.from(incident);
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
        return FcmSendDto.builder()
                .title(SELF_CHECK_TITLE)
                .content(SELF_CHECK_CONTENT)
                .fcmCategory(FcmCategory.INCIDENT_SELF_CHECK)
                .scheme(SELF_CHECK_SCHEME_PREFIX + incident.getIncidentRef())
                .emergency(true)
                .build();
    }
}
