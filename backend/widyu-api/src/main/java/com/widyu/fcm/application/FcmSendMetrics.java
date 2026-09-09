package com.widyu.fcm.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FcmSendMetrics {

    private final MeterRegistry meterRegistry;

    public void record(Runnable sendOperation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            sendOperation.run();
        } finally {
            sample.stop(Timer.builder("fcm.send").register(meterRegistry));
        }
    }
}
