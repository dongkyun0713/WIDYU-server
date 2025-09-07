package com.widyu.pay.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.pay.api.dto.mapper.PaymentMapper;
import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.pay.api.dto.response.PaymentConfirmResponses;
import com.widyu.pay.config.PaymentClient;
import com.widyu.pay.domain.Payment;
import com.widyu.pay.domain.repository.PaymentRepository;
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
    public PaymentConfirmResponse confirmPayment(PaymentConfirmRequest request) {
        PaymentConfirmResponse rawResponse = paymentClient.confirmPayment(request);

        Member currentMember = memberUtil.getCurrentMember();

        Payment payment = PaymentMapper.toEntity(rawResponse, currentMember);
        paymentRepository.save(payment);

        return PaymentConfirmResponse.from(payment);
    }


    @Transactional
    public PaymentConfirmResponse cancelPayment(String paymentKey, CancelRequest cancelRequest) {
        PaymentConfirmResponse response = paymentClient.cancelPayment(paymentKey, cancelRequest);

        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        payment.cancel(cancelRequest.cancelReason());

        return PaymentConfirmResponse.from(payment);
    }

    public PaymentConfirmResponses getPaymentsByUser() {
        Member currentMember = memberUtil.getCurrentMember();
        List<Payment> payments = paymentRepository.findByMemberId(currentMember.getId());

        if (payments.isEmpty()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        return PaymentConfirmResponses.from(payments);
    }
}
