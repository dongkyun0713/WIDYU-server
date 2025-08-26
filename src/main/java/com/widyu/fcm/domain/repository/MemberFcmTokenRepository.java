package com.widyu.fcm.domain.repository;

import com.widyu.fcm.domain.FcmNotification;
import com.widyu.fcm.domain.MemberFcmToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberFcmTokenRepository extends JpaRepository<MemberFcmToken, Long> {
    boolean existsByToken(String fcmToken);

    Optional<MemberFcmToken> findByToken(String fcmToken);
}

