package com.widyu.home.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.MemberType;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.home.dto.response.GuardianHomeCardsResponse;
import com.widyu.home.dto.response.GuardianSeniorListResponse;
import com.widyu.location.access.LocationAccessPath;
import com.widyu.location.access.application.LocationAccessLogService;
import com.widyu.location.realtime.application.RealtimeLocationService;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianHomeService {

    private final MemberUtil memberUtil;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final HeartRateResultRepository heartRateResultRepository;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MedicationProofRepository medicationProofRepository;
    private final HealthScheduleRepository healthScheduleRepository;
    private final WalkRepository walkRepository;
    private final HomeAlbumRecommendationService albumRecommendationService;
    private final RealtimeLocationService realtimeLocationService;
    private final LocationAccessLogService locationAccessLogService;

    public GuardianHomeCardsResponse getHomeCards(Long memberId) {
        Member guardian = memberUtil.getCurrentMember();
        if (guardian.getType() != MemberType.GUARDIAN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "보호자만 접근 가능합니다.");
        }

        Member senior = resolveSenior(memberId, guardian);
        LocalDate today = LocalDate.now();

        // 외출 여부는 좌표가 아니지만 위치에서 파생되므로 열람으로 센다(ADR-0036 결과).
        recordAccessQuietly(guardian.getId(), senior.getId());

        return GuardianHomeCardsResponse.of(
                realtimeLocationService.getOutingStatus(senior.getId()),
                getHeartRateInfo(senior),
                getMedicineInfo(senior, today),
                getScoredAlbums(senior, today),
                getHealthScheduleInfo(senior),
                getWalkInfo(senior, today)
        );
    }

    public GuardianSeniorListResponse getFamilySeniors() {
        Member guardian = memberUtil.getCurrentMember();
        if (guardian.getType() != MemberType.GUARDIAN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "보호자만 접근 가능합니다.");
        }

        return familyMembershipRepository.findByGuardianId(guardian.getId())
                .map(membership -> seniorProfileRepository.findAllByFamilyIdWithMember(membership.getFamily().getId()))
                .map(GuardianSeniorListResponse::from)
                .orElseGet(GuardianSeniorListResponse::empty);
    }

    /** 기록이 깨져도 홈 카드는 그려져야 한다(LLD-0056 5.1). */
    private void recordAccessQuietly(Long guardianId, Long seniorMemberId) {
        try {
            locationAccessLogService.record(guardianId, seniorMemberId, LocationAccessPath.HOME_OUTING);
        } catch (Exception e) {
            log.warn("위치 열람 기록 실패: path={}, cause={}",
                    LocationAccessPath.HOME_OUTING, e.getClass().getSimpleName());
        }
    }

    private Member resolveSenior(Long memberId, Member guardian) {
        if (memberId != null) {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 사용자입니다."));
            if (member.getType() != MemberType.SENIOR) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "시니어 회원만 조회할 수 있습니다.");
            }
            SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "시니어 프로필이 없습니다."));
            if (!familyMembershipRepository.existsByFamilyIdAndGuardianId(
                    seniorProfile.getFamily().getId(), guardian.getId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어에 대한 접근 권한이 없습니다.");
            }
            return member;
        }

        FamilyMembership membership = familyMembershipRepository.findByGuardianId(guardian.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "연결된 부모님이 없습니다."));

        List<SeniorProfile> seniors = seniorProfileRepository
                .findAllByFamilyIdWithMember(membership.getFamily().getId());

        if (seniors.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "연결된 부모님이 없습니다.");
        }

        return seniors.getFirst().getMember();
    }

    private GuardianHomeCardsResponse.HeartRateInfo getHeartRateInfo(Member senior) {
        return heartRateResultRepository.findByMemberId(senior.getId())
                .map(GuardianHomeCardsResponse.HeartRateInfo::from)
                .orElse(GuardianHomeCardsResponse.HeartRateInfo.unknown());
    }

    private GuardianHomeCardsResponse.MedicineInfo getMedicineInfo(Member senior, LocalDate today) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findEffectiveByMemberAndDateWithDetails(senior, Status.ACTIVE, today);

        if (schedules.isEmpty()) {
            return null;
        }

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);

        Map<Long, MedicationProof> proofMap = medicationProofRepository
                .findByMemberIdAndDateRange(senior.getId(), startOfDay, endOfDay)
                .stream()
                .collect(Collectors.toMap(
                        p -> p.getMedicineSchedule().getId(),
                        p -> p,
                        (p1, p2) -> p1
                ));

        Set<Long> takenIds = proofMap.keySet();

        String latestProofImageUrl = proofMap.values().stream()
                .max(Comparator.comparing(MedicationProof::getVerifiedAt))
                .map(MedicationProof::getProofImageUrls)
                .filter(urls -> !urls.isEmpty())
                .map(List::getFirst)
                .orElse(null);

        List<GuardianHomeCardsResponse.ScheduleStatus> scheduleStatuses = schedules.stream()
                .sorted(Comparator.comparing(MedicineSchedule::getAlarmTime))
                .map(s -> GuardianHomeCardsResponse.ScheduleStatus.from(s, takenIds.contains(s.getId())))
                .toList();

        int totalCount = schedules.size();

        return new GuardianHomeCardsResponse.MedicineInfo(totalCount, latestProofImageUrl, scheduleStatuses);
    }

    private List<GuardianHomeCardsResponse.AlbumInfo> getScoredAlbums(Member senior, LocalDate today) {
        return albumRecommendationService.recommendAlbums(senior, today).stream()
                .map(GuardianHomeCardsResponse.AlbumInfo::from)
                .toList();
    }

    private GuardianHomeCardsResponse.HealthScheduleInfo getHealthScheduleInfo(Member senior) {
        return healthScheduleRepository
                .findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(senior.getId(), LocalDateTime.now())
                .map(GuardianHomeCardsResponse.HealthScheduleInfo::from)
                .orElse(null);
    }

    private GuardianHomeCardsResponse.WalkInfo getWalkInfo(Member senior, LocalDate today) {
        Optional<Walk> walk = walkRepository.findByMemberAndWalkDate(senior, today);

        if (walk.isPresent()) {
            return GuardianHomeCardsResponse.WalkInfo.from(walk.get());
        }

        SeniorProfile seniorProfile = senior.getSeniorProfile();
        if (seniorProfile != null && seniorProfile.hasDefaultWalkGoal()) {
            return GuardianHomeCardsResponse.WalkInfo.withDefaultGoal(seniorProfile.getDefaultWalkGoal());
        }

        return null;
    }
}
