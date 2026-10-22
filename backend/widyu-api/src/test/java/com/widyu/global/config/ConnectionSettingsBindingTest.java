package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.widyu.global.properties.RedisProperties;
import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * DB·Redis 응답 대기 상한과 OSIV는 설정 파일에만 있어, 키 이름이나 단위가 틀리면 기본값(Hikari 30초, Lettuce 하드코딩)으로
 * 조용히 돌아간다. 실제 YAML을 읽어 바인딩한 값으로 검증한다(LLD-0106).
 */
class ConnectionSettingsBindingTest {

    private static final Map<String, Object> DATASOURCE_REQUIRED =
            Map.of("MYSQL_HOST", "db.example.internal", "MYSQL_PORT", "3306", "DB_NAME", "widyu");

    @Test
    @DisplayName("데이터소스 설정을 바인딩하면 Hikari 커넥션 대기가 3초, 생존 확인이 1초, 풀이 10이다")
    void 데이터소스_설정을_바인딩하면_Hikari_대기_상한이_기본값이다() throws IOException {
        // given
        Binder binder = binderOf("application-datasource.yml", DATASOURCE_REQUIRED);

        // when
        HikariConfig hikari = binder.bind("spring.datasource.hikari", Bindable.ofInstance(new HikariConfig())).get();

        // then
        assertThat(hikari.getConnectionTimeout()).isEqualTo(3_000L);
        assertThat(hikari.getValidationTimeout()).isEqualTo(1_000L);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("커넥션 대기 환경변수를 바꾸면 Hikari 커넥션 대기가 그 값이다")
    void 커넥션_대기_환경변수를_바꾸면_그_값을_바인딩한다() throws IOException {
        // given
        Map<String, Object> overrides = new TreeMap<>(DATASOURCE_REQUIRED);
        overrides.put("DB_POOL_CONNECTION_TIMEOUT_MS", "5000");
        Binder binder = binderOf("application-datasource.yml", overrides);

        // when
        HikariConfig hikari = binder.bind("spring.datasource.hikari", Bindable.ofInstance(new HikariConfig())).get();

        // then
        assertThat(hikari.getConnectionTimeout()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("데이터소스 URL을 바인딩하면 새 연결과 실행 중 쿼리의 대기 상한이 들어간다")
    void 데이터소스_URL을_바인딩하면_JDBC_타임아웃이_들어간다() throws IOException {
        // given
        Binder binder = binderOf("application-datasource.yml", DATASOURCE_REQUIRED);

        // when
        String url = binder.bind("spring.datasource.url", String.class).get();

        // then
        assertThat(url).contains("&connectTimeout=3000").contains("&socketTimeout=60000");
    }

    @Test
    @DisplayName("Redis 설정 파일로 연결 팩토리를 만들면 명령 대기 상한이 3초다")
    void Redis_설정_파일로_연결_팩토리를_만들면_명령_대기가_3초다() throws IOException {
        // given
        RedisProperties properties = binderOf("application-redis.yml", Map.of())
                .bind("spring.data.redis", RedisProperties.class).get();

        // when
        Duration commandTimeout = commandTimeoutOf(properties);

        // then
        assertThat(commandTimeout).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("Redis 명령 대기 환경변수를 바꾸면 연결 팩토리가 그 값을 쓴다")
    void Redis_명령_대기_환경변수를_바꾸면_그_값을_쓴다() throws IOException {
        // given
        RedisProperties properties = binderOf("application-redis.yml", Map.of("REDIS_COMMAND_TIMEOUT", "5s"))
                .bind("spring.data.redis", RedisProperties.class).get();

        // when
        Duration commandTimeout = commandTimeoutOf(properties);

        // then
        assertThat(commandTimeout).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Redis 명령 대기 설정이 없으면 3초를 바인딩한다")
    void Redis_명령_대기_설정이_없으면_3초를_바인딩한다() {
        // given
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("redis", Map.of("spring.data.redis.host", "localhost",
                "spring.data.redis.port", "6379")));

        // when
        RedisProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.data.redis", RedisProperties.class).get();

        // then
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("설정 파일을 모두 읽으면 OSIV는 공통 파일에서만 끄고 어느 프로파일도 다시 켜지 않는다")
    void 설정_파일을_모두_읽으면_OSIV는_공통_파일에서만_끈다() throws IOException {
        // given
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:application*.yml");

        // when
        List<String> names = new ArrayList<>();
        Map<String, String> openInView = new TreeMap<>();
        for (Resource file : files) {
            names.add(file.getFilename());
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file.getFilename(), file)) {
                Object value = source.getProperty("spring.jpa.open-in-view");
                if (value != null) {
                    openInView.put(file.getFilename(), String.valueOf(value));
                }
            }
        }

        // then
        assertThat(names).contains("application.yml", "application-prod.yml", "application-dev.yml",
                "application-local.yml", "application-test.yml");
        assertThat(openInView).containsExactly(entry("application.yml", "false"));
    }

    private Duration commandTimeoutOf(RedisProperties properties) {
        LettuceConnectionFactory factory =
                (LettuceConnectionFactory) new RedisConfig(properties).redisConnectionFactory();
        return factory.getClientConfiguration().getCommandTimeout();
    }

    private Binder binderOf(String classpathLocation, Map<String, Object> overrides) throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load(classpathLocation, new ClassPathResource(classpathLocation));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        sources.addFirst(new MapPropertySource("test-overrides", overrides));
        return new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
    }
}
