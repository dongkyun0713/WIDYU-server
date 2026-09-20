package com.widyu.location.access.application;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.repository.LocationAccessLogRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아직 알리지 않은 열람 기록을 시니어별로 묶어 하루 한 번 요약 통보한다(LLD-0056 5.4).
 *
 * <p>대상은 「모아서」에 동의한 시니어의 행만이 아니다. 즉시 모드에서 쿨다운 때문에 FCM이
 * 나가지 않은 행도 {@code notified_at}이 비어 있으므로 함께 묶인다 — 합치느라 빠뜨린 건도
 * 결국 본인에게 알리기 위해서다.
 *
 * <p>한 시니어의 실패가 다음 시니어를 막지 않는다. 통보를 못 받은 시니어의 행은
 * {@code notified_at}이 비어 있으므로 다음 회차에 다시 잡힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationAccessDigestScheduler {

    private static final String NOTICE_TITLE = "위치 조회 알림";

    private final LocationAccessLogRepository locationAccessLogRepository;
    private final FcmService fcmService;

    @Scheduled(cron = "${location-access.digest-cron}")
    @Transactional
    public void sendDigest() {
        List<LocationAccessLog> pending =
                locationAccessLogRepository.findByNotifiedAtIsNullOrderBySeniorMemberIdAscAccessedAtAsc();
        if (pending.isEmpty()) {
            log.info("위치 열람 요약 통보 대상 없음");
            return;
        }

        Map<Long, List<LocationAccessLog>> bySenior = pending.stream()
                .collect(Collectors.groupingBy(
                        LocationAccessLog::getSeniorMemberId, LinkedHashMap::new, Collectors.toList()));

        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, List<LocationAccessLog>> entry : bySenior.entrySet()) {
            try {
                notifySenior(entry.getKey(), entry.getValue(), now);
            } catch (Exception e) {
                log.warn("위치 열람 요약 통보 실패 seniorMemberId={} cause={}",
                        entry.getKey(), e.getClass().getSimpleName());
            }
        }
    }

    private void notifySenior(Long seniorMemberId, List<LocationAccessLog> logs, LocalDateTime now) {
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

        List<Long> ids = logs.stream().map(LocationAccessLog::getId).toList();
        locationAccessLogRepository.markNotified(ids, now);
        log.info("위치 열람 요약 통보 seniorMemberId={} viewerCount={} accessCount={}",
                seniorMemberId, viewerCount, logs.size());
    }
}
