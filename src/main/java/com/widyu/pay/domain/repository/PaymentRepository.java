package com.widyu.pay.domain.repository;

import com.widyu.pay.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
