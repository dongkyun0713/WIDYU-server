package com.widyu.location.realtime.application;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SafeZoneAlertService {

    private final IncidentRepository incidentRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void handleSafeZoneTransition(Long memberId, String previousLocationType, String currentLocationType) {
        if (currentLocationType != null) {
            if (previousLocationType == null) {
                memberRepository.findByIdForUpdate(memberId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
                incidentRepository.endOpenSafeZoneSituation(memberId, System.currentTimeMillis());
            }
            return;
        }

        if (previousLocationType == null) {
            return;
        }

        eventPublisher.publishEvent(new SafeZoneExitEvent(memberId));
        log.info("안전구역 이탈 이벤트 발행 - memberId: {}", memberId);
    }
}
