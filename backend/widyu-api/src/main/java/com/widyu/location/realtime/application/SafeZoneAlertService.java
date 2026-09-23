package com.widyu.location.realtime.application;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class SafeZoneAlertService {

    private static final String SAFE_ZONE_ALERT_KEY_PREFIX = "safezone:alert:";
    private static final long SAFE_ZONE_ALERT_TTL_SECONDS = 1800;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public void handleSafeZoneTransition(Long memberId, String previousLocationType, String currentLocationType) {
        String alertKey = SAFE_ZONE_ALERT_KEY_PREFIX + memberId;

        if (currentLocationType != null) {
            redisTemplate.delete(alertKey);
            return;
        }

        if (previousLocationType == null) {
            return;
        }

        Boolean alertReserved = redisTemplate.opsForValue()
                .setIfAbsent(alertKey, true, SAFE_ZONE_ALERT_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(alertReserved)) {
            log.debug("안전구역 이탈 알림 스킵 (중복 방지) - memberId: {}", memberId);
            return;
        }

        releaseAlertOnRollback(alertKey);
        eventPublisher.publishEvent(new SafeZoneExitEvent(memberId));
        log.info("안전구역 이탈 이벤트 발행 - memberId: {}", memberId);
    }

    // 알림 요청(outbox)이 롤백되면 플래그도 풀어야 다음 위치 갱신에서 이탈을 다시 알린다 (LLD-0058)
    private void releaseAlertOnRollback(String alertKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    redisTemplate.delete(alertKey);
                }
            }
        });
    }
}
