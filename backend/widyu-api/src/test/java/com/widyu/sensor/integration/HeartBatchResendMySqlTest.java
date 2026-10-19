package com.widyu.sensor.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockingDetails;

import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.application.FcmDelivery;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.websocket.WebSocketExceptionHandler;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.application.HeartEventConflict;
import com.widyu.heart.application.HeartRateAnomalyDetector;
import com.widyu.heart.application.HeartRateAnomalyDetector.DetectionResult;
import com.widyu.heart.application.HeartRateService;
import com.widyu.heart.dto.request.HeartRateMeasurement;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.dto.response.HeartRateStatusResponse;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.sensor.controller.SensorBatchWebSocketController;
import com.widyu.sensor.dto.response.SensorBatchResultResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 같은 심박 배치·단건을 다시 보냈을 때 행·AI 호출·알림·응답이 몇 번 생기는지 실제 MySQL에서 잰다(#777, LLD-0089).
 *
 * <p>처리 중 동시 재전송도 실패 응답 없이 STORED 1건과 DUPLICATE로 끝나야 한다(LLD-0089 AC1~AC5).
 * AI 호출 수는 줄이지 않는 것이 의도라 출력만 한다.
 *
 * <p>운영 코드는 그대로 쓰고 외부만 대역으로 둔다. AI 판정기는 호출을 세고 bpm 150 이상을 위급으로 돌려주며,
 * 첫 요청을 AI 호출 안에 붙잡아 두는 래치로 겹침을 결정적으로 만든다. S3는 호출만 기록하고 FCM 전송은 성공을 돌려준다.
 * Redis는 일회용 인스턴스를 쓴다(최신값 갱신은 측정 대상이 아니다).
 *
 * <p>실행: {@code HEART_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/heart_resend_<name> HEART_REDIS_PORT=<port>
 * DB_USERNAME=root DB_PASSWORD= [HEART_R=20] [HEART_C=5] [HEART_K=5] ./gradlew :backend:widyu-api:test
 * --tests 'com.widyu.sensor.integration.HeartBatchResendMySqlTest'}. 결과는 {@code [MEASURE]} 줄로 출력한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.maximum-pool-size=24",
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
@EnabledIfEnvironmentVariable(named = "HEART_MYSQL_URL", matches = ".+")
@Tag("heart-mysql")
@DisplayName("심박 재전송 멱등성 MySQL 측정")
class HeartBatchResendMySqlTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int EMERGENCY_BPM = 150;
    /** 위급 샘플의 위치(0부터). 샘플 5개 중 AI 대상은 4개(UNRELIABLE 1개 제외)다. */
    private static final int EMERGENCY_INDEX = 2;
    private static final int[] BPMS = {72, 75, 182, 0, 78};
    private static final String[] ACCURACIES = {"HIGH", "HIGH", "HIGH", "UNRELIABLE", "MEDIUM"};
    private static final int AI_TARGET_SAMPLES = 4;

    @MockBean private HeartRateAnomalyDetector detector;
    @MockBean private FcmTransport transport;
    @MockBean private S3Service s3Service;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private SensorBatchWebSocketController sensorBatchController;
    @Autowired private HeartRateService heartRateService;
    @Autowired private GlobalExceptionHandler restExceptionHandler;
    @Autowired private WebSocketExceptionHandler webSocketExceptionHandler;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MemberRepository members;
    @Autowired private FamilyRepository families;
    @Autowired private SeniorProfileRepository seniors;
    @Autowired private FamilyMembershipRepository memberships;
    @Autowired private MemberFcmTokenRepository tokens;
    @Autowired private CollectionRunRepository runs;
    @Autowired private RunDeviceAssignmentRepository assignments;
    @Autowired private HeartRateEventRepository heartEvents;
    @Autowired private HeartRateEmergencyRepository heartEmergencies;

    private final AtomicReference<Hold> hold = new AtomicReference<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("HEART_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heart_resend_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("루프백의 일회용 heart_resend_ 데이터베이스만 허용합니다.");
        }
        String redisPort = System.getenv("HEART_REDIS_PORT");
        if (redisPort == null || !redisPort.matches("[0-9]+")) {
            throw new IllegalArgumentException("일회용 Redis 포트(HEART_REDIS_PORT)가 필요합니다. 기존 6379를 쓰지 않습니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> envOr("DB_USERNAME", "root"));
        registry.add("spring.datasource.password", () -> envOr("DB_PASSWORD", ""));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @Test
    @DisplayName("처리가 끝난 배치를 순차 재전송하면 DUPLICATE를 받고 행·AI 호출·알림이 늘지 않는다")
    void 순차_재전송은_DUPLICATE이고_부수효과가_한번이다() throws Exception {
        // given
        printDatabaseEvidence();
        stubAi();
        stubFcm();
        int r = envInt("HEART_R", 20);
        int k = envInt("HEART_K", 5);
        Map<String, Integer> firstOutcomes = new TreeMap<>();
        Map<String, Integer> resendOutcomes = new TreeMap<>();
        Totals totals = new Totals(AI_TARGET_SAMPLES);

        for (int i = 0; i < r; i++) {
            Senior senior = seedSenior();
            byte[] payload = senior.payload();
            double noAlertBefore = noAlertCount();

            // when
            count(firstOutcomes, sendBatch(senior.memberId(), payload));
            for (int j = 0; j < k; j++) {
                count(resendOutcomes, sendBatch(senior.memberId(), payload));
            }

            // then
            Counts counts = counts(senior, noAlertBefore);
            totals.add(counts);
            System.out.println("[REP] scenario=S1 rep=" + i + " " + counts);
            assertThat(counts.events()).isEqualTo(BPMS.length);
            assertThat(counts.batches()).isEqualTo(1);
            assertThat(counts.emergencies()).isEqualTo(1);
            assertThat(counts.decisions()).isEqualTo(1);
            assertThat(counts.incidents()).isEqualTo(1);
            assertThat(counts.outbox()).isEqualTo(1);
            assertThat(counts.noAlertMetric()).isEqualTo(AI_TARGET_SAMPLES - 1);
        }
        System.out.println("[MEASURE] scenario=S1_sequential_resend R=" + r + " K=" + k
                + " first=" + firstOutcomes + " resend=" + resendOutcomes + " " + totals);
        assertThat(firstOutcomes).isEqualTo(Map.of("ACK:STORED", r));
        assertThat(resendOutcomes).isEqualTo(Map.of("ACK:DUPLICATE", r * k));
    }

    @Test
    @DisplayName("첫 요청이 AI 판정 중일 때 같은 배치를 동시에 다시 보내면 실패 없이 STORED 하나와 DUPLICATE를 받는다")
    void 처리중_동시_재전송을_첫_샘플에서_겹친다() throws Exception {
        // given
        printDatabaseEvidence();
        stubAi();
        stubFcm();
        measureConcurrentResend("S2a_hold_first_sample", null, envInt("HEART_C", 5));
    }

    @Test
    @DisplayName("첫 요청이 위급 샘플을 판정하는 중에 같은 배치를 다시 보내면 위급 알림과 판정 행은 한 번만 남는다")
    void 처리중_동시_재전송을_위급_샘플에서_겹친다() throws Exception {
        // given
        printDatabaseEvidence();
        stubAi();
        stubFcm();
        measureConcurrentResend("S2b_hold_emergency_sample", EMERGENCY_INDEX, 1);
    }

    @Test
    @DisplayName("단건 심박을 같은 측정 시각으로 순차·동시에 다시 보내면 행은 한 번이고 두 요청 모두 정상 응답을 받는다")
    void 단건_경로_재전송을_측정한다() throws Exception {
        // given
        printDatabaseEvidence();
        stubAi();
        stubFcm();
        int r = envInt("HEART_R", 20);
        Map<String, Integer> sequentialOutcomes = new TreeMap<>();
        Map<String, Integer> concurrentFirst = new TreeMap<>();
        Map<String, Integer> concurrentSecond = new TreeMap<>();
        Totals sequentialTotals = new Totals(1);
        Totals concurrentTotals = new Totals(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < r; i++) {
                // when: 순차
                Senior sequential = seedSenior();
                LocalDateTime measuredAt = toSeoul(sequential.baseTsMs());
                double before = noAlertCount();
                count(sequentialOutcomes, sendSingle(sequential.memberId(), measuredAt));
                count(sequentialOutcomes, sendSingle(sequential.memberId(), measuredAt));
                Counts sequentialCounts = counts(sequential, before);
                sequentialTotals.add(sequentialCounts);
                System.out.println("[REP] scenario=S3_single_sequential rep=" + i + " " + sequentialCounts);

                // when: 첫 요청을 AI 안에 붙잡은 채 같은 시각을 다시 보낸다
                Senior concurrent = seedSenior();
                LocalDateTime sameAt = toSeoul(concurrent.baseTsMs());
                before = noAlertCount();
                Hold held = Hold.of(concurrent.memberId(), null);
                hold.set(held);
                Future<String> first = executor.submit(() -> sendSingle(concurrent.memberId(), sameAt));
                assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
                String second = sendSingle(concurrent.memberId(), sameAt);
                held.release().countDown();
                count(concurrentFirst, first.get(30, TimeUnit.SECONDS));
                count(concurrentSecond, second);
                Counts concurrentCounts = counts(concurrent, before);
                concurrentTotals.add(concurrentCounts);
                System.out.println("[REP] scenario=S3_single_concurrent rep=" + i + " " + concurrentCounts);

                // then
                assertThat(sequentialCounts.events()).isEqualTo(1);
                assertThat(sequentialCounts.emergencies()).isEqualTo(1);
                assertThat(concurrentCounts.events()).isEqualTo(1);
                assertThat(concurrentCounts.emergencies()).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.println("[MEASURE] scenario=S3_single_sequential R=" + r + " outcomes=" + sequentialOutcomes
                + " " + sequentialTotals);
        System.out.println("[MEASURE] scenario=S3_single_concurrent R=" + r + " heldFirst=" + concurrentFirst
                + " overlappingSecond=" + concurrentSecond + " " + concurrentTotals);
        String ok = "OK:" + HeartRateStatusResponse.class.getSimpleName();
        assertThat(sequentialOutcomes).isEqualTo(Map.of(ok, 2 * r));
        assertThat(concurrentFirst).isEqualTo(Map.of(ok, r));
        assertThat(concurrentSecond).isEqualTo(Map.of(ok, r));
    }

    @Test
    @DisplayName("MySQL이 보고한 제약 이름으로 심박 행 UK 충돌과 위급 행 UK 충돌을 가른다")
    void MySQL_제약_이름으로_심박_UK_충돌을_가른다() throws Exception {
        // given
        printDatabaseEvidence();
        Senior senior = seedSenior();
        LocalDateTime measuredAt = toSeoul(senior.baseTsMs());

        // when
        DataIntegrityViolationException eventError = insertTwice(() -> heartEvents.save(HeartRateEvent.of(
                members.getReferenceById(senior.memberId()), 72, measuredAt, HeartRateStatus.NORMAL, "HIGH", null)));
        DataIntegrityViolationException emergencyError = insertTwice(() -> heartEmergencies.save(
                HeartRateEmergency.of(members.getReferenceById(senior.memberId()), 182, measuredAt, null)));

        // then
        System.out.println("[MEASURE] scenario=constraint_name heartEvent=" + constraintName(eventError)
                + " heartEmergency=" + constraintName(emergencyError));
        // 두 UK 모두 (member_id, measured_at)이다. 이름으로만 심박 행 충돌을 가린다(#789).
        assertThat(HeartEventConflict.violatesMemberTimeUk(eventError)).isTrue();
        assertThat(HeartEventConflict.violatesMemberTimeUk(emergencyError)).isFalse();
    }

    /** 같은 행을 트랜잭션 둘로 나눠 두 번 저장하고 두 번째의 무결성 예외를 돌려준다. */
    private DataIntegrityViolationException insertTwice(Runnable insert) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> insert.run());
        try {
            transaction.executeWithoutResult(status -> insert.run());
        } catch (DataIntegrityViolationException e) {
            return e;
        }
        throw new AssertionError("UK 충돌이 나지 않았습니다.");
    }

    private static String constraintName(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    /**
     * 첫 요청 A를 {@code holdIndex} 샘플의 AI 호출 안에 붙잡고(null이면 첫 AI 호출) 사본 C개를 동시에 보낸다.
     * 사본이 모두 끝난 뒤 A를 풀어 준다. 사본끼리는 실제로 경합한다.
     */
    private void measureConcurrentResend(String scenario, Integer holdIndex, int c) throws Exception {
        int r = envInt("HEART_R", 20);
        Map<String, Integer> heldOutcomes = new TreeMap<>();
        Map<String, Integer> copyOutcomes = new TreeMap<>();
        Totals totals = new Totals(AI_TARGET_SAMPLES);
        ExecutorService executor = Executors.newFixedThreadPool(c + 1);
        try {
            for (int i = 0; i < r; i++) {
                Senior senior = seedSenior();
                byte[] payload = senior.payload();
                double noAlertBefore = noAlertCount();
                LocalDateTime holdAt = null;
                if (holdIndex != null) {
                    holdAt = toSeoul(senior.baseTsMs() + holdIndex * 1000L);
                }
                Hold held = Hold.of(senior.memberId(), holdAt);
                hold.set(held);

                // when
                Future<String> first = executor.submit(() -> sendBatch(senior.memberId(), payload));
                assertThat(held.entered().await(10, TimeUnit.SECONDS)).isTrue();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> copies = new ArrayList<>();
                List<String> repOutcomes = new ArrayList<>();
                for (int j = 0; j < c; j++) {
                    copies.add(executor.submit(() -> {
                        start.await();
                        return sendBatch(senior.memberId(), payload);
                    }));
                }
                start.countDown();
                for (Future<String> copy : copies) {
                    String outcome = copy.get(30, TimeUnit.SECONDS);
                    count(copyOutcomes, outcome);
                    repOutcomes.add(outcome);
                }
                held.release().countDown();
                String heldOutcome = first.get(30, TimeUnit.SECONDS);
                count(heldOutcomes, heldOutcome);
                repOutcomes.add(heldOutcome);

                // then
                Counts counts = counts(senior, noAlertBefore);
                totals.add(counts);
                System.out.println("[REP] scenario=" + scenario + " rep=" + i + " " + counts);
                assertThat(counts.events()).isEqualTo(BPMS.length);
                assertThat(counts.batches()).isEqualTo(1);
                assertThat(counts.emergencies()).isEqualTo(1);
                assertThat(counts.decisions()).isEqualTo(1);
                assertThat(counts.incidents()).isEqualTo(1);
                assertThat(counts.outbox()).isEqualTo(1);
                // 배치 행 UK가 승자를 하나로 정한다. 나머지는 이미 저장된 배치라 DUPLICATE다(LLD-0089 AC2·AC3).
                assertThat(repOutcomes).containsOnly("ACK:STORED", "ACK:DUPLICATE");
                assertThat(Collections.frequency(repOutcomes, "ACK:STORED")).isEqualTo(1);
                // 저장된 샘플만 센다. AI 대상 4개 중 위급 1개를 뺀 3개다(LLD-0089 AC5).
                assertThat(counts.noAlertMetric()).isEqualTo(AI_TARGET_SAMPLES - 1);
            }
        } finally {
            executor.shutdownNow();
        }
        System.out.println("[MEASURE] scenario=" + scenario + " R=" + r + " C=" + c
                + " heldFirst=" + heldOutcomes + " copies=" + copyOutcomes + " " + totals);
    }

    /** 운영 WebSocket 컨트롤러를 그대로 부른다. 컨트롤러 밖으로 나온 예외는 REST·WebSocket 처리기가 무엇을 돌려주는지로 바꾼다. */
    private String sendBatch(Long memberId, byte[] payload) {
        SimpMessageHeaderAccessor accessor = accessor(memberId);
        try {
            SensorBatchResultResponse response = sensorBatchController.ingestBatch(
                    MessageBuilder.withPayload(payload).build(), accessor);
            return "ACK:" + response.result();
        } catch (Exception e) {
            return surface(e, accessor);
        }
    }

    private String sendSingle(Long memberId, LocalDateTime measuredAt) {
        SimpMessageHeaderAccessor accessor = accessor(memberId);
        try {
            return "OK:" + heartRateService.processHeartRate(
                    memberId, HeartRateSingleRequest.of(182, measuredAt, "서울시", "ACTIVE")).getClass().getSimpleName();
        } catch (Exception e) {
            return surface(e, accessor);
        }
    }

    private String surface(Exception e, SimpMessageHeaderAccessor accessor) {
        String rest = "unmapped";
        if (e instanceof DataIntegrityViolationException integrity) {
            ResponseEntity<ApiResponseTemplate<Void>> response = restExceptionHandler
                    .handleDataIntegrityViolationException(integrity, new MockHttpServletRequest("POST", "/api/v1/sensor/batches"));
            rest = response.getStatusCode().value() + "/" + response.getBody().getCode();
        }
        Map<String, String> ws = webSocketExceptionHandler.handleException(e, null, accessor);
        String wsLevel = "WARN";
        if (ws.get("message").equals(com.widyu.global.error.ErrorCode.INTERNAL_SERVER_ERROR.getMessage())) {
            wsLevel = "ERROR[WS_EXCEPTION]";
        }
        return "EXC:" + e.getClass().getSimpleName() + "|REST " + rest + "|WS /user/queue/errors " + wsLevel;
    }

    private SimpMessageHeaderAccessor accessor(Long memberId) {
        PrincipalDetails principal = new PrincipalDetails(memberId, MemberRole.USER);
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("hr-777-" + memberId);
        accessor.setDestination("/app/sensor/batches/send");
        accessor.setUser(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        return accessor;
    }

    private void stubAi() {
        given(detector.detect(any(), any(), any())).willAnswer(invocation -> {
            Long memberId = invocation.getArgument(0);
            HeartRateMeasurement measurement = invocation.getArgument(1);
            Hold held = hold.get();
            if (held != null && held.matches(memberId, measurement.measuredAt()) && hold.compareAndSet(held, null)) {
                held.entered().countDown();
                held.release().await(30, TimeUnit.SECONDS);
            }
            if (measurement.heartRate() >= EMERGENCY_BPM) {
                return new DetectionResult(HeartRateStatus.EMERGENCY, true, "EMERGENCY", "TACHYCARDIA");
            }
            return new DetectionResult(HeartRateStatus.NORMAL, false, "NORMAL", null);
        });
    }

    private void stubFcm() {
        given(transport.send(any(FcmDelivery.class), any())).willReturn(FcmTransport.Result.delivered());
    }

    /** 시니어 1명, 방장 보호자 1명(활성 토큰 1개), 열린 회차와 워치 배정. 회차가 있어야 위급 판정 행이 생긴다. */
    private Senior seedSenior() {
        int seq = SEQ.incrementAndGet();
        long baseTsMs = System.currentTimeMillis() - 60_000L;
        String deviceId = "watch-777-" + seq;
        Long memberId = new TransactionTemplate(transactionManager).execute(status -> {
            Family family = families.save(Family.createFamily(String.format("H%05d", seq)));
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어" + seq, phone(seq, 0)));
            seniors.save(SeniorProfile.createSeniorProfile(senior, family, "서울시", String.format("J%06d", seq),
                    LocalDate.of(1950, 1, 1)));
            Member guardian = members.save(Member.createMember(MemberType.GUARDIAN, "보호자" + seq, phone(seq, 1)));
            memberships.save(FamilyMembership.createLeaderMembership(family, guardian));
            tokens.save(MemberFcmToken.builder().member(guardian).token("synthetic-777-" + seq).active(true).build());
            CollectionRun run = runs.save(CollectionRun.builder()
                    .runId("run-777-" + seq).member(senior).studyId("STUDY-777").participationId("P-777")
                    .protocolRef("PROTO-777").consentVersion("v1").collectionMode("research")
                    .startedAtMs(baseTsMs - 10_000L).status(CollectionRunStatus.OPEN).build());
            assignments.save(RunDeviceAssignment.builder()
                    .assignmentId("asg-777-" + seq).run(run).deviceId(deviceId).role("WATCH")
                    .wearSite("LEFT_WRIST").assignedAtMs(baseTsMs - 10_000L).build());
            return senior.getId();
        });
        return new Senior(memberId, String.format("hr777%021d", seq), deviceId, "cm-777-" + seq, baseTsMs);
    }

    private Counts counts(Senior senior, double noAlertBefore) {
        long aiCalls = mockingDetails(detector).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("detect"))
                .filter(invocation -> senior.memberId().equals(invocation.getArgument(0)))
                .count();
        long s3Uploads = mockingDetails(s3Service).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("uploadBytes"))
                .filter(invocation -> ((String) invocation.getArgument(0)).contains(senior.batchId()))
                .count();
        return new Counts(
                query("SELECT COUNT(*) FROM heart_rate_event WHERE member_id = ?", senior.memberId()),
                query("SELECT COUNT(*) FROM sensor_batch WHERE batch_id = ?", senior.batchId()),
                query("SELECT COUNT(*) FROM heart_rate_emergency WHERE member_id = ?", senior.memberId()),
                query("SELECT COUNT(*) FROM decision_record WHERE member_id = ?", senior.memberId()),
                query("SELECT COUNT(*) FROM incident WHERE member_id = ?", senior.memberId()),
                query("SELECT COUNT(*) FROM fcm_outbox WHERE related_member_id = ?", senior.memberId()),
                aiCalls,
                s3Uploads,
                Math.round(noAlertCount() - noAlertBefore));
    }

    private long query(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private double noAlertCount() {
        return meterRegistry.counter("heart.decision", "output", "NO_ALERT").count();
    }

    private void printDatabaseEvidence() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            System.out.println("[MEASURE] env mysql=" + connection.getMetaData().getDatabaseProductVersion()
                    + " isolation=" + jdbc.queryForObject("SELECT @@transaction_isolation", String.class)
                    + " lockWaitTimeoutSec=" + jdbc.queryForObject("SELECT @@innodb_lock_wait_timeout", String.class)
                    + " jdk=" + System.getProperty("java.version"));
        }
    }

    private static void count(Map<String, Integer> outcomes, String outcome) {
        synchronized (outcomes) {
            outcomes.merge(outcome, 1, Integer::sum);
        }
    }

    private static LocalDateTime toSeoul(long tsMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), SEOUL);
    }

    private static String phone(int seq, int slot) {
        return String.format("011%07d%d", seq, slot);
    }

    private static int envInt(String name, int fallback) {
        return Integer.parseInt(envOr(name, String.valueOf(fallback)));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private record Senior(Long memberId, String batchId, String deviceId, String clockMappingId, long baseTsMs) {

        /** 같은 배치를 다시 보내므로 바이트가 매번 같아야 한다(원문 해시 대조). */
        byte[] payload() {
            StringBuilder samples = new StringBuilder("[");
            for (int i = 0; i < BPMS.length; i++) {
                if (i > 0) {
                    samples.append(',');
                }
                samples.append("{\"bpm\":").append(BPMS[i]).append(",\"ts_ms\":").append(baseTsMs + i * 1000L)
                        .append(",\"accuracy\":\"").append(ACCURACIES[i]).append("\"}");
            }
            samples.append(']');
            String json = """
                    {"v":2,"stream":"hr","batch_id":"%s","device_id":"%s","session_id":"s777","seq":1,\
                    "study_id":null,"participation_id":null,"run_id":null,\
                    "clock":{"boot_id":"b777","clock_mapping_id":"%s","anchor_elapsed_ns":"993847100000001",\
                    "anchor_epoch_ms":%d,"uncertainty_ms":2.0},\
                    "samples":%s,"on_body":true,"watch_battery_pct":63,"resend":null}"""
                    .formatted(batchId, deviceId, clockMappingId, baseTsMs, samples);
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 이 회원의 첫 AI 호출(measuredAt이 있으면 그 시각의 호출)을 붙잡는다. 한 번만 동작한다. */
    private record Hold(Long memberId, LocalDateTime measuredAt, CountDownLatch entered, CountDownLatch release) {

        static Hold of(Long memberId, LocalDateTime measuredAt) {
            return new Hold(memberId, measuredAt, new CountDownLatch(1), new CountDownLatch(1));
        }

        boolean matches(Long candidateMemberId, LocalDateTime candidateMeasuredAt) {
            if (!memberId.equals(candidateMemberId)) {
                return false;
            }
            return measuredAt == null || measuredAt.equals(candidateMeasuredAt);
        }
    }

    private record Counts(long events, long batches, long emergencies, long decisions, long incidents, long outbox,
                          long aiCalls, long s3Uploads, long noAlertMetric) {

        @Override
        public String toString() {
            return "events=" + events + " sensorBatch=" + batches + " emergency=" + emergencies
                    + " decision=" + decisions + " incident=" + incidents + " outbox=" + outbox
                    + " aiCalls=" + aiCalls + " s3Uploads=" + s3Uploads + " noAlertMetric=" + noAlertMetric;
        }
    }

    /** 반복 합계와 AI 호출 최소·최대(변동 확인용). */
    private static final class Totals {
        private final List<Counts> reps = new ArrayList<>();
        private final int expectedAiPerRep;

        Totals(int expectedAiPerRep) {
            this.expectedAiPerRep = expectedAiPerRep;
        }

        void add(Counts counts) {
            reps.add(counts);
        }

        private long sum(java.util.function.ToLongFunction<Counts> field) {
            return reps.stream().mapToLong(field).sum();
        }

        @Override
        public String toString() {
            Supplier<String> aiRange = () -> reps.stream().mapToLong(Counts::aiCalls).min().orElse(0)
                    + ".." + reps.stream().mapToLong(Counts::aiCalls).max().orElse(0);
            return "totals{events=" + sum(Counts::events) + " sensorBatch=" + sum(Counts::batches)
                    + " emergency=" + sum(Counts::emergencies) + " decision=" + sum(Counts::decisions)
                    + " incident=" + sum(Counts::incidents) + " outbox=" + sum(Counts::outbox)
                    + " aiCalls=" + sum(Counts::aiCalls) + " aiCallsPerRep=" + aiRange.get()
                    + " expectedAiPerRep=" + expectedAiPerRep
                    + " s3Uploads=" + sum(Counts::s3Uploads) + " noAlertMetric=" + sum(Counts::noAlertMetric) + "}";
        }
    }
}
