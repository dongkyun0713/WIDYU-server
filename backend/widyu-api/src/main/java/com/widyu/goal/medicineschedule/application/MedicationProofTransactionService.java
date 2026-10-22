package com.widyu.goal.medicineschedule.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.fcm.event.goal.dto.GoalAchievedEvent;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicationStatus;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationProofTransactionService {

    private static final int ALLOWED_TIME_WINDOW_MINUTES = MedicationStatus.ALLOWED_WINDOW_MINUTES;
    private static final String POINT_DESCRIPTION = "약 복용 인증";
    private static final String POINT_OPERATION_KEY_PREFIX = "MEDICATION_PROOF:";

    private final MedicationProofRepository medicationProofRepository;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MemberRepository memberRepository;
    private final SeniorProfileService seniorProfileService;
    private final ApplicationEventPublisher eventPublisher;

    public void validateBeforeUpload(Long memberId, Long scheduleId) {
        validateBeforeUploadAt(memberId, scheduleId, LocalDateTime.now());
    }

    // 테스트가 시각을 고정할 수 있게 now를 받는다. 트랜잭션은 위 public 메서드가 연다.
    void validateBeforeUploadAt(Long memberId, Long scheduleId, LocalDateTime now) {
        Member currentMember = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
        MedicineSchedule schedule = medicineScheduleRepository
                .findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 약 복용 스케줄입니다."));

        validateScheduleOwner(schedule, currentMember);
        LocalDate doseDate = resolveDoseDate(schedule, now);
        validateNotVerified(schedule, doseDate);
    }

    @Transactional
    public MedicationProofResponse verifyMedication(Long memberId, Long scheduleId, List<String> imageUrls) {
        return verifyMedicationAt(memberId, scheduleId, imageUrls, LocalDateTime.now());
    }

    // 테스트가 시각을 고정할 수 있게 now를 받는다. 트랜잭션은 위 public 메서드가 연다.
    MedicationProofResponse verifyMedicationAt(Long memberId, Long scheduleId, List<String> imageUrls,
                                               LocalDateTime now) {
        Member currentMember = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
        MedicineSchedule schedule = medicineScheduleRepository
                .findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 약 복용 스케줄입니다."));

        validateScheduleOwner(schedule, currentMember);
        LocalDate doseDate = resolveDoseDate(schedule, now);
        validateNotVerified(schedule, doseDate);

        MedicationProof proof = MedicationProof.create(schedule, currentMember, imageUrls, now, doseDate);
        saveProof(proof, scheduleId);
        currentMember.incrementMedicationAlarmRevision();

        // 즉시 적립의 하루 완료 판정도 복용 기준일로 센다(자정 넘어 인증한 어제 분은 어제 몫).
        long proofCount = medicationProofRepository.countByMemberAndVerifiedDate(currentMember, doseDate);
        long totalSchedules = medicineScheduleRepository.countEffectiveByMemberAndDate(
                currentMember, Status.ACTIVE, doseDate);
        long earnedPoints = MedicationPointPolicy.calculateEarnedPoints(proofCount, totalSchedules);
        rewardPoints(currentMember, proof, earnedPoints, proofCount == totalSchedules && totalSchedules > 0);
        log.info("약 복용 인증 완료: scheduleId={}, memberId={}, verifiedAt={}, doseDate={}, earnedPoints={}",
                scheduleId, currentMember.getId(), now, doseDate, earnedPoints);
        return MedicationProofResponse.of(currentPoints(currentMember), earnedPoints);
    }

    private void validateScheduleOwner(MedicineSchedule schedule, Member member) {
        if (!schedule.getMember().getId().equals(member.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 스케줄에 접근할 권한이 없습니다.");
        }
    }

    /**
     * 복용 기준일: now가 알람 ±30분 안에 드는 알람의 날짜(어제·오늘·내일 중, LLD-0101).
     * 23:45 알람을 00:05에 인증하면 어제, 00:10 알람을 23:50에 인증하면 내일이다.
     * 창(60분)이 하루보다 훨씬 짧아 맞는 날짜는 많아야 하나다.
     */
    private LocalDate resolveDoseDate(MedicineSchedule schedule, LocalDateTime now) {
        for (int dayOffset = -1; dayOffset <= 1; dayOffset++) {
            LocalDate date = now.toLocalDate().plusDays(dayOffset);
            if (isWithinWindow(date.atTime(schedule.getAlarmTime()), now)) {
                validateEffectiveOn(schedule, date);
                return date;
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                String.format("약 복용 인증은 알람 시간 전후 %d분 이내에만 가능합니다.", ALLOWED_TIME_WINDOW_MINUTES));
    }

    private boolean isWithinWindow(LocalDateTime alarmAt, LocalDateTime now) {
        return !now.isBefore(alarmAt.minusMinutes(ALLOWED_TIME_WINDOW_MINUTES))
                && !now.isAfter(alarmAt.plusMinutes(ALLOWED_TIME_WINDOW_MINUTES));
    }

    private void validateEffectiveOn(MedicineSchedule schedule, LocalDate doseDate) {
        if (!schedule.isEffectiveOn(doseDate)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "오늘 유효하지 않은 약 복용 스케줄입니다.");
        }
    }

    private void validateNotVerified(MedicineSchedule schedule, LocalDate doseDate) {
        if (medicationProofRepository.existsByMedicineScheduleAndVerifiedDate(schedule, doseDate)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "오늘은 이미 해당 약 복용을 인증했습니다.");
        }
    }

    private void saveProof(MedicationProof proof, Long scheduleId) {
        try {
            medicationProofRepository.saveAndFlush(proof);
        } catch (DataIntegrityViolationException exception) {
            log.warn("약 복용 인증 중복 저장 감지: scheduleId={}", scheduleId);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "오늘은 이미 해당 약 복용을 인증했습니다.");
        }
    }

    /**
     * 인증 즉시 적립. 낙관적 락 충돌 시 재시도하지 않는다.
     * 호출 대상 포인트 서비스의 {@code @RetryOnPointConflict}는 이 트랜잭션 안에 참여하므로 동작하지 않는다.
     * 이 트랜잭션 밖에서 이미 S3 업로드가 끝나 재실행이 불가하므로 롤백 후 409로 응답한다(LLD-0003 §8).
     */
    private void rewardPoints(Member member, MedicationProof proof, long earnedPoints, boolean completedDay) {
        if (earnedPoints <= 0) {
            return;
        }
        if (member.getSeniorProfile() == null) {
            log.warn("시니어 프로필이 없어 복약 인증 포인트를 적립하지 않습니다: memberId={}", member.getId());
            return;
        }
        if (completedDay) {
            seniorProfileService.addGoalRewardPoints(member.getId(), earnedPoints, POINT_DESCRIPTION,
                    POINT_OPERATION_KEY_PREFIX + proof.getId());
            eventPublisher.publishEvent(GoalAchievedEvent.forMedicationDay(
                    member.getId(), proof.getId(), earnedPoints));
            return;
        }
        seniorProfileService.addPointsToMember(member.getId(), earnedPoints, POINT_DESCRIPTION,
                POINT_OPERATION_KEY_PREFIX + proof.getId());
    }

    private long currentPoints(Member member) {
        SeniorProfile seniorProfile = member.getSeniorProfile();
        if (seniorProfile == null) {
            return 0L;
        }
        return Objects.requireNonNullElse(seniorProfile.getPoints(), 0L);
    }
}
