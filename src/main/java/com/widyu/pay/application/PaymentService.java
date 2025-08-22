package com.widyu.pay.application;

import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.pay.config.PaymentClient;
import com.widyu.pay.domain.Payment;
import com.widyu.pay.domain.repository.PaymentRepository;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentClient paymentClient;
    private final PaymentRepository paymentRepository;
    private final MemberUtil memberUtil;

    @Transactional
    public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest paymentConfirmRequest) {
        PaymentConfirmResponse paymentConfirmResponse = paymentClient.confirmPayment(paymentConfirmRequest);
        Member currentMember = memberUtil.getCurrentMember();
        Payment payment = paymentConfirmResponse.toPayment(currentMember);

        paymentRepository.save(payment);

        return paymentConfirmResponse;
    }

    @Transactional
    public PaymentConfirmResponse cancelPayment(String paymentKey, CancelRequest cancelRequest) {
        PaymentConfirmResponse response = paymentClient.cancelPayment(paymentKey, cancelRequest);

        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("결제 내역을 찾을 수 없습니다."));

        payment.cancel(cancelRequest.cancelReason());

        return response;
    }

    public List<PaymentConfirmResponse> getPaymentsByUser() {
        Member currentMember = memberUtil.getCurrentMember();
        List<Payment> payments = paymentRepository.findByMemberId((currentMember.getId()));

        if (payments.isEmpty()) {
            throw new IllegalArgumentException("해당 유저의 결제 내역을 찾을 수 없습니다.");
        }

        return payments.stream()
                .map(PaymentConfirmResponse::fromEntity)
                .toList();
    }
}
