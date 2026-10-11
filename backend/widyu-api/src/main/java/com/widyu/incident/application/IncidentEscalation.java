package com.widyu.incident.application;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.global.entity.Status;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 사건별 조건부 갱신과 INITIAL_ALERT enqueue를 원자적으로 수행한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncidentEscalation {

    private final IncidentRepository incidentRepository;
    private final FcmOutboxService outboxService;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;

    /** 플래그 OFF에서 사건을 연 트랜잭션에 합류해 최초 알림을 즉시 예약한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sendImmediately(Incident incident, long nowMs) {
        if (incident.getInitialAlertSentAtMs() != null) {
            return;
        }
        enqueueInitialAlert(incident);
        incident.markInitialAlertSent(nowMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalateIfDue(Long id, long nowMs) {
        // 플래그 OFF에서 이미 알림을 보냈어도 무응답 상태 전환은 계속 필요하다.
        incidentRepository.escalateTimedOutIfDue(id, nowMs);
        if (incidentRepository.claimInitialAlertIfDue(id, nowMs) == 0) {
            return false;
        }
        Incident incident = incidentRepository.findById(id).orElseThrow();
        enqueueInitialAlert(incident);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int escalateFallTimedOut(long nowMs) {
        return incidentRepository.escalateFallTimedOut(nowMs);
    }

    /** OFF 즉시 경로와 ON 스케줄러가 공유하는 유일한 보호자 S04/S05 생성 경로다. */
    void enqueueInitialAlert(Incident incident) {
        NotificationType type;
        String copyCode;
        if (incident.getKind() == IncidentKind.HR_ANOMALY) {
            type = NotificationType.HEART_RATE_EMERGENCY;
            copyCode = "S04";
        } else if (incident.getKind() == IncidentKind.SAFE_ZONE_EXIT) {
            type = NotificationType.SAFE_ZONE_EXITED;
            copyCode = "S05";
        } else {
            throw new IllegalArgumentException("최초 보호자 알림을 지원하지 않는 사건 종류입니다.");
        }
        Member senior = memberRepository.findById(incident.getMemberId())
                .orElseThrow(() -> new IllegalStateException("사건 회원을 찾을 수 없습니다."));
        Long familyId = seniorProfileRepository.findFamilyIdByMemberId(incident.getMemberId()).orElse(null);
        if (familyId == null) {
            log.warn("보호자 최초 안전 알림 수신자 없음: incidentRef={}", incident.getIncidentRef());
            return;
        }
        List<FamilyMembership> memberships = familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId);
        NotificationCopy copy = NotificationCopy.of(type, copyCode, Map.of("시니어 이름", senior.getName()));
        Map<String, String> data = Map.of();
        if (incident.getDecisionId() != null) {
            data = Map.of("decisionId", incident.getDecisionId());
        }
        if (incident.getKind() == IncidentKind.HR_ANOMALY) {
            data = Map.of("deliveryStage", "INITIAL_ALERT",
                    "safetyEventId", incident.getIncidentRef());
            if (incident.getDecisionId() != null) {
                data = Map.of("deliveryStage", "INITIAL_ALERT",
                        "safetyEventId", incident.getIncidentRef(),
                        "decisionId", incident.getDecisionId());
            }
        }
        FcmSendDto notification = FcmSendDto.builder()
                .title(copy.title()).content(copy.body())
                .notificationType(type)
                .eventId(incident.getIncidentRef())
                .seniorId(incident.getMemberId())
                .entityId(incident.getIncidentRef())
                .relatedMemberId(incident.getMemberId())
                .image(senior.getProfileImage())
                .decisionId(incident.getDecisionId())
                .data(data)
                .emergency(true)
                .build();
        if (incident.getKind() == IncidentKind.HR_ANOMALY) {
            FamilyMembership leader = null;
            int activeLeaders = 0;
            for (FamilyMembership membership : memberships) {
                if (!membership.isLeader() || membership.getGuardian().getStatus() != Status.ACTIVE) {
                    continue;
                }
                activeLeaders++;
                if (leader == null || membership.getId() < leader.getId()) {
                    leader = membership;
                }
            }
            if (leader == null) {
                log.warn("보호자 최초 안전 알림 수신자 없음: incidentRef={}", incident.getIncidentRef());
                return;
            }
            if (activeLeaders > 1) {
                log.warn("보호자 최초 안전 알림 복수 방장: incidentRef={}, familyId={}",
                        incident.getIncidentRef(), familyId);
            }
            outboxService.enqueue(leader.getGuardian().getId(), notification);
            log.info("보호자 최초 안전 알림 enqueue: incidentRef={}, recipients=1", incident.getIncidentRef());
            return;
        }
        int recipients = 0;
        for (FamilyMembership membership : memberships) {
            if (membership.getGuardian().getStatus() != Status.ACTIVE) {
                continue;
            }
            outboxService.enqueue(membership.getGuardian().getId(), notification);
            recipients++;
        }
        if (recipients == 0) {
            log.warn("보호자 최초 안전 알림 수신자 없음: incidentRef={}", incident.getIncidentRef());
            return;
        }
        log.info("보호자 최초 안전 알림 enqueue: incidentRef={}, recipients={}",
                incident.getIncidentRef(), recipients);
    }
}
