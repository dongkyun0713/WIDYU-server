package com.widyu.location.access.application;

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
 * <p>시니어 한 명의 선점과 FCM 등록은 별도 빈의 트랜잭션으로 묶는다. 실패하면 그 시니어의
 * 선점만 롤백되어 다음 실행에서 재시도되고, 스케줄러의 try/catch가 다음 시니어 처리를 이어 간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationAccessDigestScheduler {

    private final LocationAccessLogRepository locationAccessLogRepository;
    private final LocationAccessDigestSender locationAccessDigestSender;

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
                locationAccessDigestSender.send(seniorMemberId, now);
            } catch (Exception e) {
                log.warn("위치 열람 요약 통보 실패 seniorMemberId={} cause={}",
                        seniorMemberId, e.getClass().getSimpleName());
            }
        }
    }
}
