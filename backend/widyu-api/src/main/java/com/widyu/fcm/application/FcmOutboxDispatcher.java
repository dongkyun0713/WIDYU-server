package com.widyu.fcm.application;

import com.widyu.fcm.repository.FcmOutboxRepository;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
public class FcmOutboxDispatcher {
    private final FcmOutboxTransactions transactions;
    private final FcmOutboxRepository outbox;
    private final FcmTransport transport;
    private final FcmSendMetrics metrics;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.AbortPolicy());

    public FcmOutboxDispatcher(FcmOutboxTransactions transactions, FcmOutboxRepository outbox, FcmTransport transport,
            FcmSendMetrics metrics) {
        this.transactions = transactions;
        this.outbox = outbox;
        this.transport = transport;
        this.metrics = metrics;
    }

    public void submit(Long id) {
        try {
            executor.execute(() -> dispatch(id));
        } catch (RejectedExecutionException exception) {
            log.warn("FCM immediate worker busy; persisted delivery will be polled: id={}", id);
        }
    }

    @Scheduled(fixedDelayString = "${fcm.delivery.poll-delay-ms:1000}")
    public void poll() {
        for (Long id : outbox.findDue(LocalDateTime.now(), PageRequest.of(0, 50))) {
            submit(id);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch(Long id) {
        try {
            FcmDelivery delivery = transactions.claim(id);
            if (delivery != null) {
                metrics.record(() -> {
                    FcmTransport.Result result = transport.send(delivery,
                            () -> transactions.preflight(delivery));
                    transactions.finish(delivery, result);
                });
            }
        } catch (RuntimeException exception) {
            // DB·finalize 실패는 영속 claim을 남겨 lease 회수로 복구한다. 페이로드와 토큰은 절대 로그에 남기지 않는다.
            log.warn("FCM delivery interrupted; lease recovery required: id={}, type={}",
                    id, exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
