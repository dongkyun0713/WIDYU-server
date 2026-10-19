package com.widyu.heart.repository;

import com.widyu.global.config.RedisConfig;
import com.widyu.global.properties.RedisProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.data.redis.repository.support.RedisRepositoryFactory;

/**
 * 최신 심박 실측·검증용 실제 Redis 연결(LLD-0090). {@code HEART_REDIS_PORT}로 지정한 버리는 Redis(127.0.0.1)에만 붙고,
 * 6379면 개발용 Redis를 덮을 수 있어 거부한다.
 *
 * <p>연결·템플릿은 운영 설정({@link RedisConfig})으로 만들고, 리포지토리는 {@code @EnableRedisRepositories} 기본값과 같은
 * 어댑터·매핑 컨텍스트로 Spring Data 리포지토리 팩토리에서 만든다. 쓰기 빈은 그 어댑터의 변환기를 쓴다.
 */
public record HeartRateRedisFixture(
        LettuceConnectionFactory connectionFactory,
        HeartRateResultRepository repository,
        HeartRateLatestResultWriter writer
) implements AutoCloseable {

    public static final String PORT_ENV = "HEART_REDIS_PORT";
    private static final int DEV_REDIS_PORT = 6379;

    public static HeartRateRedisFixture open() {
        int port = Integer.parseInt(System.getenv(PORT_ENV));
        if (port == DEV_REDIS_PORT) {
            throw new IllegalStateException("개발용 Redis(6379)에는 실측을 돌리지 않는다. 버리는 Redis 포트를 지정한다.");
        }
        RedisConfig config = new RedisConfig(new RedisProperties("127.0.0.1", port, null, false));
        LettuceConnectionFactory connectionFactory = (LettuceConnectionFactory) config.redisConnectionFactory();
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        RedisTemplate<String, Object> template = config.redisTemplate(connectionFactory);
        RedisMappingContext mappingContext = new RedisMappingContext();
        RedisKeyValueAdapter adapter = new RedisKeyValueAdapter(template, mappingContext);
        adapter.afterPropertiesSet();
        HeartRateResultRepository repository = new RedisRepositoryFactory(
                new RedisKeyValueTemplate(adapter, mappingContext))
                .getRepository(HeartRateResultRepository.class);
        HeartRateLatestResultWriter writer = new HeartRateLatestResultWriter(
                new StringRedisTemplate(connectionFactory), adapter.getConverter());
        return new HeartRateRedisFixture(connectionFactory, repository, writer);
    }

    public static String key(Long memberId) {
        return "heart_rate_result:" + memberId;
    }

    public Map<String, String> hash(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Map<String, String> result = new TreeMap<>();
            Map<byte[], byte[]> raw = connection.hashCommands().hGetAll(bytes(key));
            raw.forEach((field, value) -> result.put(text(field), text(value)));
            return result;
        }
    }

    public void hset(String key, String field, String value) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.hashCommands().hSet(bytes(key), bytes(field), bytes(value));
        }
    }

    public String type(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.keyCommands().type(bytes(key)).code();
        }
    }

    public Long ttl(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.keyCommands().ttl(bytes(key));
        }
    }

    public void expire(String key, long seconds) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.keyCommands().expire(bytes(key), seconds);
        }
    }

    public Boolean exists(String key) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.keyCommands().exists(bytes(key));
        }
    }

    public Boolean isMember(String key, Long memberId) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            return connection.setCommands().sIsMember(bytes(key), bytes(String.valueOf(memberId)));
        }
    }

    public List<String> keys(String pattern) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Set<byte[]> raw = connection.keyCommands().keys(bytes(pattern));
            List<String> result = new ArrayList<>();
            raw.forEach(key -> result.add(text(key)));
            Collections.sort(result);
            return result;
        }
    }

    public void deleteMember(Long memberId) {
        repository.deleteById(memberId);
    }

    @Override
    public void close() {
        connectionFactory.destroy();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
