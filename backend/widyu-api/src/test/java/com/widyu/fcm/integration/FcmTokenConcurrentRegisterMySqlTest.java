package com.widyu.fcm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.fcm.application.FcmTransport;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
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
 * 같은 FCM 토큰 등록 둘이 겹칠 때 둘 다 성공하고 행 하나로 수렴하는지 실제 MySQL에서 확인한다(#915).
 *
 * <p>겹침은 타이밍이 아니라 잠금으로 만든다. 상대 요청 역할의 연결이 같은 토큰을 INSERT만 하고 커밋하지 않은 채
 * 기다리면, 검사 대상 등록은 UK 잠금을 기다린다. 그 대기를 {@code performance_schema.data_lock_waits}로 확인한 뒤
 * 상대를 커밋한다. 조회 후 INSERT하던 이전 방식은 여기서 Duplicate entry로 실패했다.
 *
 * <p>실행: {@code FCM_TOKEN_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/fcm_token_race_<name> DB_USERNAME=root DB_PASSWORD=
 * ./gradlew :backend:widyu-api:test --tests 'com.widyu.fcm.integration.FcmTokenConcurrentRegisterMySqlTest'}
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
@EnabledIfEnvironmentVariable(named = "FCM_TOKEN_MYSQL_URL", matches = ".+")
@Tag("fcm-token-mysql")
@DisplayName("FCM 토큰 동시 등록 MySQL 재현")
class FcmTokenConcurrentRegisterMySqlTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockBean private FcmTransport transport;
    @MockBean private S3Service s3Service;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private MemberFcmTokenRepository memberFcmTokenRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("FCM_TOKEN_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fcm_token_race_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("루프백의 일회용 fcm_token_race_ 데이터베이스만 허용합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", ""));
    }

    @Test
    @DisplayName("같은 토큰 등록이 커밋 전 다른 등록과 겹치면 충돌 없이 성공하고 활성 토큰 행 하나만 남는다")
    void 같은_토큰_등록이_겹쳐도_둘_다_성공하고_행_하나로_수렴한다() throws Exception {
        // given
        Long memberId = saveMember();
        String token = "race-token-" + SEQ.incrementAndGet();

        // when
        Throwable failure = registerWhileWinnerCommits(token, memberId);

        // then
        assertThat(failure).isNull();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, MAX(member_id) AS member_id, SUM(active = 1) AS active_rows"
                        + " FROM member_fcm_token WHERE token = ?", token);
        assertThat(((Number) row.get("cnt")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("member_id")).longValue()).isEqualTo(memberId);
        assertThat(((Number) row.get("active_rows")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원의 비활성 토큰을 등록하면 현재 회원에게 옮기고 다시 활성화한다")
    void 다른_회원의_비활성_토큰을_등록하면_옮기고_활성화한다() {
        // given
        Long previousId = saveMember();
        Long currentId = saveMember();
        String token = "transfer-token-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO member_fcm_token (token, member_id, active, registered_at, expired_at)"
                + " VALUES (?, ?, false, NOW(), NOW())", token, previousId);

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> memberFcmTokenRepository.upsertActive(token, currentId, LocalDateTime.now()));

        // then
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, MAX(member_id) AS member_id, SUM(active = 1) AS active_rows,"
                        + " MAX(expired_at) AS expired_at, MAX(last_used_at) AS last_used_at"
                        + " FROM member_fcm_token WHERE token = ?", token);
        assertThat(((Number) row.get("cnt")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("member_id")).longValue()).isEqualTo(currentId);
        assertThat(((Number) row.get("active_rows")).intValue()).isEqualTo(1);
        assertThat(row.get("expired_at")).isNull();
        assertThat(row.get("last_used_at")).isNotNull();
    }

    /** 상대 등록의 INSERT를 커밋 전으로 붙잡아 둔 채 검사 대상을 돌리고, 검사 대상이 UK 잠금을 기다리면 상대를 커밋한다. */
    private Throwable registerWhileWinnerCommits(String token, Long memberId) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement statement = winner.prepareStatement(
                    "INSERT INTO member_fcm_token (token, member_id, active, registered_at) VALUES (?, ?, true, NOW())")) {
                statement.setString(1, token);
                statement.setLong(2, memberId);
                statement.executeUpdate();
            }

            Future<Throwable> result = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                            status -> memberFcmTokenRepository.upsertActive(token, memberId, LocalDateTime.now()));
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

    /** 검사 대상 등록이 상대의 UK 잠금을 기다리기 시작할 때까지 기다린다. 그 전에 끝나면 결과를 담아 실패시킨다. */
    private void awaitLockWait(Future<Throwable> loser) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (loser.isDone()) {
                throw new IllegalStateException("검사 대상 등록이 잠금 대기 전에 끝났습니다.", loser.get());
            }
            Integer waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("검사 대상 등록이 UK 잠금 대기에 들어가지 않았습니다.");
    }

    private Long saveMember() {
        int seq = SEQ.incrementAndGet();
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자" + seq,
                "010" + String.format("%08d", seq));
        return memberRepository.save(member).getId();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
