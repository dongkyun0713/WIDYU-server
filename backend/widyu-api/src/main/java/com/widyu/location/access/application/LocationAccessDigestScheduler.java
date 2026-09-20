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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 아직 알리지 않은 열람 기록을 시니어별로 묶어 하루 한 번 요약 통보한다(LLD-0056 5.4).
 *
 * <p>대상은 「모아서」에 동의한 시니어의 행만이 아니다. 즉시 모드에서 쿨다운 때문에 FCM이
 * 나가지 않은 행도 {@code notified_at}이 비어 있으므로 함께 묶인다 — 합치느라 빠뜨린 건도
 * 결국 본인에게 알리기 위해서다.
 *
 * <p><b>보내기 전에 선점한다.</b> 읽고-보내고-갱신하면 인스턴스가 둘 이상일 때 같은 행을
 * 저마다 읽어 시니어가 같은 알림을 여러 번 받는다. 조건부 UPDATE로 먼저 집고, 집은 건수가
 * 0이면(다른 인스턴스가 가져갔으면) 보내지 않는다.
 *
 * <p>메서드에 트랜잭션을 걸지 않는다. 하나로 묶으면 한 시니어의 실패가 트랜잭션을
 * rollback-only로 만들어 그날 전체가 함께 무너진다. 선점은 자기 트랜잭션에서 끝나므로
 * 시니어마다 독립적으로 성공·실패한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationAccessDigestScheduler {

    private static final String NOTICE_TITLE = "위치 조회 알림";

    private final LocationAccessLogRepository locationAccessLogRepository;
    private final FcmService fcmService;

    @Scheduled(cron = "${location-access.digest-cron}")
    public void sendDigest() {
        List<Long> seniorIds = locationAccessLogRepository.findSeniorIdsWithUnnotified();
        if (seniorIds.isEmpty()) {
            log.info("위치 열람 요약 통보 대상 없음");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (Long seniorMemberId : seniorIds) {
            try {
                notifySenior(seniorMemberId, now);
            } catch (Exception e) {
                log.warn("위치 열람 요약 통보 실패 seniorMemberId={} cause={}",
                        seniorMemberId, e.getClass().getSimpleName());
            }
        }
    }

    private void notifySenior(Long seniorMemberId, LocalDateTime now) {
        int claimed = locationAccessLogRepository.claimUnnotified(seniorMemberId, now);
        if (claimed == 0) {
            log.info("위치 열람 요약 통보 건너뜀 - 다른 인스턴스가 선점함 seniorMemberId={}", seniorMemberId);
            return;
        }

        // 선점 전 목록이 아니라 실제로 집은 행을 다시 읽어 센다. 그 사이 늘어난 행은
        // notified_at이 비어 있어 다음 회차 몫이다.
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
