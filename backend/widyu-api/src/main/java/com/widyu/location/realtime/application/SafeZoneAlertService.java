package com.widyu.location.realtime.application;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.fcm.event.safezone.dto.SafeZoneEnterEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class SafeZoneAlertService {

    private static final String ALERT_KEY_PREFIX = "safezone:alert:";
    private static final long ALERT_TTL_SECONDS = 1800;
    private static final String EXITED = "EXITED";
    private static final String RETURNED = "RETURNED";
    private static final DefaultRedisScript<Long> TRANSITION = script("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2], 'KEEPTTL')
                return 1
            end
            return 0
            """);
    private static final DefaultRedisScript<Long> RELEASE = script("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public void handleSafeZoneTransition(Long memberId, String previousLocationType, String currentLocationType) {
        String alertKey = ALERT_KEY_PREFIX + memberId;
        if (currentLocationType != null) {
            if (previousLocationType != null) {
                return;
            }
            Long transitioned = redisTemplate.execute(TRANSITION, List.of(alertKey), EXITED, RETURNED);
            if (!Long.valueOf(1L).equals(transitioned)) {
                return;
            }
            compensateOnRollback(alertKey, RETURNED, EXITED);
            eventPublisher.publishEvent(new SafeZoneEnterEvent(memberId));
            return;
        }
        if (previousLocationType == null) {
            return;
        }
        Boolean reserved = redisTemplate.opsForValue()
                .setIfAbsent(alertKey, EXITED, ALERT_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(reserved)) {
            log.debug("안심구역 이탈 알림 묶음 유지: memberId={}", memberId);
            return;
        }
        releaseAlertOnRollback(alertKey);
        eventPublisher.publishEvent(new SafeZoneExitEvent(memberId));
        log.info("안심구역 이탈 소식 이벤트 발행: memberId={}", memberId);
    }

    private void releaseAlertOnRollback(String alertKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    redisTemplate.execute(RELEASE, List.of(alertKey), EXITED);
                }
            }
        });
    }

    private void compensateOnRollback(String alertKey, String from, String to) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    redisTemplate.execute(TRANSITION, List.of(alertKey), from, to);
                }
            }
        });
    }

    private static DefaultRedisScript<Long> script(String content) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(content);
        script.setResultType(Long.class);
        return script;
    }
}
