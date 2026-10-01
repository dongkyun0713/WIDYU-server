package com.widyu.incident.application;

import com.widyu.incident.repository.IncidentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 대상 조회와 사건별 새 트랜잭션을 분리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentTimeoutScheduler {

    private static final int POLL_LIMIT = 100;
    private static final int MAX_PAGES = 10;
    private final IncidentRepository incidentRepository;
    private final IncidentEscalation incidentEscalation;

    @Scheduled(fixedDelayString = "${sensor.incident.timeout-poll-ms}")
    public void escalateTimedOut() {
        long nowMs = System.currentTimeMillis();
        long afterId = 0L;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Long> ids = incidentRepository.findDueIds(nowMs, afterId, PageRequest.of(0, POLL_LIMIT));
            if (ids.isEmpty()) {
                break;
            }
            afterId = ids.getLast();
            for (Long id : ids) {
                try {
                    incidentEscalation.escalateIfDue(id, nowMs);
                } catch (RuntimeException e) {
                    log.warn("사건 최초 알림 처리 실패: incidentId={}, errorType={}",
                            id, e.getClass().getSimpleName());
                }
            }
            if (ids.size() < POLL_LIMIT) {
                break;
            }
        }
        incidentEscalation.escalateFallTimedOut(nowMs);
    }
}
