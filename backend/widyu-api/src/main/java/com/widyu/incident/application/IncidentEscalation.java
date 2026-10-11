package com.widyu.incident.application;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.global.entity.Status;
import com.widyu.global.properties.SensorProperties;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentState;
import com.widyu.incident.repository.IncidentGuardianResponseRepository;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.HashMap;
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
    private final IncidentGuardianResponseRepository guardianResponses;
    private final SensorProperties sensorProperties;
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

    /** 사건 행 잠금으로 보호자 멈춤과 ② 게이트의 커밋 순서를 정한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendSecondAlertIfDue(Long id) {
        Incident incident = incidentRepository.findByIdForUpdate(id).orElse(null);
        long nowMs = System.currentTimeMillis();
        if (incident == null || incident.getSecondAlertDueAtMs() == null
                || incident.getSecondAlertDueAtMs() > nowMs
                || incident.getSecondAlertSentAtMs() != null
                || incident.getSecondAlertCancelledAtMs() != null) {
            return false;
        }
        long windowMs = sensorProperties.incident().situationWindowMin() * 60_000L;
        if (incident.getKind() != IncidentKind.HR_ANOMALY
                || incident.getSituationEndedAtMs() != null
                || (incident.getState() != IncidentState.CHECKING
                    && incident.getState() != IncidentState.ESCALATED)
                || incident.lastDetectedAtOrOpenedAt() + windowMs <= nowMs
                || guardianResponses.existsByIncidentId(id)) {
            incidentRepository.cancelSecondAlertIfPending(id, nowMs);
            return false;
        }
        if (incidentRepository.claimSecondAlertIfDue(id, nowMs) == 0) {
            return false;
        }
        // 게이트와 같은 트랜잭션의 잠금 안에서 마지막으로 수신자와 멈춤을 확인한다.
        if (guardianResponses.existsByIncidentId(id)) {
            throw new IllegalStateException("② 게이트 이후 멈춤 기록이 바뀌었습니다.");
        }
        Member senior = memberRepository.findById(incident.getMemberId())
                .orElseThrow(() -> new IllegalStateException("사건 회원을 찾을 수 없습니다."));
        Long familyId = seniorProfileRepository.findFamilyIdByMemberId(incident.getMemberId()).orElse(null);
        if (familyId == null) {
            log.warn("보호자 2차 안전 알림 수신자 없음: incidentRef={}", incident.getIncidentRef());
            return true;
        }
        FcmSendDto notification = notificationFor(incident, senior, "SECOND_ALERT");
        int recipients = 0;
        for (FamilyMembership membership : familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId)) {
            if (membership.getGuardian().getStatus() != Status.ACTIVE) {
                continue;
            }
            outboxService.enqueue(membership.getGuardian().getId(), notification);
            recipients++;
        }
        if (recipients == 0) {
            log.warn("보호자 2차 안전 알림 수신자 없음: incidentRef={}", incident.getIncidentRef());
        }
        return true;
    }

    /** OFF 즉시 경로와 ON 스케줄러가 공유하는 심박 보호자 S04 생성 경로다. */
    void enqueueInitialAlert(Incident incident) {
        if (incident.getKind() != IncidentKind.HR_ANOMALY) {
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
        FcmSendDto notification = notificationFor(incident, senior, "INITIAL_ALERT");
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
    }

    private FcmSendDto notificationFor(Incident incident, Member senior, String stage) {
        NotificationType type = NotificationType.HEART_RATE_EMERGENCY;
        NotificationCopy copy = NotificationCopy.of(type, "S04", Map.of("시니어 이름", senior.getName()));
        Map<String, String> data = Map.of("deliveryStage", stage,
                "safetyEventId", incident.getIncidentRef());
        if ("SECOND_ALERT".equals(stage)) {
            data = Map.of("deliveryStage", stage, "safetyEventId", incident.getIncidentRef(),
                    "incidentRef", incident.getIncidentRef());
        }
        if (incident.getDecisionId() != null) {
            HashMap<String, String> values = new HashMap<>(data);
            values.put("decisionId", incident.getDecisionId());
            data = Map.copyOf(values);
        }
        return FcmSendDto.builder()
                .title(copy.title()).content(copy.body()).notificationType(type)
                .eventId(incident.getIncidentRef()).seniorId(incident.getMemberId())
                .entityId(incident.getIncidentRef()).relatedMemberId(incident.getMemberId())
                .image(senior.getProfileImage()).decisionId(incident.getDecisionId())
                .data(data).emergency(true).build();
    }
}
