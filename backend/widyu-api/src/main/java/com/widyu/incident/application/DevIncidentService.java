package com.widyu.incident.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import com.widyu.incident.dto.request.DevHeartEmergencyRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프론트 테스트용 심박 위급 강제 발생(개발·로컬 전용). 사건·알림은 실제 단건 심박 위급 경로를 그대로 쓴다.
 */
@Slf4j
@Service
@Profile({"local", "dev"})
@RequiredArgsConstructor
public class DevIncidentService {

    private static final List<IncidentState> OPEN_STATES = List.of(IncidentState.CHECKING, IncidentState.ESCALATED);

    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final MemberRepository memberRepository;
    private final FamilyAccessService familyAccessService;

    @Transactional
    public IncidentResponse triggerHeartEmergency(Long callerId, DevHeartEmergencyRequest request) {
        Long seniorId = resolveSenior(callerId, request);
        // 실제 경로는 5분 안 재감지를 같은 사건에 붙여 새 푸시가 없다. 테스트마다 새 사건이 열리도록 먼저 끝낸다.
        incidentRepository
                .findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                        seniorId, IncidentKind.HR_ANOMALY, OPEN_STATES)
                .ifPresent(open -> open.endSituation(System.currentTimeMillis()));
        incidentRepository.flush();
        Incident opened = incidentService.openForAlert(seniorId, IncidentKind.HR_ANOMALY);
        log.info("개발용 심박 위급 발생: callerId={}, seniorId={}, incidentRef={}",
                callerId, seniorId, opened.getIncidentRef());
        return IncidentResponse.from(opened);
    }

    private Long resolveSenior(Long callerId, DevHeartEmergencyRequest request) {
        Member caller = memberRepository.findById(callerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (caller.getType() == MemberType.SENIOR) {
            return callerId;
        }
        if (request == null || request.seniorId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "보호자는 seniorId를 지정해야 합니다.");
        }
        familyAccessService.verifyActiveFamilyAccess(callerId, request.seniorId());
        return request.seniorId();
    }
}
