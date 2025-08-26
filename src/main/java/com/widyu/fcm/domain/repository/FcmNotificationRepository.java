package com.widyu.fcm.domain.repository;

import com.widyu.fcm.domain.FcmNotification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FcmNotificationRepository extends JpaRepository<FcmNotification, Long> {
}

