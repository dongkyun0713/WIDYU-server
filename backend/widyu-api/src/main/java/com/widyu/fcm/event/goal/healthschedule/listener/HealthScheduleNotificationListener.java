package com.widyu.fcm.event.goal.healthschedule.listener;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.FcmCategory;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.healthschedule.HealthSchedule;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@org.springframework.context.annotation.Profile("!pilot") // 실증 제외: 자동 알림
@Component
@RequiredArgsConstructor
public class HealthScheduleNotificationListener {

    private static final String HEALTH_SCHEDULE_DEFAULT_IMAGE = "health_schedule.png";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final FcmService fcmService;
    private final HealthScheduleRepository healthScheduleRepository;

    /**
     * 매 시간 정각에 실행되어 1시간 후 시작하는 건강 일정에 대해 알림 발송
     * 예: 14:00에 실행 -> 15:00~15:10 사이에 시작하는 일정에 알림
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional(readOnly = true)
    public void sendHealthScheduleReminder() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourLater = now.plusHours(1);
        LocalDateTime notificationEndTime = oneHourLater.plusMinutes(10);

        log.info("건강 일정 알림 스케줄러 실행 - 대상 시간: {} ~ {}",
                oneHourLater.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                notificationEndTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 1시간 후부터 1시간 10분 후 사이에 시작하는 예정된 일정 조회
        List<HealthSchedule> upcomingSchedules = healthScheduleRepository
                .findUpcomingSchedulesInTimeRange(oneHourLater, notificationEndTime);

        if (upcomingSchedules.isEmpty()) {
            log.info("알림을 보낼 건강 일정이 없습니다.");
            return;
        }

        log.info("{}개의 건강 일정에 대해 알림을 발송합니다.", upcomingSchedules.size());

        for (HealthSchedule schedule : upcomingSchedules) {
            try {
                sendScheduleNotification(schedule);
            } catch (Exception e) {
                log.error("건강 일정 알림 발송 실패 - scheduleId: {}, memberId: {}",
                        schedule.getId(), schedule.getMember().getId(), e);
            }
        }
    }

    /**
     * 개별 일정에 대한 알림 발송
     */
    private void sendScheduleNotification(HealthSchedule schedule) {
        String scheduledTime = schedule.getScheduledAt().format(TIME_FORMATTER);
        String title = String.format("%s에 '%s' 일정이 있어요!",
                scheduledTime, schedule.getScheduleName());
        String content = "건강 일정을 잊지 말고 확인해주세요.";

        FcmSendDto fcmSendDto = FcmSendDto.builder()
                .title(title)
                .content(content)
                .fcmCategory(FcmCategory.HEALTH_SCHEDULE)
                .scheme("")
                .image(HEALTH_SCHEDULE_DEFAULT_IMAGE)
                .build();

        fcmService.sendMessageToUser(schedule.getMember().getId(), fcmSendDto);
    }
}
