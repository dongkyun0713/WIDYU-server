package com.widyu.pay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.global.crypto.AesGcmStringConverter;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.*;
import com.widyu.pay.application.PaymentService;
import com.widyu.pay.application.PaymentTransactionService;
import com.widyu.pay.dto.request.CancelRequest;
import com.widyu.pay.dto.request.PaymentApproveRequest;
import com.widyu.pay.dto.request.PaymentOrderCreateRequest;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.repository.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = PaymentMySqlResilienceTest.Config.class, properties = {
        "spring.config.location=optional:classpath:/payment-resilience-empty.properties",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.maximum-pool-size=6",
        "widyu.encryption.aes-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@EnabledIfEnvironmentVariable(named = "PAYMENT_MYSQL_URL", matches = ".+")
@Tag("payment-mysql")
class PaymentMySqlResilienceTest {
    @Autowired PaymentTransactionService transactions;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOrderRepository orders;
    @Autowired MemberRepository members;
    @Autowired SeniorProfileRepository seniors;
    @Autowired FamilyRepository families;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = System.getenv("PAYMENT_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/payment_resilience_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Requires loopback and a disposable payment_resilience_ database");
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @Test
    @DisplayName("동일 승인을 동시에 요청하면 PG 논리 승인과 DB 적립을 한 번 반영한다")
    void 동시_승인은_한번_반영한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
                System.out.println("MYSQL_EVIDENCE version=" + connection.getMetaData().getDatabaseProductVersion()
                        + " isolation=" + connection.getTransactionIsolation());
            }
            scenario.gateway.overlapNextTwoPosts();
            // when
            List<PaymentConfirmResponse> responses = concurrent(scenario::approve);
            // then
            assertThat(responses).extracting(PaymentConfirmResponse::getPaymentKey)
                    .containsOnly(scenario.paymentKey);
            assertThat(scenario.gateway.postKeys()).hasSize(2);
            assertThat(scenario.gateway.postKeys().stream().distinct()).hasSize(1);
            assertThat(scenario.gateway.operations()).isEqualTo(1);
            scenario.assertState("PAID", "DONE", 0, 10100, 1, 0);
            scenario.approve();
            assertThat(scenario.gateway.postKeys()).hasSize(2);
        }
    }

    @Test
    @DisplayName("동일 부분 취소를 동시에 요청하면 환불과 포인트 환수를 한 번 반영한다")
    void 동시_부분취소는_한번_반영한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            scenario.approve();
            scenario.gateway.overlapNextTwoPosts();
            // when
            concurrent(scenario::cancel);
            // then
            assertThat(scenario.gateway.postKeys()).hasSize(3);
            assertThat(scenario.gateway.postKeys().subList(1, 3).stream().distinct()).hasSize(1);
            assertThat(scenario.gateway.operations()).isEqualTo(2);
            scenario.assertState("PAID", "PARTIAL_CANCELED", 3000, 7100, 2, 1);
            scenario.cancel();
            assertThat(scenario.gateway.postKeys()).hasSize(3);
        }
    }

    @Test
    @DisplayName("승인 응답이 유실되면 처리중 상태를 보존하고 PG 조회로 적립을 복구한다")
    void 승인_응답유실을_복구한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            scenario.gateway.loseNextResponse();
            // when
            assertThatThrownBy(scenario::approve).isInstanceOf(RuntimeException.class);
            // then
            scenario.assertApprovalPending();
            scenario.recover();
            scenario.approve();
            scenario.assertState("PAID", "DONE", 0, 10100, 1, 0);
            assertThat(scenario.gateway.operations()).isEqualTo(1);
            assertThat(scenario.gateway.postKeys()).hasSize(1);
            assertThat(scenario.gateway.lookups()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("부분 취소 응답이 유실되면 예약 포인트를 유지하고 조회로 취소를 확정한다")
    void 부분취소_응답유실을_복구한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            scenario.approve();
            scenario.gateway.loseNextResponse();
            // when
            assertThatThrownBy(scenario::cancel).isInstanceOf(RuntimeException.class);
            // then
            scenario.assertCancellationPending();
            scenario.recover();
            scenario.cancel();
            scenario.assertState("PAID", "PARTIAL_CANCELED", 3000, 7100, 2, 1);
            assertThat(scenario.gateway.operations()).isEqualTo(2);
            assertThat(scenario.gateway.postKeys()).hasSize(2);
            assertThat(scenario.gateway.lookups()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("PG 승인 후 포인트 이력 SQL이 거절되면 결제 반영을 롤백하고 복구한다")
    void 승인_DB반영_롤백후_복구한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            jdbc.execute("CREATE TRIGGER resilience_approval BEFORE INSERT ON point_history FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resilience injected approval rollback'");
            // when
            try {
                assertThatThrownBy(scenario::approve).isInstanceOf(RuntimeException.class)
                        .hasStackTraceContaining("resilience injected approval rollback");
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS resilience_approval");
            }
            // then
            scenario.assertApprovalPending();
            scenario.recover();
            scenario.approve();
            scenario.assertState("PAID", "DONE", 0, 10100, 1, 0);
            assertThat(scenario.gateway.operations()).isEqualTo(1);
            assertThat(scenario.gateway.postKeys()).hasSize(1);
        }
    }

    @Test
    @DisplayName("PG 취소 후 결제 SQL이 거절되면 취소 확정을 롤백하고 예약을 유지해 복구한다")
    void 취소_DB반영_롤백후_복구한다() throws Exception {
        // given
        try (Scenario scenario = scenario()) {
            scenario.approve();
            jdbc.execute("CREATE TRIGGER resilience_cancel BEFORE UPDATE ON payment FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'resilience injected cancellation rollback'");
            // when
            try {
                assertThatThrownBy(scenario::cancel).isInstanceOf(RuntimeException.class)
                        .hasStackTraceContaining("resilience injected cancellation rollback");
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS resilience_cancel");
            }
            // then
            scenario.assertCancellationPending();
            scenario.recover();
            scenario.cancel();
            scenario.assertState("PAID", "PARTIAL_CANCELED", 3000, 7100, 2, 1);
            assertThat(scenario.gateway.operations()).isEqualTo(2);
            assertThat(scenario.gateway.postKeys()).hasSize(2);
        }
    }

    private List<PaymentConfirmResponse> concurrent(Callable<PaymentConfirmResponse> action) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(action);
            var second = executor.submit(action);
            return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Scenario scenario() throws Exception {
        LocalPaymentGateway gateway = new LocalPaymentGateway();
        try {
            String suffix = UUID.randomUUID().toString().replace("-", "");
            Member member = new TransactionTemplate(transactionManager).execute(status -> {
                Family family = families.save(Family.createFamily(suffix.substring(0, 6)));
                Member saved = members.save(Member.createMember(MemberType.SENIOR, "synthetic", suffix.substring(0, 11)));
                SeniorProfile senior = seniors.save(SeniorProfile.createSeniorProfile(saved, family,
                        "synthetic", suffix.substring(0, 7), LocalDate.of(1950, 1, 1)));
                ReflectionTestUtils.setField(saved, "seniorProfile", senior);
                return saved;
            });
            MemberUtil identity = mock(MemberUtil.class);
            given(identity.getCurrentMember()).willReturn(member);
            PaymentService target = new PaymentService(gateway.client(), payments, orders, identity, transactions);
            var proxy = new org.springframework.aop.framework.ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    transactionManager, new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            PaymentService service = (PaymentService) proxy.getProxy();
            String orderId = service.createOrder(new PaymentOrderCreateRequest("POINT_10000")).orderId();
            return new Scenario(gateway, service, member.getId(), orderId, "synthetic_" + suffix);
        } catch (Exception | AssertionError e) {
            gateway.close();
            throw e;
        }
    }

    private final class Scenario implements AutoCloseable {
        private final LocalPaymentGateway gateway;
        private final PaymentService service;
        private final long memberId;
        private final String orderId;
        private final String paymentKey;

        private Scenario(LocalPaymentGateway gateway, PaymentService service, long memberId,
                         String orderId, String paymentKey) {
            this.gateway = gateway;
            this.service = service;
            this.memberId = memberId;
            this.orderId = orderId;
            this.paymentKey = paymentKey;
        }

        PaymentConfirmResponse approve() {
            return service.confirmPayment(new PaymentApproveRequest(orderId, paymentKey));
        }

        PaymentConfirmResponse cancel() {
            return service.cancelPayment(paymentKey, CancelRequest.of("synthetic refund", 3000, "partial-1"));
        }

        void recover() {
            // Drive the actual recovery selector without waiting for the production backoff interval.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                var order = orders.findByOrderIdForUpdate(orderId).orElseThrow();
                ReflectionTestUtils.setField(order, "approvalNextRetryAt", java.time.ZonedDateTime.now().minusMinutes(1));
                payments.findByPaymentKeyForUpdate(paymentKey).ifPresent(payment ->
                        payment.getCancellations().forEach(cancel -> ReflectionTestUtils.setField(
                                cancel, "nextRetryAt", java.time.ZonedDateTime.now().minusMinutes(1))));
            });
            long started = System.nanoTime();
            service.recoverPendingPayments();
            service.recoverPendingPayments();
            System.out.println("RECOVERY_EVIDENCE elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " logicalOperations=" + gateway.operations() + " postAttempts=" + gateway.postKeys().size());
        }

        void assertApprovalPending() {
            assertThat(gateway.operations()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE order_id = ?", String.class, orderId))
                    .isEqualTo("APPROVING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId)).isZero();
            assertPoints(100, 0);
            assertThat(jdbc.queryForObject("SELECT approval_retry_count FROM payment_order WHERE order_id = ?",
                    Integer.class, orderId)).isEqualTo(1);
        }

        void assertCancellationPending() {
            assertThat(gateway.operations()).isEqualTo(2);
            assertState("PAID", "DONE", 0, 7100, 2, 1);
            assertThat(jdbc.queryForObject("SELECT c.status FROM payment_cancel c JOIN payment p ON p.id=c.payment_id "
                    + "WHERE p.payment_key=?", String.class, paymentKey)).isEqualTo("PENDING");
        }

        void assertState(String orderStatus, String paymentStatus, int canceled, long points, int histories, int cancels) {
            assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE order_id=?", String.class, orderId))
                    .isEqualTo(orderStatus);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE payment_key=?", String.class, paymentKey))
                    .isEqualTo(paymentStatus);
            assertThat(jdbc.queryForObject("SELECT canceled_amount FROM payment WHERE payment_key=?", Integer.class, paymentKey))
                    .isEqualTo(canceled);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_cancel c JOIN payment p ON p.id=c.payment_id "
                    + "WHERE p.payment_key=?", Integer.class, paymentKey)).isEqualTo(cancels);
            if (canceled > 0) {
                assertThat(jdbc.queryForObject("SELECT c.status FROM payment_cancel c JOIN payment p ON p.id=c.payment_id "
                        + "WHERE p.payment_key=?", String.class, paymentKey)).isEqualTo("COMPLETED");
            }
            assertPoints(points, histories);
        }

        void assertPoints(long points, int histories) {
            assertThat(jdbc.queryForObject("SELECT points FROM senior_profile WHERE member_id=?", Long.class, memberId))
                    .isEqualTo(points);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_history h JOIN senior_profile s "
                    + "ON h.senior_profile_id=s.id WHERE s.member_id=?", Integer.class, memberId)).isEqualTo(histories);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT operation_key) FROM point_history h JOIN senior_profile s "
                    + "ON h.senior_profile_id=s.id WHERE s.member_id=?", Integer.class, memberId)).isEqualTo(histories);
        }

        @Override
        public void close() { gateway.close(); }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
    @EntityScan("com.widyu")
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackages = {"com.widyu.pay.repository", "com.widyu.member.repository"},
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    PaymentRepository.class, PaymentOrderRepository.class, PaymentCancelRepository.class,
                    MemberRepository.class, SeniorProfileRepository.class, FamilyRepository.class, PointHistoryRepository.class}))
    @Import({PaymentTransactionService.class, SeniorProfileService.class, AesGcmStringConverter.class,
            com.widyu.global.config.QuerydslConfig.class})
    static class Config {
        @Bean MemberUtil memberUtil() { return mock(MemberUtil.class); }
        @Bean AlbumUnlockRepository albumUnlockRepository() { return mock(AlbumUnlockRepository.class); }
        @Bean FamilyAccessService familyAccessService() { return mock(FamilyAccessService.class); }
    }
}
