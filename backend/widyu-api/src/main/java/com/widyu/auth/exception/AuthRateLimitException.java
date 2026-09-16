package com.widyu.auth.exception;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import lombok.Getter;

@Getter
public class AuthRateLimitException extends BusinessException {
    private final long retryAfterSeconds;

    public AuthRateLimitException(long retryAfterMillis) {
        super(ErrorCode.AUTH_RATE_LIMITED);
        retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
