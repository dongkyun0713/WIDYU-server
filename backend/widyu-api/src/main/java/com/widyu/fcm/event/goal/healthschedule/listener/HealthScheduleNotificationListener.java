package com.widyu.fcm.event.goal.healthschedule.listener;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.NotificationType;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthScheduleNotificationListener {

    private static final String HEALTH_SCHEDULE_DEFAULT_IMAGE = "health_schedule.png";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
    static final String GUARDIAN_HEALTH_SCHEDULE_DETAIL = "widyu-care://health/schedules/";

    private final FcmService fcmService;
    private final HealthScheduleRepository healthScheduleRepository;

    /**
     * 매 시간 정각에 실행되어 1시간 후 시작하는 건강 일정에 대해 알림 발송
     * 예: 14:00에 실행 -> 15:00~15:10 사이에 시작하는 일정에 알림
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
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
        if (schedule.getScheduledAt() == null || schedule.getScheduleName() == null
                || schedule.getScheduleName().isBlank()) {
            log.warn("건강 일정 알림 필수 정보 누락: scheduleId={}", schedule.getId());
            return;
        }
        Member recipient = schedule.getMember();
        String variant = "H01-S";
        String deepLink = null;
        if (recipient.getType() == MemberType.GUARDIAN) {
            variant = "H01-C-SELF";
            deepLink = GUARDIAN_HEALTH_SCHEDULE_DETAIL + schedule.getId();
        }
        NotificationCopy osCopy = NotificationCopy.of(NotificationType.HEALTH_SCHEDULE_UPCOMING,
                variant + "-OS", Map.of());
        NotificationCopy inAppCopy = NotificationCopy.of(NotificationType.HEALTH_SCHEDULE_UPCOMING,
                variant + "-INAPP", Map.of(
                        "오전/오후 시각", schedule.getScheduledAt().format(TIME_FORMATTER),
                        "일정명", schedule.getScheduleName()));
        FcmSendDto dto = FcmSendDto.of(NotificationType.HEALTH_SCHEDULE_UPCOMING, osCopy,
                schedule.getId().toString(), deepLink, null, null, HEALTH_SCHEDULE_DEFAULT_IMAGE)
                .withCenterCopy(inAppCopy);
        fcmService.sendMessageToUser(recipient.getId(), dto);
    }
}
