package com.widyu.heart.repository;

import com.widyu.heart.HeartRateResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.convert.RedisConverter;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 최신 심박(Redis {@code heart_rate_result:{memberId}})의 유일한 쓰기 경로다(LLD-0090).
 *
 * <p>단건·배치가 처리 완료 순서대로 덮어쓰면 늦게 끝난 과거 측정이 최신값을 되돌린다. 그래서 저장된 측정
 * 시각보다 늦을 때만 Lua 스크립트 한 번으로 비교와 갱신·TTL 재설정을 함께 한다. 같은 측정 시각이면 갱신하지 않는다.
 *
 * <p>해시 필드는 Spring Data의 {@link RedisConverter}로 만들어 {@code findByMemberId}가 그대로 읽는다
 * ({@code _class}·enum·{@code LocalDateTime} 문자열 형식이 같다). {@code LocalDateTime} 문자열은 초·소수부 자릿수가
 * 값마다 달라 문자열로 비교하지 않고, 비교용 epoch 밀리초 필드를 함께 저장한다. 읽기 매핑에 없는 필드라 조회에는 영향이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartRateLatestResultWriter {

    /** 비교용 측정 시각 필드. Lua 스크립트와 같은 이름이다. */
    static final String MEASURED_AT_EPOCH_MS = "measuredAtEpochMs";
    private static final Long UPDATED = 1L;
    private static final DefaultRedisScript<Long> SAVE_IF_NEWER = saveIfNewerScript();

    private final StringRedisTemplate redis;
    private final RedisConverter redisConverter;

    /**
     * 저장된 값이 없거나 더 이른 측정이면 덮어쓰고 TTL을 다시 건 뒤 true를 돌려준다. 그 밖에는 기존 값·TTL을
     * 그대로 두고 false다. Redis 예외는 호출자에게 그대로 전파한다.
     */
    public boolean saveIfNewer(HeartRateResult result) {
        RedisData data = new RedisData();
        redisConverter.write(result, data);
        String measuredAtEpochMs = Long.toString(epochMillis(result.getMeasuredAt()));

        List<String> args = new ArrayList<>();
        args.add(measuredAtEpochMs);
        args.add(Long.toString(data.getTimeToLive()));
        args.add(data.getId());
        data.getBucket().rawMap().forEach((field, value) -> {
            args.add(new String(field, StandardCharsets.UTF_8));
            args.add(new String(value, StandardCharsets.UTF_8));
        });
        args.add(MEASURED_AT_EPOCH_MS);
        args.add(measuredAtEpochMs);

        List<String> keys = List.of(data.getKeyspace() + ":" + data.getId(), data.getKeyspace());
        boolean updated = UPDATED.equals(redis.execute(SAVE_IF_NEWER, keys, args.toArray()));
        if (!updated) {
            // 심박 값·측정 시각은 남기지 않는다(#639).
            log.debug("최신 심박 갱신 생략(같거나 더 늦은 측정이 이미 있음): memberId={}", result.getMemberId());
        }
        return updated;
    }

    /** 순서만 비교하므로 고정 오프셋(UTC)으로 환산한다. 밀리초 아래만 다른 두 측정은 같은 시각으로 본다. */
    private static long epochMillis(LocalDateTime measuredAt) {
        return measuredAt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static DefaultRedisScript<Long> saveIfNewerScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/heart-latest-result-save-if-newer.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
