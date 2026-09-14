package com.widyu.auth.infrastructure;

import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AuthLimitProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthLimitStore {
    private static final String PREFIX = "auth:{limits}:";
    private static final DefaultRedisScript<Long> SMS = script("auth-sms-reserve", Long.class);
    private static final DefaultRedisScript<Long> SAVE = script("auth-code-save", Long.class);
    private static final DefaultRedisScript<List> CONSUME = script("auth-code-consume", List.class);
    private static final DefaultRedisScript<Long> DISCARD = script("auth-code-discard", Long.class);
    private static final DefaultRedisScript<Long> LOGIN = script("auth-login-reserve", Long.class);
    private static final DefaultRedisScript<Long> COMPLETE = script("auth-login-complete", Long.class);

    private final StringRedisTemplate redis;
    private final AuthLimitProperties properties;

    public void reserveSms(String phone, String ip) {
        if (properties.smsGlobalDay() == null || properties.smsGlobalDay() < 1) {
            throw unavailable();
        }
        List<String> keys = List.of(key("sms:interval", phone), key("sms:hour", phone),
                key("sms:day", phone), key("sms:ip", ip), PREFIX + "sms:global");
        check(execute(SMS, keys, "1", millis(properties.smsIntervalSeconds()),
                Integer.toString(properties.smsPhoneHour()), "3600000",
                Integer.toString(properties.smsPhoneDay()), "86400000",
                Integer.toString(properties.smsIpHour()), "3600000",
                properties.smsGlobalDay().toString(), "86400000"));
    }

    public String saveCode(String phone, String code, String name, int ttlSeconds) {
        String id = UUID.randomUUID().toString();
        execute(SAVE, List.of(key("code:v2", phone)), code, name, millis(ttlSeconds), id);
        return id;
    }

    public void discardCode(String phone, String id) {
        execute(DISCARD, List.of(key("code:v2", phone)), id);
    }

    public String consumeCode(String phone, String code) {
        List<?> result = execute(CONSUME, List.of(key("code:v2", phone)), code);
        String status = (String) result.getFirst();
        if ("ok".equals(status)) {
            return (String) result.get(1);
        }
        if ("limited".equals(status)) {
            throw new AuthRateLimitException(Long.parseLong((String) result.get(1)));
        }
        if ("missing".equals(status)) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND);
        }
        if ("mismatch".equals(status)) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_MISMATCH);
        }
        throw unavailable();
    }

    public LoginAttempt reserveLogin(String email, String ip) {
        List<String> keys = List.of(key("login:account", email.strip().toLowerCase(Locale.ROOT)),
                key("login:ip", ip));
        String id = UUID.randomUUID().toString();
        check(execute(LOGIN, keys, Integer.toString(properties.loginAccountFailures()),
                Integer.toString(properties.loginIpFailures()), millis(properties.loginWindowSeconds()), id));
        return new LoginAttempt(keys, id);
    }

    public void completeLogin(LoginAttempt attempt, boolean success) {
        String outcome = "failure";
        if (success) {
            outcome = "success";
        }
        check(execute(COMPLETE, attempt.keys(), attempt.id(), outcome, millis(properties.loginWindowSeconds())));
    }

    private void check(long result) {
        if (result < 0) {
            throw unavailable();
        }
        if (result > 0) {
            throw new AuthRateLimitException(result);
        }
    }

    private <T> T execute(DefaultRedisScript<T> script, List<String> keys, String... args) {
        try {
            T result = redis.execute(script, keys, (Object[]) args);
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (RuntimeException exception) {
            // Redis exception messages may contain commands, arguments or connection credentials.
            throw unavailable();
        }
    }

    private static String key(String purpose, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return PREFIX + purpose + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String millis(int seconds) {
        return Long.toString(seconds * 1000L);
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE);
    }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
        script.setResultType(type);
        return script;
    }

    public record LoginAttempt(List<String> keys, String id) {
    }
}
