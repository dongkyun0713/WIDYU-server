package com.widyu.fcm.event.goal.walk.listener;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.NotificationType;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.member.MemberType;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalkNotificationListener {

    private static final String WALK_DEFAULT_IMAGE = "walk.png";

    private final FcmService fcmService;
    private final WalkRepository walkRepository;

    /**
     * 매일 저녁 7시에 실행되어 오늘 만보계 목표 미달성자에게 알림 발송
     */
    @Scheduled(cron = "0 0 19 * * *")
    @Transactional
    public void sendWalkGoalReminderToUnachieved() {
        LocalDate today = LocalDate.now();

        log.info("만보계 목표 미달성 알림 스케줄러 실행 - 대상 날짜: {}", today);

        // 오늘 날짜의 미달성 Walk 기록 조회
        List<Walk> unachievedWalks = walkRepository.findUnachievedWalksByDate(today);

        if (unachievedWalks.isEmpty()) {
            log.info("오늘 만보계 목표 미달성자가 없습니다.");
            return;
        }

        log.info("{}명의 시니어에게 만보계 목표 미달성 알림을 발송합니다.", unachievedWalks.size());

        for (Walk walk : unachievedWalks) {
            try {
                sendUnachievedNotification(walk);
            } catch (Exception e) {
                log.error("만보계 미달성 알림 발송 실패 - walkId: {}, memberId: {}",
                        walk.getId(), walk.getMember().getId(), e);
            }
        }
    }

    /**
     * 개별 미달성자에게 알림 발송
     */
    private void sendUnachievedNotification(Walk walk) {
        if (walk.getMember().getType() != MemberType.SENIOR
                || walk.getGoalSteps() == null || walk.getActualSteps() == null
                || walk.getActualSteps() <= 0 || walk.getActualSteps() >= walk.getGoalSteps()) {
            return;
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.WALK_GOAL_UNMET, "W01",
                Map.of("실제 걸음 수", walk.getActualSteps().toString()));
        FcmSendDto dto = FcmSendDto.of(NotificationType.WALK_GOAL_UNMET, copy,
                null, null, null, null, WALK_DEFAULT_IMAGE);
        fcmService.sendMessageToUser(walk.getMember().getId(), dto);
    }
}
