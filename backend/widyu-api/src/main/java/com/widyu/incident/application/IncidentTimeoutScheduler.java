package com.widyu.incident.application;

import com.widyu.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응답 마감을 넘긴 사건을 서버가 무응답으로 판정한다(ADR-0035 결정 5, LLD-0054 5.3).
 *
 * <p>단말은 무응답을 보내지 않는다. 답을 못 하는 상황이 곧 무응답이라, 그 상황에서 단말이
 * 무언가 보내 주기를 기대할 수 없다. 그래서 마감은 서버가 잰다.
 *
 * <p>여기서 보호자에게 다시 알리지 않는다. 보호자 알림은 판정 시점에 이미 나갔다(결정 7).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentTimeoutScheduler {

    private final IncidentRepository incidentRepository;

    @Scheduled(fixedDelayString = "${sensor.incident.timeout-poll-ms}")
    @Transactional
    public void escalateTimedOut() {
        int escalated = incidentRepository.escalateTimedOut(System.currentTimeMillis());
        if (escalated == 0) {
            return;
        }
        log.info("인시던트 무응답 판정: count={}", escalated);
    }
}
