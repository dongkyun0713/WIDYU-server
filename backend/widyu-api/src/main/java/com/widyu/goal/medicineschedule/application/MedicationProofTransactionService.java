package com.widyu.goal.medicineschedule.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public void validateBeforeUpload(Long memberId, Long scheduleId) {
        Member currentMember = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
        MedicineSchedule schedule = medicineScheduleRepository
                .findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 약 복용 스케줄입니다."));

        validateScheduleOwner(schedule, currentMember);
        LocalDateTime now = LocalDateTime.now();
        validateScheduleTime(schedule, now);
        validateNotVerifiedToday(schedule, now);
    }

    @Transactional
    public MedicationProofResponse verifyMedication(Long memberId, Long scheduleId, List<String> imageUrls) {
        Member currentMember = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
        MedicineSchedule schedule = medicineScheduleRepository
                .findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 약 복용 스케줄입니다."));

        validateScheduleOwner(schedule, currentMember);
        LocalDateTime now = LocalDateTime.now();
        validateScheduleTime(schedule, now);
        validateNotVerifiedToday(schedule, now);

        MedicationProof proof = MedicationProof.create(schedule, currentMember, imageUrls);
        saveProof(proof, scheduleId);
        currentMember.incrementMedicationAlarmRevision();

        long earnedPoints = calculateEarnedPoints(currentMember, now.toLocalDate());
        rewardPoints(currentMember, proof, earnedPoints);
        log.info("약 복용 인증 완료: scheduleId={}, memberId={}, verifiedAt={}, earnedPoints={}",
                scheduleId, currentMember.getId(), now, earnedPoints);
        return MedicationProofResponse.of(currentPoints(currentMember), earnedPoints);
    }

    private void validateScheduleOwner(MedicineSchedule schedule, Member member) {
        if (!schedule.getMember().getId().equals(member.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 스케줄에 접근할 권한이 없습니다.");
        }
    }

    private void validateScheduleTime(MedicineSchedule schedule, LocalDateTime now) {
        if (!schedule.isEffectiveOn(now.toLocalDate())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "오늘 유효하지 않은 약 복용 스케줄입니다.");
        }

        LocalDateTime alarmDateTime = now.toLocalDate().atTime(schedule.getAlarmTime());
        if (now.isBefore(alarmDateTime.minusMinutes(ALLOWED_TIME_WINDOW_MINUTES))
                || now.isAfter(alarmDateTime.plusMinutes(ALLOWED_TIME_WINDOW_MINUTES))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("약 복용 인증은 알람 시간 전후 %d분 이내에만 가능합니다.", ALLOWED_TIME_WINDOW_MINUTES));
        }
    }

    private void validateNotVerifiedToday(MedicineSchedule schedule, LocalDateTime now) {
        if (medicationProofRepository.existsByMedicineScheduleAndVerifiedAtBetween(
                schedule, now.toLocalDate().atStartOfDay(), now.toLocalDate().atTime(LocalTime.MAX))) {
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

    private long calculateEarnedPoints(Member member, LocalDate date) {
        long proofCount = medicationProofRepository.countByMemberAndVerifiedAtBetween(
                member, date.atStartOfDay(), date.atTime(LocalTime.MAX));
        long totalSchedules = medicineScheduleRepository.countEffectiveByMemberAndDate(
                member, Status.ACTIVE, date);
        return MedicationPointPolicy.calculateEarnedPoints(proofCount, totalSchedules);
    }

    /**
     * 인증 즉시 적립. 낙관적 락 충돌 시 재시도하지 않는다.
     * 호출 대상 {@code addPointsToMember}의 {@code @RetryOnPointConflict}는 이 트랜잭션 안에 참여하므로 동작하지 않는다.
     * 이 트랜잭션 밖에서 이미 S3 업로드가 끝나 재실행이 불가하므로 롤백 후 409로 응답한다(LLD-0003 §8).
     */
    private void rewardPoints(Member member, MedicationProof proof, long earnedPoints) {
        if (earnedPoints <= 0) {
            return;
        }
        if (member.getSeniorProfile() == null) {
            log.warn("시니어 프로필이 없어 복약 인증 포인트를 적립하지 않습니다: memberId={}", member.getId());
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
