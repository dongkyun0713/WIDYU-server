package com.widyu.pay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.widyu.auth.application.SmsService;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.pay.Payment;
import com.widyu.pay.PaymentOrder;
import com.widyu.pay.PaymentStatus;
import com.widyu.pay.PaymentCancel;
import com.widyu.pay.application.PaymentCancellationCommand;
import com.widyu.pay.application.PaymentService;
import com.widyu.pay.application.PaymentTransactionService;
import com.widyu.pay.infrastructure.PaymentClient;
import com.widyu.pay.dto.request.CancelRequest;
import com.widyu.pay.dto.request.PaymentApproveRequest;
import com.widyu.pay.dto.request.PaymentGatewayCancelRequest;
import com.widyu.pay.dto.request.PaymentOrderCreateRequest;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.dto.response.PaymentOrderResponse;
import com.widyu.pay.repository.PaymentCancelRepository;
import com.widyu.pay.repository.PaymentOrderRepository;
import com.widyu.pay.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import com.widyu.fcm.application.FcmTransport;

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
@DisplayName("결제 통합 테스트")
class PaymentIntegrationTest {
    @MockBean private FcmTransport fcmTransport;

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentTransactionService paymentTransactionService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentCancelRepository paymentCancelRepository;
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private SeniorProfileRepository seniorProfileRepository;
    @Autowired private FamilyRepository familyRepository;

    @MockBean private PaymentClient paymentClient;
    @MockBean private MemberUtil memberUtil;
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private S3Client s3Client;
    @MockBean private SmsService smsService;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @AfterEach
    void tearDown() {
        pointHistoryRepository.deleteAll();
        paymentRepository.deleteAll();
        paymentOrderRepository.deleteAll();
        seniorProfileRepository.deleteAll();
        familyRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("결제 승인 시 주문, 결제, 포인트 적립 이력이 함께 저장된다")
    void 결제_승인_통합() {
        Member currentMember = createSeniorMember("홍길동", "01012345678", "FAM001", "INV0011");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_123456",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        PaymentConfirmResponse confirmResponse = paymentService.confirmPayment(
                new PaymentApproveRequest(orderResponse.orderId(), "pay_123456")
        );

        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderId(orderResponse.orderId()).orElseThrow();
        Payment payment = paymentRepository.findByPaymentKey("pay_123456").orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();
        var pointHistories = pointHistoryRepository.findAllBySeniorProfileIdOrderByCreatedAtDesc(seniorProfile.getId());

        assertThat(confirmResponse.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(paymentOrder.getStatus()).isEqualTo(com.widyu.pay.PaymentOrderStatus.PAID);
        assertThat(payment.getOrderId()).isEqualTo(orderResponse.orderId());
        assertThat(payment.getAmount()).isEqualTo(10000);
        assertThat(seniorProfile.getPoints()).isEqualTo(10100L);
        assertThat(pointHistories).hasSize(1);
        assertThat(pointHistories.get(0).getType()).isEqualTo(PointHistoryType.EARN);
        assertThat(pointHistories.get(0).getAmount()).isEqualTo(10000L);
        assertThat(pointHistories.get(0).getDescription()).isEqualTo("포인트 충전 10,000원");
    }

    @Test
    @DisplayName("PG 승인 응답이 완료 상태가 아니면 결제와 포인트를 반영하지 않고 복구 대상으로 유지한다")
    void 미완료_승인_응답_통합() {
        Member currentMember = createSeniorMember("박철수", "01077776666", "FAM004", "INV0044");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_888888",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.WAITING_FOR_DEPOSIT
        ));

        assertThatThrownBy(() -> paymentService.confirmPayment(
                new PaymentApproveRequest(orderResponse.orderId(), "pay_888888")
        )).isInstanceOf(BusinessException.class);

        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderId(orderResponse.orderId()).orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();

