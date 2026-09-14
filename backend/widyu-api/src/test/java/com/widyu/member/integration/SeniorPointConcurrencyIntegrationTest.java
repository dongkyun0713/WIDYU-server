package com.widyu.member.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.auth.repository.VerificationCodeRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
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
    @MockBean private com.widyu.fcm.application.FcmTransport fcmTransport;

    @Autowired private SeniorProfileService seniorProfileService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SeniorProfileRepository seniorProfileRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;

    @MockBean private MemberUtil memberUtil;
    @MockBean private VerificationCodeRepository verificationCodeRepository;
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @AfterEach
    void tearDown() {
        pointHistoryRepository.deleteAll();
        seniorProfileRepository.deleteAll();
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
