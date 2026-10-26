package com.widyu.fcm.application;

import com.widyu.fcm.dto.FcmSendDto;
import java.time.Duration;
import java.util.function.BooleanSupplier;

public interface FcmTransport {
    Result send(String token, FcmSendDto dto);

    default Result send(FcmDelivery delivery, BooleanSupplier beforeSend) {
        return send(delivery.token(), delivery.message(), beforeSend);
    }

    default Result send(String token, FcmSendDto dto, BooleanSupplier beforeSend) {
        if (!beforeSend.getAsBoolean()) {
            return Result.rejected(false, Result.PREFLIGHT);
        }
        return send(token, dto);
    }

    /** detail은 실패 로그에 붙는 코드 값이다. 토큰·제목·본문·FCM 오류 메시지 원문은 넣지 않는다. */
    record Result(boolean success, boolean permanentToken, boolean retryable, Duration retryAfter, String detail) {
        public static final String PREFLIGHT = "PREFLIGHT";
        public static final String EXPIRED_BEFORE_SEND = "EXPIRED_BEFORE_SEND";

        public static Result delivered() { return new Result(true, false, false, Duration.ZERO, null); }
        public static Result retry(Duration delay) { return new Result(false, false, true, delay, null); }
        public static Result rejected(boolean permanentToken) { return rejected(permanentToken, null); }
        public static Result rejected(boolean permanentToken, String detail) {
            return new Result(false, permanentToken, false, Duration.ZERO, detail);
        }
    }
}
