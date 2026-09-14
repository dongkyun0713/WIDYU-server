package com.widyu.fcm.application;

import com.widyu.fcm.dto.FcmSendDto;
import java.time.Duration;
import java.util.function.BooleanSupplier;

public interface FcmTransport {
    Result send(String token, FcmSendDto dto);

    default Result send(String token, FcmSendDto dto, BooleanSupplier beforeSend) {
        if (!beforeSend.getAsBoolean()) {
            return Result.rejected(false);
        }
        return send(token, dto);
    }

    record Result(boolean success, boolean permanentToken, boolean retryable, Duration retryAfter) {
        public static Result delivered() { return new Result(true, false, false, Duration.ZERO); }
        public static Result retry(Duration delay) { return new Result(false, false, true, delay); }
        public static Result rejected(boolean permanentToken) {
            return new Result(false, permanentToken, false, Duration.ZERO);
        }
    }
}
