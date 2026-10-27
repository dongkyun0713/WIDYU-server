package com.widyu.sensor.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.fcm.application.FcmTransport;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.sensor.application.ClockMappingService;
import com.widyu.sensor.dto.request.SensorBatchRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 같은 시계 매핑을 처음 보는 요청 둘이 겹칠 때 둘 다 통과하는지 실제 MySQL(REPEATABLE READ)에서 확인한다(#905, LLD-0044 5.1).
 *
 * <p>H2와 Mockito로는 재현되지 않는다. 경합 뒤 재조회가 첫 조회의 스냅샷을 그대로 쓰면 다른 요청이 커밋한 행을 못 보는데,
 * 이 스냅샷 동작은 InnoDB에만 있다.
 *
 * <p>겹침은 타이밍이 아니라 잠금으로 만든다. 상대 요청 역할의 연결이 같은 매핑 행을 INSERT만 하고 커밋하지 않은 채
 * 기다리면, 검사 대상 요청은 첫 조회에서 행을 못 보고(커밋 전이라 스냅샷에 없다) INSERT에서 UK 잠금을 기다린다.
 * 그 대기를 {@code performance_schema.data_lock_waits}로 확인한 뒤 상대를 커밋하면 검사 대상은 UK 충돌을 거쳐 재조회한다.
 *
 * <p>실행: {@code CLOCK_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/clock_race_<name> DB_USERNAME=root DB_PASSWORD=
 * ./gradlew :backend:widyu-api:test --tests 'com.widyu.sensor.integration.ClockMappingConcurrentRegisterMySqlTest'}
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "logging.level.org.hibernate.SQL=warn",
        "logging.level.org.hibernate.type=warn",
        "logging.level.org.hibernate.orm.jdbc.bind=warn",
        "firebase.config-path=/dev/null",
        "s3.credentials.access-key=test",
        "s3.credentials.secret-key=test",
        "s3.region.statics=ap-northeast-2",
        "s3.bucket-name=test-bucket",
        "coolsms.api-key=test",
        "coolsms.api-secret=test",
        "coolsms.api-url=https://api.coolsms.co.kr",
        "coolsms.from-phone-number=01000000000",
        "coolsms.verification-code-length=6",
        "coolsms.verification-code-ttl=300",
        "coolsms.message-template=인증번호: {code}"
})
@EnabledIfEnvironmentVariable(named = "CLOCK_MYSQL_URL", matches = ".+")
@Tag("clock-mysql")
@DisplayName("시계 매핑 동시 등록 MySQL 재현")
class ClockMappingConcurrentRegisterMySqlTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String DEVICE_ID = "gw-race";
    private static final String BOOT_ID = "boot-race";
    private static final long ANCHOR_ELAPSED_NS = 993_847_100_000_001L;
    private static final long ANCHOR_EPOCH_MS = 1_760_000_000_000L;
    private static final double UNCERTAINTY_MS = 2.0;
    /** 상대 요청이 먼저 저장한 관측 범위. */
    private static final long WINNER_MIN_NS = 993_847_120_000_000L;
    private static final long WINNER_MAX_NS = 993_847_130_000_000L;
    /** 검사 대상 요청의 관측 범위. 상대보다 양쪽으로 넓어 갱신되면 값이 바뀐다. */
    private static final long LOSER_MIN_NS = 993_847_110_000_000L;
    private static final long LOSER_MAX_NS = 993_847_140_000_000L;

    @MockBean private FcmTransport transport;
    @MockBean private S3Service s3Service;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private ClockMappingService clockMappingService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CLOCK_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/clock_race_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("루프백의 일회용 clock_race_ 데이터베이스만 허용합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", ""));
    }

    @Test
    @DisplayName("센서 배치 경로에서 같은 매핑을 동시에 처음 등록하면 둘 다 통과하고 행 하나가 두 관측 범위를 덮는다")
    void 센서_배치_경로에서_같은_매핑을_동시에_처음_등록하면_둘_다_통과한다() throws Exception {
        // given
        String mappingId = "cm-race-" + SEQ.incrementAndGet();

        // when
        Throwable failure = registerWhileWinnerCommits(mappingId, () -> register(mappingId));

        // then
        assertThat(failure).isNull();
        assertSingleRowCoversBothRanges(mappingId);
    }

    @Test
    @DisplayName("바깥 트랜잭션 안에서 같은 매핑을 동시에 처음 등록해도 둘 다 통과하고 행 하나가 두 관측 범위를 덮는다")
    void 바깥_트랜잭션_안에서_같은_매핑을_동시에_처음_등록해도_둘_다_통과한다() throws Exception {
        // given: 마커 등록(CollectionRunService.registerMarker)처럼 바깥 트랜잭션에 합류하는 호출
        String mappingId = "cm-race-" + SEQ.incrementAndGet();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        // when
        Throwable failure = registerWhileWinnerCommits(mappingId,
                () -> outer.executeWithoutResult(status -> register(mappingId)));

        // then
        assertThat(failure).isNull();
        assertSingleRowCoversBothRanges(mappingId);
    }

    /** 상대 요청의 INSERT를 커밋 전으로 붙잡아 둔 채 검사 대상을 돌리고, 검사 대상이 UK 잠금을 기다리면 상대를 커밋한다. */
    private Throwable registerWhileWinnerCommits(String mappingId, Runnable loser) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            insertUncommitted(winner, mappingId);

            Future<Throwable> result = executor.submit(() -> {
                try {
                    loser.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            awaitLockWait(result);
            winner.commit();
            return result.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void register(String mappingId) {
        clockMappingService.register(
                new SensorBatchRequest.Clock(
                        BOOT_ID, mappingId, String.valueOf(ANCHOR_ELAPSED_NS), ANCHOR_EPOCH_MS, UNCERTAINTY_MS),
                DEVICE_ID, LOSER_MIN_NS, LOSER_MAX_NS);
    }

    private void insertUncommitted(Connection connection, String mappingId) throws Exception {
        String sql = "INSERT INTO clock_mapping (clock_mapping_id, device_id, boot_id, anchor_elapsed_ns,"
                + " anchor_epoch_ms, uncertainty_ms, observed_min_elapsed_ns, observed_max_elapsed_ns,"
                + " first_seen_at_ms, last_seen_at_ms, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, mappingId);
            statement.setString(2, DEVICE_ID);
            statement.setString(3, BOOT_ID);
            statement.setLong(4, ANCHOR_ELAPSED_NS);
            statement.setLong(5, ANCHOR_EPOCH_MS);
            statement.setDouble(6, UNCERTAINTY_MS);
            statement.setLong(7, WINNER_MIN_NS);
            statement.setLong(8, WINNER_MAX_NS);
            statement.setLong(9, 1_760_000_000_500L);
            statement.setLong(10, 1_760_000_000_500L);
            statement.executeUpdate();
        }
    }

    /** 검사 대상의 INSERT가 상대의 UK 잠금을 기다리기 시작할 때까지 기다린다. 그 전에 끝나면 결과를 담아 실패시킨다. */
    private void awaitLockWait(Future<Throwable> loser) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (loser.isDone()) {
                throw new IllegalStateException("검사 대상 요청이 잠금 대기 전에 끝났습니다.", loser.get());
            }
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("검사 대상 요청이 UK 잠금 대기에 들어가지 않았습니다.");
    }

    private void assertSingleRowCoversBothRanges(String mappingId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, MIN(observed_min_elapsed_ns) AS min_ns, MAX(observed_max_elapsed_ns) AS max_ns"
                        + " FROM clock_mapping WHERE clock_mapping_id = ?",
                mappingId);
        assertThat(((Number) row.get("cnt")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("min_ns")).longValue()).isEqualTo(LOSER_MIN_NS);
        assertThat(((Number) row.get("max_ns")).longValue()).isEqualTo(LOSER_MAX_NS);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
