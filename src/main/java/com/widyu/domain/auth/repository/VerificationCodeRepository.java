package com.widyu.domain.auth.repository;

import com.widyu.domain.auth.domain.VerificationCode;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VerificationCodeRepository extends CrudRepository<VerificationCode, String> {
}