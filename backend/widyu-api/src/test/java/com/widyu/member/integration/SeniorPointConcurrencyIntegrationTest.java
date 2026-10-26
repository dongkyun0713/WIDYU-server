package com.widyu.member.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.goal.walk.application.WalkService;
import com.widyu.goal.walk.dto.request.UpdateStepsRequest;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
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
@DisplayName("시니어 포인트 동시성 통합 테스트")
class SeniorPointConcurrencyIntegrationTest {
    @MockBean private FcmTransport fcmTransport;

    @Autowired private SeniorProfileService seniorProfileService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SeniorProfileRepository seniorProfileRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private FcmNotificationRepository notificationRepository;
    @Autowired private FamilyMembershipRepository familyMembershipRepository;
    @Autowired private WalkRepository walkRepository;
    @Autowired private WalkService walkService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private MemberUtil memberUtil;
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAll();
        pointHistoryRepository.deleteAll();
        walkRepository.deleteAll();
        seniorProfileRepository.deleteAll();
        familyMembershipRepository.deleteAll();
        familyRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("동일 시니어에게 포인트를 동시에 적립하면 모든 적립이 유실 없이 반영된다")
    void 포인트_동시_적립_유실없음() throws InterruptedException {
        // given
        Long memberId = createSeniorMember("홍길동", "01012345678", "FAM100", "INV1001");
        long seniorProfileId = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getId();
        long initialPoints = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getPoints();
        int threadCount = 4;
        long addAmount = 10L;

        // when
        ConcurrencyResult result = runConcurrently(threadCount,
                () -> seniorProfileService.addPointsToMember(memberId, addAmount, "동시 적립 테스트"));

        // then
        // 적립은 잔액 부족 같은 정상 실패가 없으므로 모든 요청이 성공해야 한다
        assertThat(result.completed()).isTrue();
        assertThat(result.successCount()).isEqualTo(threadCount);

        long finalPoints = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getPoints();
        assertThat(finalPoints).isEqualTo(initialPoints + addAmount * threadCount);

        long earnHistoryCount = countHistoriesByType(seniorProfileId, PointHistoryType.EARN);
        assertThat(earnHistoryCount).isEqualTo(threadCount);
        assertThat(notificationRepository.findAll()).hasSize(threadCount)
                .allMatch(notification -> notification.getType() == NotificationType.POINT_EARNED);
    }

    @Test
    @DisplayName("잔액을 초과하는 동시 차감 요청에도 성공한 만큼만 차감되고 잔액이 음수가 되지 않는다")
    void 포인트_동시_차감_과차감없음() throws InterruptedException {
        // given — 초기 100pt, 50pt씩 차감하면 최대 2건만 성공 가능
        Long memberId = createSeniorMember("김영희", "01099998888", "FAM200", "INV2002");
        long seniorProfileId = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getId();
        long initialPoints = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getPoints();
        int threadCount = 4;
        long deductAmount = 50L;
        int expectedSuccess = (int) (initialPoints / deductAmount);

        // when
        ConcurrencyResult result = runConcurrently(threadCount,
                () -> seniorProfileService.deductPointsFromMember(memberId, deductAmount, "동시 차감 테스트"));

        // then
        assertThat(result.completed()).isTrue();
        assertThat(result.successCount()).isEqualTo(expectedSuccess);

        long finalPoints = seniorProfileRepository.findByMemberId(memberId).orElseThrow().getPoints();
        assertThat(finalPoints).isEqualTo(0L);
        assertThat(finalPoints).isEqualTo(initialPoints - deductAmount * expectedSuccess);

        long useHistoryCount = countHistoriesByType(seniorProfileId, PointHistoryType.USE);
        assertThat(useHistoryCount).isEqualTo(expectedSuccess);
        assertThat(notificationRepository.findAll()).hasSize(expectedSuccess)
                .allMatch(notification -> notification.getType() == NotificationType.POINT_USED);
    }

    @Test
    @DisplayName("걷기 목표를 달성하면 시니어와 보호자에게 G01을 하나씩 만들고 P01은 만들지 않는다")
    void 걷기_목표를_달성하면_가족에게_G01을_만들고_P01은_만들지_않는다() {
        // given
        Long seniorId = createSeniorMember("홍길동", "01012345678", "FAM300", "INV3003");
        Member senior = memberRepository.findById(seniorId).orElseThrow();
        Family family = seniorProfileRepository.findByMemberId(seniorId).orElseThrow().getFamily();
        Member guardian = memberRepository.save(Member.createMember(MemberType.GUARDIAN,
                "보호자", "01033334444"));
        familyMembershipRepository.save(FamilyMembership.createMembership(family, guardian));
        Walk walk = walkRepository.save(Walk.createWithGoal(senior, LocalDate.now(), 5000));
        given(memberUtil.getCurrentMember()).willReturn(senior);

        // when
        walkService.updateSteps(new UpdateStepsRequest(6000, LocalDate.now()));
        walkService.updateSteps(new UpdateStepsRequest(7000, LocalDate.now()));

        // then
        assertThat(walkRepository.findById(walk.getId()).orElseThrow().isRewarded()).isTrue();
        assertThat(pointHistoryRepository.count()).isEqualTo(1L);
        var centers = notificationRepository.findAll();
        assertThat(centers).hasSize(2)
                .allMatch(center -> center.getType() == NotificationType.GOAL_ACHIEVED)
                .allMatch(center -> center.getBody().equals("25P가 자동으로 적립됐어요."));
        assertThat(centers.stream().map(center -> center.getRecipientMember().getId()).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(seniorId, guardian.getId()));
    }

    @Test
    @DisplayName("걷기 목표 달성 거래를 롤백하면 포인트와 G01을 함께 되돌린다")
    void 걷기_목표_달성을_롤백하면_포인트와_G01을_함께_되돌린다() {
        // given
        Long seniorId = createSeniorMember("김영희", "01099998888", "FAM400", "INV4004");
        Member senior = memberRepository.findById(seniorId).orElseThrow();
        Walk walk = walkRepository.save(Walk.createWithGoal(senior, LocalDate.now(), 5000));
        given(memberUtil.getCurrentMember()).willReturn(senior);

        // when
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            walkService.updateSteps(new UpdateStepsRequest(6000, LocalDate.now()));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        // then
        assertThat(walkRepository.findById(walk.getId()).orElseThrow().isRewarded()).isFalse();
        assertThat(pointHistoryRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
    }

    private long countHistoriesByType(long seniorProfileId, PointHistoryType type) {
        return pointHistoryRepository.findAllBySeniorProfileIdOrderByCreatedAtDesc(seniorProfileId).stream()
                .filter(history -> history.getType() == type)
                .count();
    }

    private ConcurrencyResult runConcurrently(int threadCount, Runnable action) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    action.run();
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {
                    // 잔액 부족(BusinessException) 등 정상 실패는 성공 카운트에서 제외한다
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        return new ConcurrencyResult(completed, successCount.get());
    }

    private record ConcurrencyResult(boolean completed, int successCount) {
    }

    private Long createSeniorMember(String name, String phoneNumber, String familyCode, String inviteCode) {
        Family family = familyRepository.save(Family.createFamily(familyCode));
        Member member = memberRepository.save(Member.createMember(MemberType.SENIOR, name, phoneNumber));
        SeniorProfile seniorProfile = seniorProfileRepository.save(
                SeniorProfile.createSeniorProfile(
                        member,
                        family,
                        "서울시 강남구",
                        inviteCode,
                        LocalDate.of(1950, 1, 1)
                )
        );
        return member.getId();
    }
}
