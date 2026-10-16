package com.widyu.fcm.application.degraded;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.fcm.application.degraded.DegradedAlertReconcileTransactions.Outcome;
import com.widyu.fcm.application.degraded.DegradedAlertReconcileTransactions.ReconcileRecord;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 복구 후 저하 경로 발송 기록(fcm:degraded:reconcile)을 fcm_notification 이력으로 옮긴다(LLD-0087 5.1).
 * DB가 아직 안 되면 꺼낸 기록을 목록에 되돌리고 다음 주기에 다시 시도한다. 평시 비용은 주기마다 LPOP 1회다.
 * 저하 경로를 꺼도 남은 기록은 옮긴다. 로그에는 스트림 ID와 건수만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegradedAlertReconciler {

    private final StringRedisTemplate redis;
    private final DegradedAlertReconcileTransactions transactions;
    private final FcmDegradedProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${fcm.degraded.reconcile-ms:10000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcile() {
        List<String> items;
        try {
            items = redis.opsForList().leftPop(DegradedEmergencyAlertConsumer.RECONCILE_KEY,
                    properties.reconcileBatch());
        } catch (RuntimeException exception) {
            log.warn("저하 경로 보정 목록 조회 실패: type={}", exception.getClass().getSimpleName());
            return;
        }
        if (items == null) {
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            try {
                handle(items.get(index));
            } catch (DataIntegrityViolationException exception) {
                // 되돌리면 같은 기록이 매 주기 뒤 기록을 막는다. 제약 위반은 다시 해도 같으므로 버린다.
                dropped("rejected");
                log.warn("저하 경로 보정 기록 저장 거부: type={}", exception.getClass().getSimpleName());
            } catch (DataAccessException | TransactionException exception) {
                defer(items.subList(index, items.size()), exception);
                return;
            }
        }
    }

    private void handle(String json) {
        ReconcileRecord record = parse(json);
        if (record == null) {
            dropped("malformed");
            log.warn("저하 경로 보정 기록 해석 실패: length={}", json.length());
            return;
        }
        Outcome outcome = transactions.persist(record);
        if (outcome == Outcome.SAVED) {
            meterRegistry.counter("fcm.degraded.reconciled").increment();
            return;
        }
        if (outcome == Outcome.TOKEN_MISSING) {
            dropped("token_missing");
            log.warn("저하 경로 보정 토큰 없음: streamId={}", record.streamId());
            return;
        }
        if (outcome == Outcome.RECIPIENT_MISSING) {
            dropped("recipient_missing");
            log.warn("저하 경로 보정 수신자 없음: streamId={}", record.streamId());
        }
    }

    private ReconcileRecord parse(String json) {
        try {
            ReconcileRecord record = objectMapper.readValue(json, ReconcileRecord.class);
            if (record == null || !record.complete()) {
                return null;
            }
            return record;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 실패한 기록과 아직 처리하지 않은 기록을 목록 끝에 되돌린다. 되돌리기도 실패하면 그 건수는 잃는다. */
    private void defer(List<String> remaining, RuntimeException cause) {
        try {
            redis.opsForList().rightPushAll(DegradedEmergencyAlertConsumer.RECONCILE_KEY, remaining);
            meterRegistry.counter("fcm.degraded.reconcile_deferred").increment(remaining.size());
            log.warn("저하 경로 보정 보류, DB 복구 대기: deferred={}, type={}",
                    remaining.size(), cause.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            log.warn("저하 경로 보정 기록 되돌리기 실패, 유실: lost={}, type={}",
                    remaining.size(), exception.getClass().getSimpleName());
        }
    }

    private void dropped(String reason) {
        meterRegistry.counter("fcm.degraded.reconcile_dropped", "reason", reason).increment();
    }
}
