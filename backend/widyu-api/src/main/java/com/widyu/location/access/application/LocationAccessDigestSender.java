package com.widyu.location.access.application;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.repository.LocationAccessLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 시니어 한 명의 위치 열람 요약 선점과 FCM 등록을 하나의 트랜잭션으로 처리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocationAccessDigestSender {

    private static final String NOTICE_TITLE = "위치 조회 알림";

    private final LocationAccessLogRepository locationAccessLogRepository;
    private final FcmService fcmService;

    /**
     * 미통보 행을 먼저 조건부 선점하고 같은 트랜잭션에서 FCM outbox에 등록한다.
     * 등록이 실패하면 선점도 롤백되므로 다음 스케줄 실행에서 다시 시도할 수 있다.
     */
    @Transactional
    public void send(Long seniorMemberId, LocalDateTime now) {
        int claimed = locationAccessLogRepository.claimUnnotified(seniorMemberId, now);
        if (claimed == 0) {
            log.info("위치 열람 요약 통보 건너뜀 - 다른 인스턴스가 선점함 seniorMemberId={}", seniorMemberId);
            return;
        }

        List<LocationAccessLog> logs =
                locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(seniorMemberId, now);
        long viewerCount = logs.stream()
                .map(LocationAccessLog::getViewerMemberId)
                .distinct()
                .count();

        fcmService.sendMessageToUser(seniorMemberId, FcmSendDto.builder()
                .title(NOTICE_TITLE)
                .content(String.format("지난 기간 보호자 %d명이 위치를 %d회 확인했어요", viewerCount, logs.size()))
                .fcmCategory(FcmCategory.LOCATION_NOTICE)
                .scheme("")
                .image(null)
                .emergency(false)
                .build());

        log.info("위치 열람 요약 통보 seniorMemberId={} viewerCount={} accessCount={}",
                seniorMemberId, viewerCount, logs.size());
    }
}
