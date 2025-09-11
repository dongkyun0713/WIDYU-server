package com.widyu.domain.fcm.domain.repository;

import com.widyu.domain.fcm.domain.MemberFcmToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberFcmTokenRepository extends JpaRepository<MemberFcmToken, Long> {
    Optional<MemberFcmToken> findByToken(String fcmToken);

    List<MemberFcmToken> findAllByMemberIdAndActiveTrue(Long id);

    List<MemberFcmToken> findAllByLastUsedAtBeforeAndActiveTrue(LocalDateTime threshold);
}

