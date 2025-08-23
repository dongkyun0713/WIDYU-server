package com.widyu.pay.domain.repository;

import com.widyu.pay.domain.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    List<Payment> findByMemberId(Long memberId);
}
