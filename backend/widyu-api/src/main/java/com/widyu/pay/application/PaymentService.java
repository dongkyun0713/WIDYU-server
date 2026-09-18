package com.widyu.pay.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.pay.Payment;
import com.widyu.pay.PaymentOrder;
import com.widyu.pay.PointChargePackage;
import com.widyu.pay.dto.request.CancelRequest;
import com.widyu.pay.dto.request.PaymentApproveRequest;
import com.widyu.pay.dto.request.PaymentGatewayCancelRequest;
import com.widyu.pay.dto.request.PaymentGatewayConfirmRequest;
import com.widyu.pay.dto.request.PaymentOrderCreateRequest;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.dto.response.PaymentConfirmResponses;
import com.widyu.pay.dto.response.PaymentOrderResponse;
import com.widyu.pay.dto.response.PaymentPackageResponse;
import com.widyu.pay.infrastructure.PaymentClient;
import com.widyu.pay.infrastructure.PaymentGatewayException;
import com.widyu.pay.repository.PaymentOrderRepository;
import com.widyu.pay.repository.PaymentRepository;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private static final int ORDER_ID_MAX_ATTEMPTS = 5;
    private static final int PAYMENT_ORDER_EXPIRATION_MINUTES = 15;
    private static final Duration PG_IDEMPOTENCY_RETENTION = Duration.ofDays(15);
    private static final Duration PAYMENT_AUTHORIZATION_RETENTION = Duration.ofMinutes(10);

    private final PaymentClient paymentClient;
    private final PaymentRepository paymentRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final MemberUtil memberUtil;
    private final PaymentTransactionService paymentTransactionService;

    @Transactional(readOnly = true)
    public List<PaymentPackageResponse> getPackages() {
        return java.util.Arrays.stream(PointChargePackage.values())
                .map(PaymentPackageResponse::from)
                .toList();
    }

    @Transactional
    public PaymentOrderResponse createOrder(PaymentOrderCreateRequest request) {
        Member currentMember = memberUtil.getCurrentMember();
        validateSeniorMember(currentMember);
        PointChargePackage paymentPackage = resolvePackage(request.packageId());
        PaymentOrder paymentOrder = PaymentOrder.create(
                generateOrderId(),
                currentMember,
                paymentPackage.getOrderName(),
                paymentPackage.getId(),
                paymentPackage.getAmount(),
                0,
                ZonedDateTime.now().plusMinutes(PAYMENT_ORDER_EXPIRATION_MINUTES)
        );
        paymentOrderRepository.save(paymentOrder);
        return PaymentOrderResponse.from(paymentOrder);
    }

    public PaymentConfirmResponse confirmPayment(PaymentApproveRequest request) {
        Member currentMember = memberUtil.getCurrentMember();
        PaymentTransactionService.ApprovalClaim claim = paymentTransactionService.claimApproval(request, currentMember);
        if (claim.isCompleted()) {
            return claim.existingResponse();
        }
        return requestApproval(claim.command());
    }

    public PaymentConfirmResponse cancelPayment(String paymentKey, CancelRequest cancelRequest) {
        Member currentMember = memberUtil.getCurrentMember();
        PaymentTransactionService.CancellationClaim claim = paymentTransactionService.claimCancellation(
                paymentKey,
                cancelRequest,
                currentMember
        );
        if (claim.isCompleted()) {
            return claim.existingResponse();
        }
        return requestCancellation(claim.command());
    }

    @Transactional(readOnly = true)
    public PaymentConfirmResponses getPaymentsByUser() {
        Member currentMember = memberUtil.getCurrentMember();
        List<Payment> payments = paymentRepository.findByMemberId(currentMember.getId());
        if (payments.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }
        return PaymentConfirmResponses.from(payments);
    }

    public void recoverPendingPayments() {
        ZonedDateTime now = ZonedDateTime.now();
        paymentTransactionService.findPendingApprovals(now).forEach(this::recoverApproval);
        paymentTransactionService.findPendingCancellations(now).forEach(this::recoverCancellation);
    }

    private PaymentConfirmResponse requestApproval(PaymentApprovalCommand command) {
        try {
            PaymentConfirmResponse response = paymentClient.confirmPayment(
                    PaymentGatewayConfirmRequest.of(command.orderId(), command.amount(), command.paymentKey()),
                    command.pgIdempotencyKey()
            );
            return paymentTransactionService.completeApproval(command, response);
        } catch (RuntimeException e) {
            paymentTransactionService.recordApprovalFailure(command, errorCodeOf(e));
            throw e;
        }
    }

    private PaymentConfirmResponse requestCancellation(PaymentCancellationCommand command) {
        try {
            PaymentConfirmResponse response = paymentClient.cancelPayment(
                    command.paymentKey(),
                    PaymentGatewayCancelRequest.from(command.request()),
                    command.pgIdempotencyKey()
            );
            return paymentTransactionService.completeCancellation(command, response);
        } catch (RuntimeException e) {
            paymentTransactionService.recordCancellationFailure(command, errorCodeOf(e));
            throw e;
        }
    }

    private void recoverApproval(PaymentApprovalCommand command) {
        try {
            PaymentConfirmResponse payment = paymentClient.getPayment(command.paymentKey());
            if (payment.getStatus() == com.widyu.pay.PaymentStatus.DONE) {
                paymentTransactionService.completeApproval(command, payment);
                return;
            }
            if (isApprovalTerminalStatus(payment.getStatus())) {
                paymentTransactionService.releaseApproval(command);
                return;
            }
        } catch (PaymentGatewayException e) {
            if (e.getStatus() != 404) {
                log.warn("Payment approval recovery lookup failed: orderId={}", command.orderId(), e);
                paymentTransactionService.recordApprovalFailure(command, errorCodeOf(e));
                return;
            }
            // 404: PG에 결제가 없음 = 아직 미확인. 아래 POST 복구 흐름으로 진행한다.
        } catch (RuntimeException e) {
            log.warn("Payment approval recovery lookup failed: orderId={}", command.orderId(), e);
            paymentTransactionService.recordApprovalFailure(command, errorCodeOf(e));
            return;
        }

        if (isApprovalAuthorizationExpired(command.requestedAt())) {
            paymentTransactionService.stopApprovalRecovery(command, "AUTHORIZATION_EXPIRED");
            log.error("Payment approval requires reconciliation after authorization expiration: orderId={}", command.orderId());
            return;
        }
        if (isApprovalTerminalError(command.lastErrorCode())) {
            paymentTransactionService.releaseApproval(command);
            return;
        }

        try {
            requestApproval(command);
        } catch (RuntimeException e) {
            log.warn("Payment approval recovery failed: orderId={}", command.orderId(), e);
        }
    }

    private void recoverCancellation(PaymentCancellationCommand command) {
        try {
            PaymentConfirmResponse payment = paymentClient.getPayment(command.paymentKey());
            Integer gatewayCanceledAmount = payment.getGatewayCanceledAmount();
            if (gatewayCanceledAmount != null && gatewayCanceledAmount == command.expectedCanceledAmount()) {
                paymentTransactionService.completeCancellation(command, payment);
                return;
            }
            if (gatewayCanceledAmount != null && gatewayCanceledAmount > command.expectedCanceledAmount()) {
                paymentTransactionService.holdCancellationForReconciliation(command, "CANCELED_AMOUNT_MISMATCH");
                log.error("Payment cancellation requires reconciliation due to canceled amount mismatch: "
                                + "paymentKey={}, cancellationId={}, expected={}, gateway={}",
                        command.paymentKey(), command.cancellationId(), command.expectedCanceledAmount(), gatewayCanceledAmount);
                return;
            }
        } catch (PaymentGatewayException e) {
            if (e.getStatus() != 404) {
                log.warn("Payment cancellation recovery lookup failed: paymentKey={}, cancellationId={}",
                        command.paymentKey(), command.cancellationId(), e);
                paymentTransactionService.recordCancellationFailure(command, errorCodeOf(e));
                return;
            }
            // 404: PG에 결제가 없음 = 아직 미확인. 아래 POST 복구 흐름으로 진행한다.
        } catch (RuntimeException e) {
            log.warn("Payment cancellation recovery lookup failed: paymentKey={}, cancellationId={}",
                    command.paymentKey(), command.cancellationId(), e);
            paymentTransactionService.recordCancellationFailure(command, errorCodeOf(e));
            return;
        }

        if (isIdempotencyExpired(command.requestedAt())) {
            paymentTransactionService.stopCancellationRecovery(command, "IDEMPOTENCY_KEY_EXPIRED");
            log.error("Payment cancellation requires reconciliation after idempotency retention: paymentKey={}, cancellationId={}",
                    command.paymentKey(), command.cancellationId());
            return;
        }
        if (isCancellationTerminalError(command.lastErrorCode())) {
            paymentTransactionService.releaseCancellation(command);
            return;
        }

        try {
            requestCancellation(command);
        } catch (RuntimeException e) {
            log.warn("Payment cancellation recovery failed: paymentKey={}, cancellationId={}",
                    command.paymentKey(), command.cancellationId(), e);
        }
    }

    private String errorCodeOf(RuntimeException exception) {
        if (exception instanceof PaymentGatewayException paymentGatewayException) {
            return paymentGatewayException.getErrorCode();
        }
        return "UNKNOWN_PAYMENT_ERROR";
    }

    private boolean isApprovalTerminalError(String errorCode) {
        return "INVALID_CARD_EXPIRATION".equals(errorCode)
                || "INVALID_STOPPED_CARD".equals(errorCode)
                || "REJECT_CARD_COMPANY".equals(errorCode)
                || "NOT_SUPPORTED_METHOD".equals(errorCode)
                || "INVALID_REQUEST".equals(errorCode);
    }

    private boolean isCancellationTerminalError(String errorCode) {
        return "NOT_CANCELABLE_PAYMENT".equals(errorCode)
                || "NOT_CANCELABLE_AMOUNT".equals(errorCode)
                || "EXCEED_MAX_REFUND_DUE".equals(errorCode)
                || "INVALID_REQUEST".equals(errorCode);
    }

    private boolean isIdempotencyExpired(ZonedDateTime requestedAt) {
        return requestedAt == null || requestedAt.plus(PG_IDEMPOTENCY_RETENTION).isBefore(ZonedDateTime.now());
    }

    private boolean isApprovalAuthorizationExpired(ZonedDateTime requestedAt) {
        return requestedAt == null || requestedAt.plus(PAYMENT_AUTHORIZATION_RETENTION).isBefore(ZonedDateTime.now());
    }

    private boolean isApprovalTerminalStatus(com.widyu.pay.PaymentStatus status) {
        return status == com.widyu.pay.PaymentStatus.ABORTED
                || status == com.widyu.pay.PaymentStatus.EXPIRED
                || status == com.widyu.pay.PaymentStatus.CANCELED
                || status == com.widyu.pay.PaymentStatus.PARTIAL_CANCELED;
    }

    private void validateSeniorMember(Member member) {
        if (member.getType() != MemberType.SENIOR || member.getSeniorProfile() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시니어 회원만 결제할 수 있습니다.");
        }
    }

    private PointChargePackage resolvePackage(String packageId) {
        try {
            return PointChargePackage.fromId(packageId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "지원하지 않는 결제 패키지입니다.");
        }
    }

    private String generateOrderId() {
        for (int attempt = 0; attempt < ORDER_ID_MAX_ATTEMPTS; attempt++) {
            String orderId = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            if (!paymentOrderRepository.existsByOrderId(orderId)) {
                return orderId;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "주문 ID 생성에 실패했습니다.");
    }
}
