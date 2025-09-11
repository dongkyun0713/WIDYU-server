package com.widyu.domain.member.repository;

import com.widyu.domain.member.domain.LocalAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocalAccountRepository extends JpaRepository<LocalAccount, Long> {
    boolean existsByEmail(String email);

    Optional<LocalAccount> findByEmail(String email);
}