        assertThat(paymentOrder.getStatus()).isEqualTo(com.widyu.pay.PaymentOrderStatus.APPROVING);
        assertThat(paymentOrder.getApprovalNextRetryAt()).isNotNull();
        assertThat(paymentRepository.findByPaymentKey("pay_888888")).isEmpty();
        assertThat(seniorProfile.getPoints()).isEqualTo(100L);
        assertThat(pointHistoryRepository.findAllBySeniorProfileIdOrderByCreatedAtDesc(seniorProfile.getId())).isEmpty();
    }

    @Test
    @DisplayName("부분 취소 시 취소 이력과 포인트 차감 이력이 함께 반영된다")
    void 부분_취소_통합() {
        Member currentMember = createSeniorMember("김영희", "01099998888", "FAM002", "INV0022");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_654321",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_654321"));
        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_654321"),
                org.mockito.ArgumentMatchers.eq(new PaymentGatewayCancelRequest("부분 취소", 3000)),
                org.mockito.ArgumentMatchers.anyString()
        ))
                .willReturn(createGatewayResponse(
                        "pay_654321",
                        orderResponse.orderId(),
                        "포인트 충전 10,000원",
                        PaymentStatus.PARTIAL_CANCELED,
                        3000
                ));
        PaymentConfirmResponse cancelResponse = paymentService.cancelPayment(
                "pay_654321",
                new CancelRequest("부분 취소", 3000, "cancel-request-1")
        );

        Payment payment = paymentRepository.findByPaymentKey("pay_654321").orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();
        List<com.widyu.member.PointHistory> pointHistories =
                pointHistoryRepository.findAllBySeniorProfileIdOrderByCreatedAtDesc(seniorProfile.getId());

        assertThat(cancelResponse.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(payment.getCanceledAmount()).isEqualTo(3000);
        assertThat(payment.getCanceledPointAmount()).isEqualTo(3000);
        assertThat(payment.getRemainingAmount()).isEqualTo(7000);
        assertThat(cancelResponse.getCancellations()).hasSize(1);
        assertThat(seniorProfile.getPoints()).isEqualTo(7100L);
        assertThat(pointHistories).hasSize(2);
        assertThat(pointHistories.get(0).getType()).isEqualTo(PointHistoryType.USE);
        assertThat(pointHistories.get(0).getAmount()).isEqualTo(3000L);
        assertThat(pointHistories.get(0).getDescription()).isEqualTo("부분 취소");
    }

    @Test
    @DisplayName("같은 멱등 키로 부분 취소를 재요청하면 취소와 포인트 환수가 한 번만 반영된다")
    void 동일_멱등_키_부분_취소_재요청_통합() {
        Member currentMember = createSeniorMember("김영희", "01099998887", "FAM003", "INV0033");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_777777",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_777777"));

        CancelRequest cancelRequest = new CancelRequest("부분 취소", 3000, "cancel-request-duplicate");
        given(memberUtil.getCurrentMember()).willReturn(
                reloadCurrentMember(currentMember.getId()),
                reloadCurrentMember(currentMember.getId())
        );
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_777777"),
                org.mockito.ArgumentMatchers.eq(PaymentGatewayCancelRequest.from(cancelRequest)),
                org.mockito.ArgumentMatchers.anyString()
        )).willReturn(createGatewayResponse(
                "pay_777777",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.PARTIAL_CANCELED,
                3000
        ));

        paymentService.cancelPayment("pay_777777", cancelRequest);
        PaymentConfirmResponse duplicatedResponse = paymentService.cancelPayment("pay_777777", cancelRequest);

        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_777777",
                new CancelRequest("부분 취소", null, "cancel-request-duplicate")
        )).isInstanceOf(BusinessException.class);

        Payment payment = paymentRepository.findByPaymentKey("pay_777777").orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();
        List<com.widyu.member.PointHistory> pointHistories =
                pointHistoryRepository.findAllBySeniorProfileIdOrderByCreatedAtDesc(seniorProfile.getId());

        assertThat(duplicatedResponse.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(duplicatedResponse.getCancellations()).hasSize(1);
        assertThat(payment.getCanceledAmount()).isEqualTo(3000);
        assertThat(seniorProfile.getPoints()).isEqualTo(7100L);
        assertThat(pointHistories).hasSize(2);
        verify(paymentClient, times(1)).cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_777777"),
                org.mockito.ArgumentMatchers.eq(PaymentGatewayCancelRequest.from(cancelRequest)),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("취소 선점 시 환수 포인트를 예약하고 취소를 중단하면 포인트를 반환한다")
    void 취소_포인트_예약_및_해제_통합() {
        Member currentMember = createSeniorMember("이몽룡", "01055554444", "FAM005", "INV0055");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_555555",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_555555"));

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_555555"),
                any(),
                org.mockito.ArgumentMatchers.anyString()
        )).willThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_555555",
                new CancelRequest("부분 취소", 3000, "cancel-reserve-1")
        )).isInstanceOf(IllegalStateException.class);

        SeniorProfile reservedProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();
        assertThat(reservedProfile.getPoints()).isEqualTo(7100L);

        Payment payment = paymentRepository.findByPaymentKey("pay_555555").orElseThrow();
        PaymentCancel pendingCancel = paymentCancelRepository.findAll().stream()
                .filter(cancellation -> cancellation.getPayment().getId().equals(payment.getId()))
                .findFirst()
                .orElseThrow();
        paymentTransactionService.releaseCancellation(new PaymentCancellationCommand(
                pendingCancel.getId(),
                "pay_555555",
                CancelRequest.of("부분 취소", 3000, "cancel-reserve-1"),
                pendingCancel.getPgIdempotencyKey(),
                3000,
                ZonedDateTime.now()
        ));

        SeniorProfile releasedProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();
        assertThat(releasedProfile.getPoints()).isEqualTo(10100L);
        assertThat(paymentCancelRepository.findById(pendingCancel.getId())).isEmpty();
    }

    @Test
    @DisplayName("취소 복구를 중단하면 예약한 환수 포인트를 반환한다")
    void 취소_복구_중단_포인트_반환_통합() {
        Member currentMember = createSeniorMember("변학도", "01011110000", "FAM007", "INV0077");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_444444",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_444444"));

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_444444"),
                any(),
                org.mockito.ArgumentMatchers.anyString()
        )).willThrow(new IllegalStateException("timeout"));

        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_444444",
                new CancelRequest("부분 취소", 3000, "cancel-stop-1")
        )).isInstanceOf(IllegalStateException.class);
        assertThat(seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow().getPoints())
                .isEqualTo(7100L);

        Payment payment = paymentRepository.findByPaymentKey("pay_444444").orElseThrow();
        PaymentCancel pendingCancel = paymentCancelRepository.findAll().stream()
                .filter(cancellation -> cancellation.getPayment().getId().equals(payment.getId()))
                .findFirst()
                .orElseThrow();
        paymentTransactionService.stopCancellationRecovery(new PaymentCancellationCommand(
                pendingCancel.getId(),
                "pay_444444",
                CancelRequest.of("부분 취소", 3000, "cancel-stop-1"),
                pendingCancel.getPgIdempotencyKey(),
                3000,
                ZonedDateTime.now()
        ), "IDEMPOTENCY_KEY_EXPIRED");

        assertThat(seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow().getPoints())
                .isEqualTo(10100L);
        PaymentCancel stoppedCancel = paymentCancelRepository.findById(pendingCancel.getId()).orElseThrow();
        assertThat(stoppedCancel.isAborted()).isTrue();
        assertThat(stoppedCancel.getRecoveryStoppedAt()).isNotNull();

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_444444",
                new CancelRequest("부분 취소", 3000, "cancel-stop-1")
        )).isInstanceOf(BusinessException.class);
        assertThat(seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow().getPoints())
                .isEqualTo(10100L);

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_444444"),
                any(),
                org.mockito.ArgumentMatchers.anyString()
        )).willReturn(createGatewayResponse(
                "pay_444444",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.PARTIAL_CANCELED,
                3000
        ));
        PaymentConfirmResponse newKeyCancelResponse = paymentService.cancelPayment(
                "pay_444444",
                new CancelRequest("부분 취소", 3000, "cancel-stop-2")
        );
        assertThat(newKeyCancelResponse.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow().getPoints())
                .isEqualTo(7100L);
    }

    @Test
    @DisplayName("취소 금액 불일치로 대사 보류하면 예약 포인트를 유지한다")
    void 취소_금액_불일치_대사_보류_통합() {
        Member currentMember = createSeniorMember("방자", "01022221111", "FAM008", "INV0088");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_333333",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_333333"));

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_333333"),
                any(),
                org.mockito.ArgumentMatchers.anyString()
        )).willThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_333333",
                new CancelRequest("부분 취소", 3000, "cancel-mismatch-1")
        )).isInstanceOf(IllegalStateException.class);

        Payment payment = paymentRepository.findByPaymentKey("pay_333333").orElseThrow();
        PaymentCancel pendingCancel = paymentCancelRepository.findAll().stream()
                .filter(cancellation -> cancellation.getPayment().getId().equals(payment.getId()))
                .findFirst()
                .orElseThrow();
        paymentTransactionService.holdCancellationForReconciliation(new PaymentCancellationCommand(
                pendingCancel.getId(),
                "pay_333333",
                CancelRequest.of("부분 취소", 3000, "cancel-mismatch-1"),
                pendingCancel.getPgIdempotencyKey(),
                3000,
                ZonedDateTime.now()
        ), "CANCELED_AMOUNT_MISMATCH");

        assertThat(seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow().getPoints())
                .isEqualTo(7100L);
        PaymentCancel heldCancel = paymentCancelRepository.findById(pendingCancel.getId()).orElseThrow();
        assertThat(heldCancel.getRecoveryStoppedAt()).isNotNull();
    }

    @Test
    @DisplayName("PG 취소 응답에 취소가 반영되지 않았으면 내부 취소를 확정하지 않고 재시도 대상으로 유지한다")
    void 미반영_취소_응답_통합() {
        Member currentMember = createSeniorMember("성춘향", "01033332222", "FAM006", "INV0066");
        given(memberUtil.getCurrentMember()).willReturn(currentMember, currentMember);

        PaymentOrderResponse orderResponse = paymentService.createOrder(new PaymentOrderCreateRequest("POINT_10000"));
        given(paymentClient.confirmPayment(any(), org.mockito.ArgumentMatchers.anyString())).willReturn(createGatewayResponse(
                "pay_999999",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));
        paymentService.confirmPayment(new PaymentApproveRequest(orderResponse.orderId(), "pay_999999"));

        given(memberUtil.getCurrentMember()).willReturn(reloadCurrentMember(currentMember.getId()));
        given(paymentClient.cancelPayment(
                org.mockito.ArgumentMatchers.eq("pay_999999"),
                any(),
                org.mockito.ArgumentMatchers.anyString()
        )).willReturn(createGatewayResponse(
                "pay_999999",
                orderResponse.orderId(),
                "포인트 충전 10,000원",
                PaymentStatus.DONE
        ));

        assertThatThrownBy(() -> paymentService.cancelPayment(
                "pay_999999",
                new CancelRequest("부분 취소", 3000, "cancel-unreflected-1")
        )).isInstanceOf(BusinessException.class);

        Payment payment = paymentRepository.findByPaymentKey("pay_999999").orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(currentMember.getId()).orElseThrow();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getCanceledAmount()).isEqualTo(0);
        assertThat(seniorProfile.getPoints()).isEqualTo(7100L);
        PaymentCancel pendingCancel = paymentCancelRepository.findAll().stream()
                .filter(cancellation -> cancellation.getPayment().getId().equals(payment.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(pendingCancel.isPending()).isTrue();
        assertThat(pendingCancel.getNextRetryAt()).isNotNull();
    }

    private Member createSeniorMember(String name, String phoneNumber, String familyCode, String inviteCode) {
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
        ReflectionTestUtils.setField(member, "seniorProfile", seniorProfile);
        return member;
    }

    private Member reloadCurrentMember(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId).orElseThrow();
        ReflectionTestUtils.setField(member, "seniorProfile", seniorProfile);
        return member;
    }

    private PaymentConfirmResponse createGatewayResponse(
            String paymentKey,
            String orderId,
            String orderName,
            PaymentStatus status
    ) {
        return createGatewayResponse(paymentKey, orderId, orderName, status, 0);
    }

    private PaymentConfirmResponse createGatewayResponse(
            String paymentKey,
            String orderId,
            String orderName,
            PaymentStatus status,
            int canceledAmount
    ) {
        PaymentConfirmResponse response = new PaymentConfirmResponse();
        ReflectionTestUtils.setField(response, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(response, "orderId", orderId);
        ReflectionTestUtils.setField(response, "orderName", orderName);
        ReflectionTestUtils.setField(response, "amount", 10000);
        ReflectionTestUtils.setField(response, "totalAmount", 10000);
        ReflectionTestUtils.setField(response, "balanceAmount", 10000 - canceledAmount);
        ReflectionTestUtils.setField(response, "status", status);
        ReflectionTestUtils.setField(response, "canceledAmount", canceledAmount);
        ReflectionTestUtils.setField(response, "requestedAt", ZonedDateTime.parse("2026-05-28T10:00:00+09:00"));
        ReflectionTestUtils.setField(response, "approvedAt", ZonedDateTime.parse("2026-05-28T10:01:00+09:00"));
        return response;
    }
}
