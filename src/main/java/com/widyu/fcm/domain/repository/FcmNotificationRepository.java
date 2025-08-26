package com.widyu.fcm.domain.repository;

import com.widyu.fcm.domain.FcmNotification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FcmNotificationRepository extends JpaRepository<FcmNotification, Long> {
    List<FcmNotification> findByTokenOrderByCreatedAtDesc(String token);
}

