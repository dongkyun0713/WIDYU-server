package com.widyu.heart.scheduler;

import com.widyu.global.properties.HeartProperties;
import com.widyu.heart.repository.HeartRateEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class HeartRateCleanupScheduler {

    private static final int RETENTION_DAYS = 30;

    private final HeartRateEventRepository heartRateEventRepository;
    private final HeartProperties heartProperties;

    /**
     * 매일 새벽 3시 실행: 30일 이상 지난 심박수 이벤트 삭제
     * HeartRateEmergency(위급상황 기록)는 안전 기록이므로 삭제하지 않음
     * heart.cleanup.exempt-member-ids에 지정된 실증 참가자는 삭제 대상에서 제외
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void deleteOldHeartRateEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<Long> exemptMemberIds = heartProperties.cleanup().exemptMemberIds();
        int deleted = deleteBefore(cutoff, exemptMemberIds);
        log.info(
                "심박수 이벤트 정리 완료 - {}일 이전 데이터 {}건 삭제, 제외 회원 {}명",
                RETENTION_DAYS,
                deleted,
                exemptMemberIds.size());
    }

    private int deleteBefore(LocalDateTime cutoff, List<Long> exemptMemberIds) {
        if (exemptMemberIds.isEmpty()) {
            return heartRateEventRepository.deleteByMeasuredAtBefore(cutoff);
        }
        return heartRateEventRepository.deleteByMeasuredAtBeforeAndMemberIdNotIn(cutoff, exemptMemberIds);
    }
}
