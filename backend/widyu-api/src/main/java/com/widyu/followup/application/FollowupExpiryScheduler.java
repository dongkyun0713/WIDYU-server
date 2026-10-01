package com.widyu.followup.application;

import com.widyu.followup.FollowupCardState;
import com.widyu.followup.repository.FollowupCardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupExpiryScheduler {
    private final FollowupCardService cardService;
    private final FollowupCardRepository cardRepository;

    @Scheduled(fixedDelay = 60_000)
    public void expire() {
        if (!cardService.enabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long afterId = 0;
        while (true) {
            List<Long> ids = cardRepository.findDueIds(FollowupCardState.ISSUED, nowMs, afterId,
                    PageRequest.of(0, 100));
            if (ids.isEmpty()) {
                return;
            }
            for (Long id : ids) {
                try {
                    cardService.expireIfDue(id, nowMs);
                } catch (RuntimeException e) {
                    log.warn("후속 카드 만료 실패: cardId={}, error={}", id, e.getClass().getSimpleName());
                }
                afterId = id;
            }
        }
    }
}
