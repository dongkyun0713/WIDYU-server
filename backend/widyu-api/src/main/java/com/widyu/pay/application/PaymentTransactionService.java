package com.widyu.pay.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.pay.Payment;
import com.widyu.pay.PaymentCancel;
import com.widyu.pay.PaymentCancelStatus;
import com.widyu.pay.PaymentOrder;
import com.widyu.pay.PaymentStatus;
import com.widyu.pay.dto.mapper.PaymentMapper;
import com.widyu.pay.dto.request.CancelRequest;
import com.widyu.pay.dto.request.PaymentApproveRequest;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.repository.PaymentCancelRepository;
import com.widyu.pay.repository.PaymentOrderRepository;
import com.widyu.pay.repository.PaymentRepository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentCancelRepository paymentCancelRepository;

    @Transactional
    public ApprovalClaim claimApproval(PaymentApproveRequest request, Member currentMember) {
        validateSeniorMember(currentMember);
        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderIdForUpdate(request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, "주문 정보를 찾을 수 없습니다."));
        validatePaymentOrderOwnership(paymentOrder, currentMember.getId());

        Payment existingOrderPayment = paymentRepository.findByOrderId(request.orderId()).orElse(null);
        if (existingOrderPayment != null) {
            validatePaymentOwnership(existingOrderPayment, currentMember.getId());
            if (!Objects.equals(existingOrderPayment.getPaymentKey(), request.paymentKey())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 결제 완료된 주문입니다.");
            }
            return ApprovalClaim.completed(PaymentConfirmResponse.from(existingOrderPayment));
        }

        Payment existingPayment = paymentRepository.findByPaymentKey(request.paymentKey()).orElse(null);
        if (existingPayment != null) {
            validatePaymentOwnership(existingPayment, currentMember.getId());
            validateExistingPaymentMatchesOrder(existingPayment, paymentOrder);
            return ApprovalClaim.completed(PaymentConfirmResponse.from(existingPayment));
        }

        if (paymentOrder.isApproving()) {
            if (!paymentOrder.matchesApprovalPaymentKey(request.paymentKey())) {
                throw new BusinessException(ErrorCode.PAYMENT_PROCESSING);
            }
            return ApprovalClaim.processing(toApprovalCommand(paymentOrder));
        }

        validatePaymentOrderState(paymentOrder);
        paymentOrder.beginApproval(request.paymentKey(), UUID.randomUUID().toString(), ZonedDateTime.now());
        return ApprovalClaim.processing(toApprovalCommand(paymentOrder));
    }

    @Transactional
    public PaymentConfirmResponse completeApproval(PaymentApprovalCommand command, PaymentConfirmResponse response) {
        PaymentOrder paymentOrder = paymentOrderRepository.findByOrderIdForUpdate(command.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND, "주문 정보를 찾을 수 없습니다."));
        Payment existingPayment = paymentRepository.findByOrderId(command.orderId()).orElse(null);
        if (existingPayment != null) {
            return PaymentConfirmResponse.from(existingPayment);
        }

        if (!paymentOrder.isApproving()
                || !paymentOrder.matchesApprovalPaymentKey(command.paymentKey())
                || !Objects.equals(paymentOrder.getApprovalPgIdempotencyKey(), command.pgIdempotencyKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "결제 승인 처리 상태가 일치하지 않습니다.");
        }
        validateConfirmResponse(response, paymentOrder, command.paymentKey());

        Payment payment = PaymentMapper.toEntity(response, paymentOrder.getMember(), paymentOrder);
        paymentRepository.save(payment);
        paymentOrder.markPaid();
        return PaymentConfirmResponse.from(payment);
    }

    @Transactional
    public void releaseApproval(PaymentApprovalCommand command) {
        paymentOrderRepository.findByOrderIdForUpdate(command.orderId()).ifPresent(paymentOrder -> {
            if (paymentOrder.isApproving()
                    && paymentOrder.matchesApprovalPaymentKey(command.paymentKey())
                    && Objects.equals(paymentOrder.getApprovalPgIdempotencyKey(), command.pgIdempotencyKey())) {
                paymentOrder.resetApproval();
            }
        });
    }

    @Transactional
    public void recordApprovalFailure(PaymentApprovalCommand command, String errorCode) {
        paymentOrderRepository.findByOrderIdForUpdate(command.orderId()).ifPresent(paymentOrder -> {
            if (paymentOrder.isApproving()
                    && Objects.equals(paymentOrder.getApprovalPgIdempotencyKey(), command.pgIdempotencyKey())) {
                paymentOrder.scheduleApprovalRecovery(errorCode, nextRetryAt(paymentOrder.getApprovalRetryCount()));
            }
        });
    }

    @Transactional
    public void stopApprovalRecovery(PaymentApprovalCommand command, String errorCode) {
        paymentOrderRepository.findByOrderIdForUpdate(command.orderId()).ifPresent(paymentOrder -> {
            if (paymentOrder.isApproving()
                    && Objects.equals(paymentOrder.getApprovalPgIdempotencyKey(), command.pgIdempotencyKey())) {
                paymentOrder.stopApprovalRecovery(errorCode, ZonedDateTime.now());
            }
        });
    }

    @Transactional
    public CancellationClaim claimCancellation(String paymentKey, CancelRequest cancelRequest, Member currentMember) {
        Payment payment = paymentRepository.findByPaymentKeyForUpdate(paymentKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        validatePaymentOwnership(payment, currentMember.getId());

        PaymentCancel existingCancellation = findCancellationByIdempotencyKey(payment, cancelRequest);
        if (existingCancellation != null) {
            validateExistingCancellationRequest(existingCancellation, cancelRequest);
            if (existingCancellation.isAborted()) {
                throw new BusinessException(ErrorCode.PAYMENT_FAILED, "중단된 취소 요청입니다. 새로운 멱등 키로 다시 요청해주세요.");
            }
            if (!existingCancellation.isPending()) {
                return CancellationClaim.completed(PaymentConfirmResponse.from(payment));
            }
            validateCancellationRecoveryNotStopped(existingCancellation);
            return CancellationClaim.processing(toCancellationCommand(existingCancellation));
        }

        PaymentCancel pendingCancellation = findPendingCancellation(payment);
        if (pendingCancellation != null) {
            validateCancellationRecoveryNotStopped(pendingCancellation);
            CancelRequest effectiveRequest = sanitizeCancelRequest(payment, cancelRequest);
            if (pendingCancellation.matchesRequest(
                    effectiveRequest.cancelReason(),
                    effectiveRequest.cancelAmount()
            ) && Objects.equals(pendingCancellation.getIdempotencyKey(), effectiveRequest.idempotencyKey())) {
                return CancellationClaim.processing(toCancellationCommand(pendingCancellation));
            }
            throw new BusinessException(ErrorCode.PAYMENT_PROCESSING);
        }
        if (payment.isCanceled()) {
            return CancellationClaim.completed(PaymentConfirmResponse.from(payment));
        }

        CancelRequest effectiveRequest = sanitizeCancelRequest(payment, cancelRequest);
        validatePartialCancellationIdempotencyKey(payment, effectiveRequest);

        Integer requestedCancelAmount = null;
        if (cancelRequest != null) {
            requestedCancelAmount = cancelRequest.cancelAmount();
        }
        // 결제는 포인트를 충전하지 않으므로 환수 포인트도 없다 — cancel_point_amount 컬럼은 호환용으로 남기고 0을 기록한다 (ADR-0029)
        PaymentCancel paymentCancel = PaymentCancel.createPending(
                payment,
                effectiveRequest.cancelAmount(),
                requestedCancelAmount,
                0,
                effectiveRequest.cancelReason(),
                currentMember.getId(),
                effectiveRequest.idempotencyKey(),
                UUID.randomUUID().toString()
        );
        payment.addCancellation(paymentCancel);
        paymentRepository.flush();
        return CancellationClaim.processing(toCancellationCommand(paymentCancel));
    }

    @Transactional
    public PaymentConfirmResponse completeCancellation(PaymentCancellationCommand command, PaymentConfirmResponse response) {
        PaymentCancel paymentCancel = paymentCancelRepository.findByIdForUpdate(command.cancellationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        Payment payment = paymentRepository.findByPaymentKeyForUpdate(command.paymentKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (!paymentCancel.isPending()) {
            return PaymentConfirmResponse.from(payment);
        }
        validateCancellationRecoveryNotStopped(paymentCancel);
        if (!Objects.equals(paymentCancel.getPgIdempotencyKey(), command.pgIdempotencyKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "결제 취소 처리 상태가 일치하지 않습니다.");
        }
        validateCancelResponse(response, command.paymentKey(), payment, paymentCancel);

        ZonedDateTime canceledAt = ZonedDateTime.now();
        paymentCancel.complete(canceledAt);
        payment.cancel(
                paymentCancel.getCancelAmount(),
                paymentCancel.getCancelPointAmount(),
                paymentCancel.getCancelReason(),
                canceledAt
        );
        if (payment.getPaymentOrder() != null && payment.getStatus() == PaymentStatus.CANCELED) {
            payment.getPaymentOrder().markCanceled();
        }
        return PaymentConfirmResponse.from(payment);
    }

    @Transactional
    public void releaseCancellation(PaymentCancellationCommand command) {
        paymentCancelRepository.findByIdForUpdate(command.cancellationId()).ifPresent(paymentCancel -> {
            if (paymentCancel.isPending() && Objects.equals(paymentCancel.getPgIdempotencyKey(), command.pgIdempotencyKey())) {
                paymentCancel.getPayment().removeCancellation(paymentCancel);
            }
        });
    }

    @Transactional
    public void recordCancellationFailure(PaymentCancellationCommand command, String errorCode) {
        paymentCancelRepository.findByIdForUpdate(command.cancellationId()).ifPresent(paymentCancel -> {
            if (paymentCancel.isPending() && Objects.equals(paymentCancel.getPgIdempotencyKey(), command.pgIdempotencyKey())) {
                paymentCancel.scheduleRecovery(errorCode, nextRetryAt(paymentCancel.getRetryCount()));
            }
        });
    }

    @Transactional
    public void stopCancellationRecovery(PaymentCancellationCommand command, String errorCode) {
        paymentCancelRepository.findByIdForUpdate(command.cancellationId()).ifPresent(paymentCancel -> {
            if (paymentCancel.isPending() && Objects.equals(paymentCancel.getPgIdempotencyKey(), command.pgIdempotencyKey())) {
                // 중단 직전 PG 조회에서 취소 미반영이 확인된 경우다 — ABORTED로 종결해 새 취소 요청을 막지 않는다
                paymentCancel.abort(errorCode, ZonedDateTime.now());
            }
        });
    }

    @Transactional
    public void holdCancellationForReconciliation(PaymentCancellationCommand command, String errorCode) {
        paymentCancelRepository.findByIdForUpdate(command.cancellationId()).ifPresent(paymentCancel -> {
            if (paymentCancel.isPending() && Objects.equals(paymentCancel.getPgIdempotencyKey(), command.pgIdempotencyKey())) {
                // PG 누적 취소액 불일치 — 환불이 이미 반영됐을 수 있어 자동 복구를 멈추고 수동 정합을 기다린다
                paymentCancel.stopRecovery(errorCode, ZonedDateTime.now());
            }
        });
    }

    @Transactional(readOnly = true)
    public List<PaymentApprovalCommand> findPendingApprovals(ZonedDateTime retryAt) {
        return paymentOrderRepository.findByStatusAndApprovalNextRetryAtBeforeAndApprovalRecoveryStoppedAtIsNull(
                        com.widyu.pay.PaymentOrderStatus.APPROVING,
                        retryAt
                ).stream()
                .map(this::toApprovalCommand)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentCancellationCommand> findPendingCancellations(ZonedDateTime retryAt) {
        return paymentCancelRepository.findByStatusAndNextRetryAtBeforeAndRecoveryStoppedAtIsNull(
                        PaymentCancelStatus.PENDING,
                        retryAt
                ).stream()
                .map(this::toCancellationCommand)
                .toList();
    }

    private PaymentApprovalCommand toApprovalCommand(PaymentOrder paymentOrder) {
        return new PaymentApprovalCommand(
                paymentOrder.getOrderId(),
                paymentOrder.getApprovalPaymentKey(),
                paymentOrder.getAmount(),
                paymentOrder.getApprovalPgIdempotencyKey(),
                paymentOrder.getApprovalRequestedAt(),
                paymentOrder.getApprovalLastErrorCode()
        );
    }

    private PaymentCancellationCommand toCancellationCommand(PaymentCancel paymentCancel) {
        return new PaymentCancellationCommand(
                paymentCancel.getId(),
                paymentCancel.getPayment().getPaymentKey(),
                CancelRequest.of(
                        paymentCancel.getCancelReason(),
                        paymentCancel.getCancelAmount(),
                        paymentCancel.getIdempotencyKey()
                ),
                paymentCancel.getPgIdempotencyKey(),
                paymentCancel.getPayment().getCanceledAmount() + paymentCancel.getCancelAmount(),
                paymentCancel.getCreatedAt().atZone(java.time.ZoneId.systemDefault()),
                paymentCancel.getLastErrorCode()
        );
    }

    private ZonedDateTime nextRetryAt(int retryCount) {
        long seconds = 30;
        for (int count = 0; count < retryCount && seconds < 900; count++) {
            seconds = Math.min(seconds * 2, 900);
        }
        return ZonedDateTime.now().plusSeconds(seconds);
    }

    private PaymentCancel findCancellationByIdempotencyKey(Payment payment, CancelRequest cancelRequest) {
        if (cancelRequest == null || cancelRequest.idempotencyKey() == null || cancelRequest.idempotencyKey().isBlank()) {
            return null;
        }
        return payment.getCancellations().stream()
                .filter(cancellation -> cancelRequest.idempotencyKey().equals(cancellation.getIdempotencyKey()))
                .findFirst()
                .orElse(null);
    }

    private PaymentCancel findPendingCancellation(Payment payment) {
        return payment.getCancellations().stream()
                .filter(PaymentCancel::isPending)
                .findFirst()
                .orElse(null);
    }

    private void validateExistingCancellationRequest(PaymentCancel paymentCancel, CancelRequest cancelRequest) {
        String cancelReason = cancelRequest.cancelReason();
        if (cancelReason == null || cancelReason.isBlank()) {
            cancelReason = "사용자 요청";
        }
        // 금액 생략(전액 취소)과 명시 금액은 서로 다른 요청이다 — 원본 요청의 생략 여부까지 정확히 비교한다
        if (!Objects.equals(cancelRequest.cancelAmount(), paymentCancel.getRequestedCancelAmount())
                || !cancelReason.equals(paymentCancel.getCancelReason())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "같은 멱등 키에는 동일한 취소 요청만 사용할 수 있습니다.");
        }
    }

    private CancelRequest sanitizeCancelRequest(Payment payment, CancelRequest cancelRequest) {
        String reason = null;
        Integer requestedCancelAmount = null;
        String idempotencyKey = null;
        if (cancelRequest != null) {
            reason = cancelRequest.cancelReason();
            requestedCancelAmount = cancelRequest.cancelAmount();
            idempotencyKey = cancelRequest.idempotencyKey();
        }
        if (reason == null || reason.isBlank()) {
            reason = "사용자 요청";
        }
        int remainingAmount = payment.getRemainingAmount();
        int cancelAmount = remainingAmount;
        if (requestedCancelAmount != null) {
            cancelAmount = requestedCancelAmount;
        }
        if (cancelAmount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "취소 금액은 0보다 커야 합니다.");
        }
        if (cancelAmount > remainingAmount) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "남은 결제 금액보다 크게 취소할 수 없습니다.");
        }
        return CancelRequest.of(reason, cancelAmount, idempotencyKey);
    }

    private void validatePartialCancellationIdempotencyKey(Payment payment, CancelRequest cancelRequest) {
        if (cancelRequest.cancelAmount() >= payment.getRemainingAmount()) {
            return;
        }
        if (cancelRequest.idempotencyKey() == null || cancelRequest.idempotencyKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "부분 취소에는 멱등 키가 필요합니다.");
        }
    }

    private void validatePaymentOwnership(Payment payment, Long memberId) {
        if (!payment.isOwnedBy(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 결제만 접근할 수 있습니다.");
        }
    }

    private void validateSeniorMember(Member member) {
        if (member.getType() != MemberType.SENIOR || member.getSeniorProfile() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시니어 회원만 결제할 수 있습니다.");
        }
    }

    private void validatePaymentOrderOwnership(PaymentOrder paymentOrder, Long memberId) {
        if (!paymentOrder.isOwnedBy(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 주문만 결제할 수 있습니다.");
        }
    }

    private void validatePaymentOrderState(PaymentOrder paymentOrder) {
        if (paymentOrder.isExpired()) {
            paymentOrder.markExpired();
            throw new BusinessException(ErrorCode.BAD_REQUEST, "만료된 주문입니다.");
        }
        if (!paymentOrder.isCreated()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 처리된 주문입니다.");
        }
    }

    private void validateExistingPaymentMatchesOrder(Payment payment, PaymentOrder paymentOrder) {
        if (!payment.matches(paymentOrder.getOrderId(), paymentOrder.getAmount())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "기존 결제 요청 정보와 일치하지 않습니다.");
        }
    }

    private void validateConfirmResponse(PaymentConfirmResponse response, PaymentOrder paymentOrder, String paymentKey) {
        if (response.getStatus() != PaymentStatus.DONE) {
            throw new BusinessException(ErrorCode.PAYMENT_PROCESSING, "결제 승인이 완료되지 않은 상태입니다: " + response.getStatus());
        }
        if (!Objects.equals(response.getPaymentKey(), paymentKey)
                || !Objects.equals(response.getOrderId(), paymentOrder.getOrderId())
                || response.getResolvedAmount() != paymentOrder.getAmount()) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "PG 응답과 요청 정보가 일치하지 않습니다.");
        }
    }

    private void validateCancelResponse(PaymentConfirmResponse response, String paymentKey, Payment payment, PaymentCancel paymentCancel) {
        if (!Objects.equals(response.getPaymentKey(), paymentKey)) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "취소 응답의 결제 키가 일치하지 않습니다.");
        }
        if (paymentCancel.getCancelAmount() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "취소 금액은 0보다 커야 합니다.");
        }
        if (response.getStatus() != PaymentStatus.CANCELED && response.getStatus() != PaymentStatus.PARTIAL_CANCELED) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "취소가 반영되지 않은 응답 상태입니다: " + response.getStatus());
        }
        Integer gatewayCanceledAmount = response.getGatewayCanceledAmount();
        int expectedCanceledAmount = payment.getCanceledAmount() + paymentCancel.getCancelAmount();
        if (gatewayCanceledAmount == null || gatewayCanceledAmount != expectedCanceledAmount) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "취소 응답의 누적 취소 금액이 예상과 일치하지 않습니다.");
        }
    }

    private void validateCancellationRecoveryNotStopped(PaymentCancel paymentCancel) {
        if (paymentCancel.isRecoveryStopped()) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "복구가 중단된 취소 요청입니다. 수동 정합 처리가 필요합니다.");
        }
    }

    public record ApprovalClaim(PaymentConfirmResponse existingResponse, PaymentApprovalCommand command) {
        static ApprovalClaim completed(PaymentConfirmResponse response) {
            return new ApprovalClaim(response, null);
        }

        static ApprovalClaim processing(PaymentApprovalCommand command) {
            return new ApprovalClaim(null, command);
        }

        public boolean isCompleted() {
            return existingResponse != null;
        }
    }

    public record CancellationClaim(PaymentConfirmResponse existingResponse, PaymentCancellationCommand command) {
        static CancellationClaim completed(PaymentConfirmResponse response) {
            return new CancellationClaim(response, null);
        }

        static CancellationClaim processing(PaymentCancellationCommand command) {
            return new CancellationClaim(null, command);
        }

        public boolean isCompleted() {
            return existingResponse != null;
        }
    }
}
