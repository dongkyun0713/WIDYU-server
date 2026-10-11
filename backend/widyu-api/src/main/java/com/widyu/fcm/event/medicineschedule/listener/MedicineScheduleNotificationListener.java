package com.widyu.fcm.event.medicineschedule.listener;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.global.entity.Status;
import com.widyu.global.properties.SensorProperties;
import com.widyu.goal.medicineschedule.application.MedicationAlarmPayload;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicineScheduleNotificationListener {

    private static final String MEDICINE_DEFAULT_IMAGE = "medicine.png";

    private final FcmService fcmService;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MedicationProofRepository medicationProofRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SensorProperties sensorProperties;

    /**
     * 매 분마다 실행하여 의약품 복용 알림 발송
     * 앱 기기 알람이 배포될 때까지 정시 서버 푸시를 유지한다.
     * +10분·+20분 미인증 푸시와 +30분 보호자 알림은 스위치와 무관하게 보낸다.
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkMedicineSchedules() {
        checkMedicineSchedulesAt(LocalDateTime.now());
    }

    void checkMedicineSchedulesAt(LocalDateTime now) {
        LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        log.debug("의약품 복용 후속 알림 체크 시작: {}", minute);
        if (sensorProperties.medication().onTimePush()) {
            sendOnTimeNotification(minute);
        }
        sendNotificationForTime(minute.minusMinutes(10), NotificationType.MEDICATION_REMINDER_10);
        sendNotificationForTime(minute.minusMinutes(20), NotificationType.MEDICATION_REMINDER_20);
        sendGuardianAlertOnly(minute.minusMinutes(30));
    }

    private void sendOnTimeNotification(LocalDateTime dueAt) {
        LocalDate date = dueAt.toLocalDate();
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findByAlarmTimeAndStatusEffectiveOn(dueAt.toLocalTime(), Status.ACTIVE, date);

        if (schedules.isEmpty()) {
            return;
        }

        List<Long> scheduleIds = schedules.stream()
                .map(MedicineSchedule::getId)
                .toList();
        Set<Long> verifiedScheduleIds = new HashSet<>(
                medicationProofRepository.findVerifiedScheduleIds(
                        scheduleIds, date.atStartOfDay(), date.atTime(LocalTime.MAX))
        );

        for (MedicineSchedule schedule : schedules) {
            if (verifiedScheduleIds.contains(schedule.getId())) {
                continue;
            }
            sendLegacyOnTimeNotification(schedule);
        }
    }

    private void sendLegacyOnTimeNotification(MedicineSchedule schedule) {
        Long seniorId = schedule.getMember().getId();
        FcmSendDto dto = FcmSendDto.builder()
                .title("약 복용 시간이에요!")
                .content("지금 약을 복용하고 인증해주세요.")
                .fcmCategory(FcmCategory.MEDICINE_SCHEDULE)
                .scheme("").image(MEDICINE_DEFAULT_IMAGE)
                .data(MedicationAlarmPayload.of(schedule.getMember().getMedicationAlarmRevision()))
                .build();

        fcmService.sendMessageToUser(seniorId, dto);

        log.info("의약품 복용 정시 알림 발송: scheduleId={}, memberId={}, alarmTime={}",
                schedule.getId(), seniorId, schedule.getAlarmTime());
    }

    private void sendNotificationForTime(LocalDateTime dueAt, NotificationType type) {
        LocalDate date = dueAt.toLocalDate();
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findByAlarmTimeAndStatusEffectiveOn(dueAt.toLocalTime(), Status.ACTIVE, date);

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
                sendMedicineNotification(schedule, date, type);
            }
        }
    }

    private void sendGuardianAlertOnly(LocalDateTime dueAt) {
        LocalDate date = dueAt.toLocalDate();
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findByAlarmTimeAndStatusEffectiveOn(dueAt.toLocalTime(), Status.ACTIVE, date);

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
                sendGuardianNotification(schedule, date);
            }
        }
    }

    private void sendMedicineNotification(MedicineSchedule schedule, LocalDate date, NotificationType type) {
        Long seniorId = schedule.getMember().getId();
        NotificationCopy copy = NotificationCopy.of(type, type.copyCode(), Map.of());
        FcmSendDto dto = FcmSendDto.builder()
                .title(copy.title()).content(copy.body())
                .fcmCategory(FcmCategory.MEDICINE_SCHEDULE).image(MEDICINE_DEFAULT_IMAGE)
                .notificationType(type).entityId(schedule.getId().toString())
                .eventId(timedEventId(type, schedule.getId(), seniorId, date))
                .data(MedicationAlarmPayload.of(schedule.getMember().getMedicationAlarmRevision(), type))
                .build();

        fcmService.sendMessageToUser(seniorId, dto);

        log.info("의약품 복용 인증 요청: scheduleId={}, memberId={}, type={}, alarmTime={}",
                schedule.getId(), seniorId, type, schedule.getAlarmTime());
    }

    private void sendGuardianNotification(MedicineSchedule schedule, LocalDate date) {
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

        NotificationType type = NotificationType.MEDICATION_PROOF_MISSING;
        Map<String, String> values = Map.of();
        String seniorName = schedule.getMember().getName();
        if (seniorName != null && !seniorName.isBlank()) {
            values = Map.of("시니어 이름", seniorName);
        }
        NotificationCopy copy = NotificationCopy.of(type, type.copyCode(), values);

        for (FamilyMembership membership : memberships) {
            Long guardianId = membership.getGuardian().getId();
            FcmSendDto dto = FcmSendDto.builder()
                    .title(copy.title()).content(copy.body())
                    .fcmCategory(FcmCategory.MEDICINE_SCHEDULE)
                    .image(schedule.getMember().getProfileImage())
                    .notificationType(type).entityId(schedule.getId().toString())
                    .seniorId(seniorMemberId).relatedMemberId(seniorMemberId)
                    .eventId(timedEventId(type, schedule.getId(), guardianId, date))
                    .data(MedicationAlarmPayload.of(membership.getGuardian().getMedicationAlarmRevision(), type))
                    .build();

            fcmService.sendMessageToUser(guardianId, dto);

            log.info("보호자 미인증 알림 발송: seniorMemberId={}, guardianId={}, scheduleId={}, alarmTime={}",
                    seniorMemberId, guardianId,
                    schedule.getId(), schedule.getAlarmTime());
        }
    }

    private String timedEventId(NotificationType type, Long scheduleId, Long recipientId, LocalDate date) {
        return MedicationAlarmPayload.eventId(type.name() + ":" + scheduleId + ":" + recipientId + ":" + date);
    }
}
