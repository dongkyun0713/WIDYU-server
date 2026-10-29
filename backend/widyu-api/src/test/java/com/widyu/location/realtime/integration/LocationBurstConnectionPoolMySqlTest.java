package com.widyu.location.realtime.integration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.fcm.application.FcmTransport;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 재연결 직후 밀린 위치가 몰릴 때 위치 수신이 DB 커넥션 풀을 막지 않는지 실제 MySQL·Redis에서 확인한다(LLD-0114).
 *
 * <p>2026-10-29 개발 서버에서 한 회원의 밀린 위치 84건이 20초 안에 들어오자, 수신 스레드마다 실시간 트랜잭션의
 * 커넥션을 쥔 채 원본 저장(REQUIRES_NEW)의 두 번째 커넥션을 기다려 풀 10개가 3초씩 막혔다. 풀 크기·대기 시간은
 * 개발 서버와 같게(10개·3000ms), 동시 처리 스레드는 그때 관측한 수신 스레드 수(14개)보다 넉넉한 16개로 둔다.
 *
 * <p>실행: {@code LOCATION_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/location_burst_<name> LOCATION_REDIS_PORT=<port>
 * DB_USERNAME=root DB_PASSWORD= ./gradlew :backend:widyu-api:test --tests '*LocationBurstConnectionPoolMySqlTest'}
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.connection-timeout=3000",
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
@EnabledIfEnvironmentVariable(named = "LOCATION_MYSQL_URL", matches = ".+")
@Tag("location-mysql")
@DisplayName("위치 몰림 커넥션 풀 MySQL 재현")
class LocationBurstConnectionPoolMySqlTest {

    private static final int THREADS = 16;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @MockBean private FcmTransport transport;
    @MockBean private S3Service s3Service;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private RealtimeLocationService realtimeLocationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private SeniorProfileRepository seniorProfileRepository;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        String url = System.getenv("LOCATION_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/location_burst_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("루프백의 일회용 location_burst_ 데이터베이스만 허용합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", ""));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> envOr("LOCATION_REDIS_PORT", "6379"));
    }

    @Test
    @DisplayName("한 회원이 재연결 직후 최근 7분치 위치 84건을 몰아 보내도 커넥션 대기로 실패하지 않고 원본이 모두 남는다")
    void 한_회원의_밀린_위치_84건이_몰려도_실패하지_않는다() throws Exception {
        Result result = burst("single-84", 1, 84, 5_000L);

        assertThat(result.failed()).isZero();
        assertThat(result.storedRows()).isEqualTo(result.sent());
    }

    @Test
    @DisplayName("회원 20명이 동시에 재연결해 최근 위치 40건씩 몰아 보내도 커넥션 대기로 실패하지 않고 원본이 모두 남는다")
    void 여러_회원이_동시에_재연결해도_실패하지_않는다() throws Exception {
        Result result = burst("multi-20x40", 20, 40, 5_000L);

        assertThat(result.failed()).isZero();
        assertThat(result.storedRows()).isEqualTo(result.sent());
    }

    @Test
    @DisplayName("회원 20명이 1시간 넘게 밀린 위치 40건씩 몰아 보내도 커넥션 대기로 실패하지 않고 원본이 모두 남는다")
    void 오래_밀린_위치가_여러_회원에게서_몰려도_실패하지_않는다() throws Exception {
        Result result = burst("multi-20x40-stale", 20, 40, 90_000L);

        assertThat(result.failed()).isZero();
        assertThat(result.storedRows()).isEqualTo(result.sent());
    }

    /**
     * 회원마다 지금부터 거꾸로 {@code intervalMs} 간격으로 잰 위치를 만들어, 모든 회원의 위치를 한꺼번에 16개 스레드에 던진다.
     * 앱이 쌓아 둔 순서(오래된 것부터)로 제출하고, 처리 결과와 저장된 원본 행 수를 센다.
     */
    private Result burst(String label, int members, int fixesPerMember, long intervalMs) throws Exception {
        List<Long> memberIds = new ArrayList<>();
        for (int i = 0; i < members; i++) {
            memberIds.add(saveSenior());
        }
        long now = System.currentTimeMillis();
        List<Object[]> work = new ArrayList<>();
        for (int k = 0; k < fixesPerMember; k++) {
            for (Long memberId : memberIds) {
                long tsMs = now - (long) (fixesPerMember - k) * intervalMs;
                work.add(new Object[]{memberId, payload(memberId, k, tsMs)});
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> failures = new TreeMap<>();
        List<Future<String>> futures = new ArrayList<>();
        for (Object[] item : work) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    realtimeLocationService.updateAndBroadcast((byte[]) item[1], (Long) item[0]);
                    return null;
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                }
            }));
        }
        long startedAt = System.nanoTime();
        start.countDown();
        for (Future<String> future : futures) {
            String failure = future.get(5, TimeUnit.MINUTES);
            if (failure != null) {
                failures.merge(failure, 1, Integer::sum);
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        pool.shutdown();

        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM location_fix WHERE member_id IN (" + join(memberIds) + ")", Integer.class);
        int failed = failures.values().stream().mapToInt(Integer::intValue).sum();
        Result result = new Result(work.size(), failed, stored, elapsedMs);
        System.out.printf("[LOCATION-BURST] scenario=%s threads=%d pool=10 sent=%d failed=%d failures=%s "
                        + "storedRows=%d elapsedMs=%d%n",
                label, THREADS, result.sent(), failed, failures, stored, elapsedMs);
        return result;
    }

    private Long saveSenior() {
        int n = SEQ.incrementAndGet();
        Family family = familyRepository.save(Family.createFamily("LB" + String.format("%04d", n)));
        Member member = memberRepository.save(
                Member.createMember(MemberType.SENIOR, "시니어" + n, "019" + String.format("%08d", n)));
        seniorProfileRepository.save(SeniorProfile.createSeniorProfile(
                member, family, "서울", "LBI" + String.format("%04d", n), LocalDate.of(1950, 1, 1)));
        return member.getId();
    }

    private byte[] payload(Long memberId, int seq, long tsMs) {
        return """
                {"v":2,"memberId":%d,"device_id":"lb-%d","session_id":"s-burst","seq":%d,
                 "lat":37.55,"lon":126.97,"accuracy_m":8.0,"speed_mps":1.0,"provider":"fused",
                 "is_mock":false,"ts_ms":%d,"reason":"move"}""".formatted(memberId, memberId, seq, tsMs)
                .getBytes(UTF_8);
    }

    private static String join(List<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private record Result(int sent, int failed, int storedRows, long elapsedMs) {
    }
}
