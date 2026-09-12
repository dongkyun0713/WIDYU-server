package com.widyu.goal.home.application;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.goal.home.dto.response.FamilyListResponse;
import com.widyu.goal.home.dto.response.FamilyMemberResponse;
import com.widyu.goal.home.dto.response.GuardianGoalHomeResponse;
import com.widyu.goal.home.dto.response.GuardianGoalStatsResponse;
import com.widyu.goal.home.dto.response.SeniorGoalHomeResponse;
import com.widyu.goal.home.dto.response.SeniorWeeklyGoalStatusResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicationStatus;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.goal.DailyGoalStatus;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineCategory;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.medicine.MedicineScheduleDetail;
import com.widyu.walk.Walk;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoalHomeService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MedicationProofRepository medicationProofRepository;
    private final WalkRepository walkRepository;
    private final HealthScheduleRepository healthScheduleRepository;
    private final MemberRepository memberRepository;
    private final MemberUtil memberUtil;

    public FamilyListResponse getFamilyList() {
        Member currentMember = memberUtil.getCurrentMember();

        FamilyMembership myMembership = familyMembershipRepository.findByGuardianId(currentMember.getId())
                .orElse(null);

        if (myMembership == null) {
            return FamilyListResponse.of(List.of());
        }

        List<SeniorProfile> seniors = seniorProfileRepository
                .findAllByFamilyIdWithMember(myMembership.getFamily().getId());

        List<FamilyMemberResponse> families = seniors.stream()
                .map(FamilyMemberResponse::from)
                .toList();

        return FamilyListResponse.of(families);
    }

    /**
     * 시니어 목표 홈 - 목표 조회
     */
    public SeniorGoalHomeResponse getSeniorGoalHome() {
        Member currentMember = memberUtil.getCurrentMember();
        LocalDate today = LocalDate.now();

        // Medicine 정보
        SeniorGoalHomeResponse.MedicineInfo medicineInfo = getSeniorMedicineInfo(currentMember, today);

        // Steps 정보
        SeniorGoalHomeResponse.StepsInfo stepsInfo = getStepsInfo(currentMember, today);

        // Hospital 정보
        SeniorGoalHomeResponse.HospitalInfo hospitalInfo = getUpcomingHospitalInfo(currentMember);

        return SeniorGoalHomeResponse.of(medicineInfo, stepsInfo, hospitalInfo);
    }

    /**
     * 시니어 이번 주 목표 달성률 조회
     */
    public SeniorWeeklyGoalStatusResponse getSeniorWeeklyGoalStatus() {
        Member currentMember = memberUtil.getCurrentMember();
        LocalDate today = LocalDate.now();

        // 이번 주 일요일부터 토요일까지
        LocalDate startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        GoalPeriodData periodData = getGoalPeriodData(currentMember, startOfWeek, endOfWeek);

        List<DailyGoalStatus> weeklyStatus = new ArrayList<>();
        for (LocalDate date = startOfWeek; !date.isAfter(endOfWeek); date = date.plusDays(1)) {
            DailyGoalStatus status = calculateDailyGoalStatus(periodData, date, today);
            weeklyStatus.add(status);
        }

        return SeniorWeeklyGoalStatusResponse.of(weeklyStatus);
    }

    /**
     * 보호자 목표 홈 - 현황 조회
     */
    public GuardianGoalStatsResponse getGuardianGoalStats(Long memberId) {
        Member targetMember = getMember(memberId);
        LocalDate today = LocalDate.now();

        // 지난주
        LocalDate lastWeekStart = today.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate lastWeekEnd = lastWeekStart.plusDays(6);

        // 이번주
        LocalDate thisWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        LocalDate thisWeekEnd = thisWeekStart.plusDays(6);
        GoalPeriodData periodData = getGoalPeriodData(targetMember, lastWeekStart, thisWeekEnd);

        List<Double> lastWeekGoalRates = calculateDailyGoalRates(periodData, lastWeekStart, lastWeekEnd);
        Double lastWeekGoalRate = calculateWeeklyGoalRate(lastWeekGoalRates, lastWeekStart, today);

        // 이번주 일별 달성률
        List<Double> thisWeekGoalRates = calculateDailyGoalRates(periodData, thisWeekStart, thisWeekEnd);
        Double thisWeekGoalRate = calculateWeeklyGoalRate(thisWeekGoalRates, thisWeekStart, today);

        return GuardianGoalStatsResponse.of(lastWeekGoalRate, thisWeekGoalRate, thisWeekGoalRates);
    }

    /**
     * 보호자 목표 홈 - 목표 조회
     */
    public GuardianGoalHomeResponse getGuardianGoalHome(Long memberId) {
        Member targetMember = getMember(memberId);
        LocalDate today = LocalDate.now();

        // Medicine 정보
        GuardianGoalHomeResponse.MedicineInfo medicineInfo = getGuardianMedicineInfo(targetMember, today);

        // Steps 정보
        GuardianGoalHomeResponse.StepsInfo stepsInfo = getGuardianStepsInfo(targetMember, today);

        // Hospital 정보
        GuardianGoalHomeResponse.HospitalInfo hospitalInfo = getGuardianHospitalInfo(targetMember);

        return GuardianGoalHomeResponse.of(medicineInfo, stepsInfo, hospitalInfo);
    }

    // ===== Private Helper Methods =====

    private SeniorGoalHomeResponse.MedicineInfo getSeniorMedicineInfo(Member member, LocalDate today) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findEffectiveByMemberAndDateWithDetails(member, Status.ACTIVE, today);

        if (schedules.isEmpty()) {
            return null;
        }

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);

        // 스케줄 수정·삭제로 오늘 유효 목록에서 빠진 버전의 인증은 제외한다 (takenCount가 totalCount를 넘지 않도록)
        Set<Long> scheduleIds = schedules.stream()
                .map(MedicineSchedule::getId)
                .collect(Collectors.toSet());

        // 오늘 복용 인증 정보 (스케줄별 1건)
        Map<Long, MedicationProof> proofsByScheduleId = medicationProofRepository
                .findByMemberIdAndDateRange(member.getId(), startOfDay, endOfDay)
                .stream()
                .filter(proof -> scheduleIds.contains(proof.getMedicineSchedule().getId()))
                .collect(Collectors.toMap(
                        proof -> proof.getMedicineSchedule().getId(),
                        proof -> proof,
                        // 조회 쿼리에 정렬이 없어 중복 인증 시 대표를 verifiedAt 최초순으로 고정한다.
                        BinaryOperator.minBy(Comparator.comparing(MedicationProof::getVerifiedAt))
                ));

        MedicineSchedule nextSchedule = findNextSchedule(schedules, LocalTime.now());

        return SeniorGoalHomeResponse.MedicineInfo.from(
                nextSchedule,
                proofsByScheduleId.size(),
                schedules.size(),
                proofsByScheduleId.get(nextSchedule.getId())
        );
    }

    // 알람 시간 오름차순 정렬된 목록 기준. 오늘 남은 알람이 없으면 마지막 알람을 그대로 노출한다.
    static MedicineSchedule findNextSchedule(List<MedicineSchedule> schedules, LocalTime now) {
        return schedules.stream()
                .filter(s -> s.getAlarmTime().isAfter(now))
                .min(Comparator.comparing(MedicineSchedule::getAlarmTime))
                .orElse(schedules.getLast());
    }

    private SeniorGoalHomeResponse.StepsInfo getStepsInfo(Member member, LocalDate today) {
        Walk walk = walkRepository.findByMemberAndWalkDate(member, today).orElse(null);

        if (walk != null) {
            return new SeniorGoalHomeResponse.StepsInfo(walk.getActualSteps(), walk.getGoalSteps());
        }

        // 기본 목표가 있으면 반환
        if (member.getSeniorProfile() != null && member.getSeniorProfile().hasDefaultWalkGoal()) {
            return new SeniorGoalHomeResponse.StepsInfo(0, member.getSeniorProfile().getDefaultWalkGoal());
        }

        return null;
    }

    private SeniorGoalHomeResponse.HospitalInfo getUpcomingHospitalInfo(Member member) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureLimit = now.plusMonths(1); // 1개월 이내

        List<HealthSchedule> upcomingSchedules = healthScheduleRepository
                .findByMemberIdAndWeek(member.getId(), now, futureLimit);

        if (upcomingSchedules.isEmpty()) {
            return null;
        }

        // 가장 가까운 일정
        HealthSchedule nearest = upcomingSchedules.stream()
                .filter(s -> s.getScheduledAt().isAfter(now))
                .min(Comparator.comparing(HealthSchedule::getScheduledAt))
                .orElse(null);

        if (nearest == null) {
            return null;
        }

        int dday = (int) java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), nearest.getScheduledAt().toLocalDate());

        return SeniorGoalHomeResponse.HospitalInfo.from(nearest, dday);
    }

    private DailyGoalStatus calculateDailyGoalStatus(GoalPeriodData periodData, LocalDate date, LocalDate today) {
        // 날짜가 미래면 NOT_STARTED (기한 전)
        if (date.isAfter(today)) {
            return DailyGoalStatus.NOT_STARTED;
        }

        GoalCounts goalCounts = calculateGoalCounts(periodData, date);
        if (goalCounts.total() == 0) {
            return DailyGoalStatus.NOT_STARTED; // 목표가 없으면 시작 전
        }

        // 상태 결정
        if (goalCounts.completed() == goalCounts.total()) {
            return DailyGoalStatus.COMPLETED;
        }
        if (date.isBefore(today)) {
            return DailyGoalStatus.FAILED;
        }
        if (goalCounts.completed() > 0) {
            return DailyGoalStatus.IN_PROGRESS;
        }
        return DailyGoalStatus.NOT_STARTED;
    }

    private GoalCounts calculateGoalCounts(GoalPeriodData periodData, LocalDate date) {
        List<MedicineSchedule> schedules = periodData.schedules().stream()
                .filter(schedule -> schedule.isEffectiveOn(date))
                .toList();
        Set<Long> verifiedScheduleIds = periodData.verifiedScheduleIdsByDate().getOrDefault(date, Set.of());
        Walk walk = periodData.walksByDate().get(date);

        int totalGoals = schedules.size();
        if (walk != null) {
            totalGoals++;
        }

        int completedGoals = (int) schedules.stream()
                .filter(schedule -> verifiedScheduleIds.contains(schedule.getId()))
                .count();
        if (walk != null && walk.isGoalAchieved()) {
            completedGoals++;
        }

        return new GoalCounts(totalGoals, completedGoals);
    }

    private List<Double> calculateDailyGoalRates(
            GoalPeriodData periodData,
            LocalDate startDate,
            LocalDate endDate
    ) {
        List<Double> dailyGoalRates = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            GoalCounts goalCounts = calculateGoalCounts(periodData, date);
            dailyGoalRates.add(goalCounts.rate());
        }
        return dailyGoalRates;
    }

    private Double calculateWeeklyGoalRate(List<Double> dailyGoalRates, LocalDate startDate, LocalDate today) {
        int totalDays = 0;
        int completedDays = 0;
        LocalDate date = startDate;

        for (Double dailyGoalRate : dailyGoalRates) {
            if (date.isAfter(today)) {
                break;
            }
            totalDays++;
            if (dailyGoalRate >= 1.0) {
                completedDays++;
            }
            date = date.plusDays(1);
        }

        if (totalDays == 0) {
            return 0.0;
        }
        return (double) completedDays / totalDays;
    }

    private GoalPeriodData getGoalPeriodData(Member member, LocalDate startDate, LocalDate endDate) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findEffectiveByMemberAndDateRange(member, Status.ACTIVE, startDate, endDate);

        Map<LocalDate, Set<Long>> verifiedScheduleIdsByDate = medicationProofRepository
                .findByMemberIdAndDateRange(member.getId(), startDate.atStartOfDay(), endDate.atTime(LocalTime.MAX))
                .stream()
                .collect(Collectors.groupingBy(
                        proof -> proof.getVerifiedAt().toLocalDate(),
                        Collectors.mapping(proof -> proof.getMedicineSchedule().getId(), Collectors.toSet())
                ));

        Map<LocalDate, Walk> walksByDate = walkRepository
                .findByMemberAndWalkDateBetweenOrderByWalkDateAsc(member, startDate, endDate)
                .stream()
                .collect(Collectors.toMap(Walk::getWalkDate, walk -> walk));

        return new GoalPeriodData(schedules, verifiedScheduleIdsByDate, walksByDate);
    }

    private GuardianGoalHomeResponse.MedicineInfo getGuardianMedicineInfo(Member member, LocalDate today) {
        List<MedicineSchedule> schedules = medicineScheduleRepository
                .findEffectiveByMemberAndDateWithDetails(member, Status.ACTIVE, today);

        if (schedules.isEmpty()) {
            return null;
        }

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);
        LocalDateTime now = LocalDateTime.now();

        // 오늘 복용 인증 정보 조회
        Map<Long, MedicationProof> proofMap = medicationProofRepository
                .findByMemberIdAndDateRange(member.getId(), startOfDay, endOfDay)
                .stream()
                .collect(Collectors.toMap(
                        p -> p.getMedicineSchedule().getId(),
                        p -> p,
                        // 조회 쿼리에 정렬이 없어 중복 인증 시 대표를 verifiedAt 최초순으로 고정한다.
                        BinaryOperator.minBy(Comparator.comparing(MedicationProof::getVerifiedAt))
                ));

        int totalCount = 0;
        int takenCount = 0;

        List<GuardianGoalHomeResponse.ScheduleItem> scheduleItems = new ArrayList<>();

        for (MedicineSchedule schedule : schedules) {
            int scheduleTotal = schedule.getTotalCount();
            totalCount += scheduleTotal;

            MedicationProof proof = proofMap.get(schedule.getId());
            boolean taken = proof != null;
            if (taken) {
                takenCount += scheduleTotal;
            }

            MedicationStatus status = MedicationStatus.of(taken, today, schedule.getAlarmTime(), now);

            List<GuardianGoalHomeResponse.MedicineItem> medicineItems = schedule.getCategories().stream()
                    .flatMap(category -> category.getMedicines().stream())
                    .map(detail -> new GuardianGoalHomeResponse.MedicineItem(
                            detail.getMedicine().getName(),
                            detail.getDose()
                    ))
                    .toList();

            String proofImageUrl = null;
            if (taken && !proof.getProofImageUrls().isEmpty()) {
                proofImageUrl = proof.getProofImageUrls().get(0);
            }

            scheduleItems.add(new GuardianGoalHomeResponse.ScheduleItem(
                    schedule.getId(),
                    schedule.getAlarmTime().format(TIME_FORMATTER),
                    status,
                    proofImageUrl,
                    medicineItems
            ));
        }

        return new GuardianGoalHomeResponse.MedicineInfo(totalCount, takenCount, scheduleItems);
    }

    private GuardianGoalHomeResponse.StepsInfo getGuardianStepsInfo(Member member, LocalDate today) {
        Walk walk = walkRepository.findByMemberAndWalkDate(member, today).orElse(null);

        if (walk != null) {
            return new GuardianGoalHomeResponse.StepsInfo(walk.getActualSteps(), walk.getGoalSteps());
        }

        // 기본 목표가 있으면 반환
        if (member.getSeniorProfile() != null && member.getSeniorProfile().hasDefaultWalkGoal()) {
            return new GuardianGoalHomeResponse.StepsInfo(0, member.getSeniorProfile().getDefaultWalkGoal());
        }

        return null;
    }

    private GuardianGoalHomeResponse.HospitalInfo getGuardianHospitalInfo(Member member) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureLimit = now.plusMonths(1);

        List<HealthSchedule> upcomingSchedules = healthScheduleRepository
                .findByMemberIdAndWeek(member.getId(), now, futureLimit);

        if (upcomingSchedules.isEmpty()) {
            return null;
        }

        HealthSchedule nearest = upcomingSchedules.stream()
                .filter(s -> s.getScheduledAt().isAfter(now))
                .min(Comparator.comparing(HealthSchedule::getScheduledAt))
                .orElse(null);

        if (nearest == null) {
            return null;
        }

        int dday = (int) java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), nearest.getScheduledAt().toLocalDate());

        return GuardianGoalHomeResponse.HospitalInfo.from(nearest, dday);
    }

    private Member getMember(Long memberId) {
        if (memberId == null) {
            Member currentMember = memberUtil.getCurrentMember();
            FamilyMembership myMembership = familyMembershipRepository.findByGuardianId(currentMember.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "연결된 부모님이 없습니다."));

            List<SeniorProfile> seniors = seniorProfileRepository
                    .findAllByFamilyIdWithMember(myMembership.getFamily().getId());

            if (seniors.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "연결된 부모님이 없습니다.");
            }

            return seniors.get(0).getMember();
        }

        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 사용자입니다."));
    }

    private record GoalPeriodData(
            List<MedicineSchedule> schedules,
            Map<LocalDate, Set<Long>> verifiedScheduleIdsByDate,
            Map<LocalDate, Walk> walksByDate
    ) {
    }

    private record GoalCounts(int total, int completed) {

        private double rate() {
            if (total == 0) {
                return 0.0;
            }
            return (double) completed / total;
        }
    }
}
