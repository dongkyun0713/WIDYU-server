package com.widyu.fcm.event.medicineschedule.listener;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.entity.Status;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@org.springframework.context.annotation.Profile("!pilot") // 실증 제외: 자동 알림
@Component
@RequiredArgsConstructor
public class MedicineScheduleNotificationListener {

    private static final String MEDICINE_DEFAULT_IMAGE = "medicine.png";

    private final FcmService fcmService;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MedicationProofRepository medicationProofRepository;
    private final FamilyMembershipRepository familyMembershipRepository;

    /**
     * 매 분마다 실행하여 의약품 복용 알림 발송
     * - 알람 시간 (정시): 1차 알림 (시니어)
     * - 알람 시간 + 10분: 2차 알림 (시니어)
     * - 알람 시간 + 20분: 3차 알림 (시니어)
     * - 알람 시간 + 30분: 보호자 알림
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkMedicineSchedules() {
        LocalTime currentTime = LocalTime.now();
        LocalDate today = LocalDate.now();

        log.debug("의약품 복용 알림 체크 시작: {}", currentTime);

        // 1차 알림: 알람 시간 (정시)
        sendNotificationForTime(currentTime, today, 1);

        // 2차 알림: 알람 시간 + 10분
        sendNotificationForTime(currentTime.minusMinutes(10), today, 2);

        // 3차 알림: 알람 시간 + 20분
        sendNotificationForTime(currentTime.minusMinutes(20), today, 3);

        // 보호자 알림: 알람 시간 + 30분
        sendGuardianAlertOnly(currentTime.minusMinutes(30), today);
    }

    private void sendNotificationForTime(LocalTime alarmTime, LocalDate date, int attemptNumber) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findByAlarmTimeAndStatusEffectiveOn(alarmTime, Status.ACTIVE, date);

        if (schedules.isEmpty()) {
            return;
        }

        List<Long> scheduleIds = schedules.stream()
                .map(MedicineSchedule::getId)
                .toList();

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        Set<Long> verifiedScheduleIds = new HashSet<>(
                medicationProofRepository.findVerifiedScheduleIds(scheduleIds, startOfDay, endOfDay)
        );

        // 인증되지 않은 스케줄만 알림 발송
        for (MedicineSchedule schedule : schedules) {
            if (!verifiedScheduleIds.contains(schedule.getId())) {
                sendMedicineNotification(schedule, attemptNumber);
            }
        }
    }

    private void sendGuardianAlertOnly(LocalTime alarmTime, LocalDate date) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findByAlarmTimeAndStatusEffectiveOn(alarmTime, Status.ACTIVE, date);

        if (schedules.isEmpty()) {
            return;
        }

        List<Long> scheduleIds = schedules.stream()
                .map(MedicineSchedule::getId)
                .toList();

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        Set<Long> verifiedScheduleIds = new HashSet<>(
                medicationProofRepository.findVerifiedScheduleIds(scheduleIds, startOfDay, endOfDay)
        );

        // 인증되지 않은 스케줄만 보호자에게 알림 발송
        for (MedicineSchedule schedule : schedules) {
            if (!verifiedScheduleIds.contains(schedule.getId())) {
                sendGuardianNotification(schedule);
            }
        }
    }

    private void sendMedicineNotification(MedicineSchedule schedule, int attemptNumber) {
        String title = "약 복용 알림 (" + attemptNumber + "차)";
        if (attemptNumber == 1) {
            title = "약 복용 시간이에요!";
        }
        String content = "지금 약을 복용하고 인증해주세요.";

        FcmSendDto dto = new FcmSendDto(
                title,
                content,
                FcmCategory.MEDICINE_SCHEDULE,
                "",
                MEDICINE_DEFAULT_IMAGE
        );

        fcmService.sendMessageToUser(schedule.getMember().getId(), dto);

        log.info("의약품 복용 알림 발송: scheduleId={}, memberId={}, attempt={}, alarmTime={}",
                schedule.getId(), schedule.getMember().getId(), attemptNumber, schedule.getAlarmTime());
    }

    private void sendGuardianNotification(MedicineSchedule schedule) {
        Long seniorMemberId = schedule.getMember().getId();

        // 시니어 프로필이 있는지 확인
        if (schedule.getMember().getSeniorProfile() == null) {
            log.debug("보호자 알림 스킵: 시니어 프로필이 없는 회원입니다. memberId={}", seniorMemberId);
            return;
        }

        Long familyId = schedule.getMember().getSeniorProfile().getFamily().getId();

        List<FamilyMembership> memberships = familyMembershipRepository
                .findAllByFamilyIdWithGuardian(familyId);

        if (memberships.isEmpty()) {
            log.debug("보호자 알림 스킵: 연결된 보호자가 없습니다. familyId={}", familyId);
            return;
        }

        String title = schedule.getMember().getName() + "님이 약을 복용하지 않았어요";
        String content = "3회 알림에도 복용 인증을 하지 않았습니다. 확인해주세요.";

        for (FamilyMembership membership : memberships) {
            FcmSendDto dto = new FcmSendDto(
                    title,
                    content,
                    FcmCategory.MEDICINE_SCHEDULE,
                    "",
                    schedule.getMember().getProfileImage()
            );

            fcmService.sendMessageToUser(membership.getGuardian().getId(), dto);

            log.info("보호자 미인증 알림 발송: seniorMemberId={}, guardianId={}, scheduleId={}, alarmTime={}",
                    seniorMemberId, membership.getGuardian().getId(),
                    schedule.getId(), schedule.getAlarmTime());
        }
    }
}
