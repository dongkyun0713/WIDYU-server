package com.widyu.goal.medicineschedule.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicationStatus;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.MemberRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationProofService {

    private static final int ALLOWED_TIME_WINDOW_MINUTES = MedicationStatus.ALLOWED_WINDOW_MINUTES;
    private static final String PROOF_IMAGE_DIRECTORY = "medication-proof";

    private final MedicationProofRepository medicationProofRepository;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MemberRepository memberRepository;
    private final MemberUtil memberUtil;
    private final S3Service s3Service;

    @Transactional
    public MedicationProofResponse verifyMedication(Long scheduleId, List<MultipartFile> images) {
        Member currentMember = getCurrentMemberForAlarmChange();

        MedicineSchedule schedule = medicineScheduleRepository
                .findByIdAndStatusWithDetails(scheduleId, Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 약 복용 스케줄입니다."));

        if (!schedule.getMember().getId().equals(currentMember.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "해당 스케줄에 접근할 권한이 없습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!schedule.isEffectiveOn(now.toLocalDate())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "오늘 유효하지 않은 약 복용 스케줄입니다.");
        }

        LocalTime alarmTime = schedule.getAlarmTime();

        // 알람 시간 전후 30분 체크
        LocalDateTime alarmDateTime = now.toLocalDate().atTime(alarmTime);
        LocalDateTime earliestAllowed = alarmDateTime.minusMinutes(ALLOWED_TIME_WINDOW_MINUTES);
        LocalDateTime latestAllowed = alarmDateTime.plusMinutes(ALLOWED_TIME_WINDOW_MINUTES);

        if (now.isBefore(earliestAllowed) || now.isAfter(latestAllowed)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("약 복용 인증은 알람 시간 전후 %d분 이내에만 가능합니다.",
                            ALLOWED_TIME_WINDOW_MINUTES));
        }

        // 오늘 이미 인증했는지 체크
        boolean alreadyVerifiedToday = medicationProofRepository.existsByMedicineScheduleAndVerifiedAtBetween(
                schedule,
                now.toLocalDate().atStartOfDay(),
                now.toLocalDate().atTime(LocalTime.MAX)
        );

        if (alreadyVerifiedToday) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "오늘은 이미 해당 약 복용을 인증했습니다.");
        }

        List<String> imageUrls = uploadProofImages(images, currentMember.getId());

        MedicationProof proof = MedicationProof.create(schedule, currentMember, imageUrls);
        saveProof(proof, scheduleId);
        currentMember.incrementMedicationAlarmRevision();

        long earnedPoints = calculateEarnedPoints(currentMember, now.toLocalDate());

        log.info("약 복용 인증 완료: scheduleId={}, memberId={}, verifiedAt={}, earnedPoints={}",
                scheduleId, currentMember.getId(), now, earnedPoints);

        return MedicationProofResponse.of(currentPoints(currentMember), earnedPoints);
    }

    // 인증을 저장한 뒤의 오늘 인증 횟수로 계산해야 마지막 일정 인증에 보너스가 반영된다.
    private long calculateEarnedPoints(Member member, LocalDate date) {
        long proofCount = medicationProofRepository.countByMemberAndVerifiedAtBetween(
                member, date.atStartOfDay(), date.atTime(LocalTime.MAX));
        long totalSchedules = medicineScheduleRepository.countEffectiveByMemberAndDate(
                member, Status.ACTIVE, date);
        return MedicationPointPolicy.calculateEarnedPoints(proofCount, totalSchedules);
    }

    private Member getCurrentMemberForAlarmChange() {
        Long memberId = memberUtil.getCurrentMember().getId();
        return memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
    }

    // 시니어 프로필이 없는 회원도 인증 자체는 성공해야 하므로 잔액은 0으로 내려준다.
    private long currentPoints(Member member) {
        SeniorProfile seniorProfile = member.getSeniorProfile();
        if (seniorProfile == null) {
            return 0L;
        }
        return Objects.requireNonNullElse(seniorProfile.getPoints(), 0L);
    }

    // 위 중복 검사와 저장 사이에 다른 요청이 끼어들 수 있어, 최종 차단은 DB unique 제약이 맡는다.
    private void saveProof(MedicationProof proof, Long scheduleId) {
        try {
            medicationProofRepository.saveAndFlush(proof);
        } catch (DataIntegrityViolationException e) {
            log.warn("약 복용 인증 중복 저장 감지: scheduleId={}", scheduleId);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "오늘은 이미 해당 약 복용을 인증했습니다.");
        }
    }

    private List<String> uploadProofImages(List<MultipartFile> images, Long memberId) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }

        List<String> uploadedUrls = new ArrayList<>();
        try {
            for (MultipartFile image : images) {
                String directory = PROOF_IMAGE_DIRECTORY + "/" + memberId;
                String filePath = s3Service.generateFilePath(directory, image.getOriginalFilename());
                String url = s3Service.uploadFile(image, filePath);
                uploadedUrls.add(url);
            }
            return uploadedUrls;
        } catch (Exception e) {
            uploadedUrls.forEach(s3Service::deleteFile);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "복용 인증 이미지 업로드에 실패했습니다.");
        }
    }
}
