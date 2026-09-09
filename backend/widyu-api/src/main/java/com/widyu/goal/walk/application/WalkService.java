package com.widyu.goal.walk.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.retry.RetryOnPointConflict;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.walk.Walk;
import com.widyu.goal.walk.dto.request.SetGoalRequest;
import com.widyu.goal.walk.dto.request.UpdateStepsRequest;
import com.widyu.goal.walk.dto.response.UpdateStepsResponse;
import com.widyu.goal.walk.dto.response.UpcomingGoalResponse;
import com.widyu.goal.walk.dto.response.WalkDetailResponse;
import com.widyu.goal.walk.dto.response.WalkMonthlyResponse;
import com.widyu.goal.walk.repository.WalkRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalkService {

    private final WalkRepository walkRepository;
    private final MemberRepository memberRepository;
    private final MemberUtil memberUtil;

    public WalkMonthlyResponse getMonthlyStats(int year, int month, Long memberId) {
        Member targetMember = getMember(memberId);
        YearMonth requestedMonth = YearMonth.of(year, month);
        YearMonth currentMonth = YearMonth.now();

        // Summary 계산
        WalkMonthlyResponse.WalkSummary summary = calculateSummary(
                targetMember,
                requestedMonth,
                currentMonth
        );

        // Daily Data 조회
        List<WalkMonthlyResponse.WalkDaily> dailyData = getDailyDataForMonth(
                targetMember,
                requestedMonth
        );

        return WalkMonthlyResponse.of(summary, dailyData);
    }

    public WalkDetailResponse getWalkDetail(Long memberId) {
        Member targetMember = getMember(memberId);
        LocalDate today = LocalDate.now();

        Walk walk = walkRepository.findByMemberAndWalkDate(targetMember, today).orElse(null);

        if (walk != null) {
            return WalkDetailResponse.from(walk);
        }

        // Walk 기록이 없으면 defaultWalkGoal 확인
        if (targetMember.getSeniorProfile() != null &&
            targetMember.getSeniorProfile().hasDefaultWalkGoal()) {
            return WalkDetailResponse.withDefault(
                    targetMember.getSeniorProfile().getDefaultWalkGoal()
            );
        }

        return null;
    }

    public UpcomingGoalResponse getUpcomingGoal(Long memberId) {
        Member targetMember = getMember(memberId);

        if (targetMember.getSeniorProfile() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "시니어 프로필이 없습니다.");
        }

        if (!targetMember.getSeniorProfile().hasDefaultWalkGoal()) {
            return null;
        }

        return UpcomingGoalResponse.of(
                targetMember.getSeniorProfile().getDefaultWalkGoal()
        );
    }

    @Transactional
    public void setOrUpdateGoal(Long memberId, SetGoalRequest request) {
        Member targetMember = getMember(memberId);

        if (targetMember.getSeniorProfile() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "시니어 프로필이 없습니다.");
        }

        LocalDate today = LocalDate.now();
        Integer previousDefaultGoal = targetMember.getSeniorProfile().getDefaultWalkGoal();

        // 오늘 날짜의 Walk 기록 확인
        boolean todayWalkExists = walkRepository.existsByMemberAndWalkDate(targetMember, today);

        if (previousDefaultGoal == null) {
            // 처음 목표 설정: 오늘부터 적용
            if (!todayWalkExists) {
                Walk todayWalk = Walk.createWithGoal(targetMember, today, request.steps());
                walkRepository.save(todayWalk);
            }
            targetMember.getSeniorProfile().updateDefaultWalkGoal(request.steps());
            log.info("걷기 목표 설정 (처음): memberId={}, defaultWalkGoal={}, 오늘부터 적용",
                    targetMember.getId(), request.steps());
            return;
        }

        // 목표 수정: 오늘은 기존 목표 유지, 내일부터 새 목표 적용
        if (!todayWalkExists) {
            // 오늘 Walk 기록이 없으면 기존 defaultWalkGoal로 생성
            Walk todayWalk = Walk.createWithGoal(targetMember, today, previousDefaultGoal);
            walkRepository.save(todayWalk);
        }
        // 내일부터 적용될 새 목표 설정
        targetMember.getSeniorProfile().updateDefaultWalkGoal(request.steps());
        log.info("걷기 목표 수정: memberId={}, 기존={}, 신규={}, 내일부터 적용",
                targetMember.getId(), previousDefaultGoal, request.steps());
    }

    @RetryOnPointConflict
    @Transactional
    public UpdateStepsResponse updateSteps(UpdateStepsRequest request) {
        Member currentMember = memberUtil.getCurrentMember();
        LocalDate today = LocalDate.now();

        Walk walk = walkRepository.findByMemberAndWalkDate(currentMember, today)
                .orElseGet(() -> {
                    if (currentMember.getSeniorProfile() != null &&
                        currentMember.getSeniorProfile().hasDefaultWalkGoal()) {
                        Walk newWalk = Walk.createWithGoal(
                                currentMember,
                                today,
                                currentMember.getSeniorProfile().getDefaultWalkGoal()
                        );
                        return walkRepository.save(newWalk);
                    }
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "먼저 걷기 목표를 설정해주세요.");
                });

        walk.updateActualSteps(request.steps());

        // 목표 달성 시 포인트 지급 (하루 1회만, 목표 초과 후 재연동해도 중복 지급하지 않음)
        boolean achieved = walk.isGoalAchieved();
        if (achieved && !walk.isRewarded() && currentMember.getSeniorProfile() != null) {
            currentMember.getSeniorProfile().addPoints((long) walk.getPointRewarded());
            walk.markRewarded();
            log.info("걸음 목표 달성 - 포인트 자동 지급: memberId={}, points={}",
                    currentMember.getId(), walk.getPointRewarded());
        }

        log.info("걸음 수 연동: memberId={}, date={}, actualSteps={}, achieved={}",
                currentMember.getId(), today, request.steps(), achieved);

        return UpdateStepsResponse.of(achieved);
    }


    private WalkMonthlyResponse.WalkSummary calculateSummary(
            Member member,
            YearMonth requestedMonth,
            YearMonth currentMonth
    ) {
        YearMonth previousMonth = requestedMonth.minusMonths(1);
        WalkMonthlyResponse.WalkSummary.MonthStats previous = getMonthStats(member, previousMonth);

        YearMonth targetMonth = requestedMonth;
        if (requestedMonth.equals(currentMonth)) {
            targetMonth = currentMonth;
        }
        WalkMonthlyResponse.WalkSummary.MonthStats current = getMonthStats(member, targetMonth);

        return WalkMonthlyResponse.WalkSummary.of(previous, current);
    }

    private WalkMonthlyResponse.WalkSummary.MonthStats getMonthStats(Member member, YearMonth month) {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        long achieved = walkRepository.countAchievedGoals(member.getId(), startDate, endDate);
        long total = walkRepository.countTotalRecords(member.getId(), startDate, endDate);

        return WalkMonthlyResponse.WalkSummary.MonthStats.of(achieved, total);
    }

    private List<WalkMonthlyResponse.WalkDaily> getDailyDataForMonth(
            Member member,
            YearMonth month
    ) {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        List<Walk> walks = walkRepository.findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
                member, startDate, endDate
        );

        Map<LocalDate, Walk> walkMap = walks.stream()
                .collect(Collectors.toMap(Walk::getWalkDate, walk -> walk));

        // defaultWalkGoal 확인
        Integer defaultWalkGoal = null;
        if (member.getSeniorProfile() != null && member.getSeniorProfile().hasDefaultWalkGoal()) {
            defaultWalkGoal = member.getSeniorProfile().getDefaultWalkGoal();
        }

        LocalDate today = LocalDate.now();

        List<WalkMonthlyResponse.WalkDaily> dailyData = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Walk walk = walkMap.get(date);
            if (walk != null) {
                // Walk 기록이 있으면 실제 기록 반환
                dailyData.add(WalkMonthlyResponse.WalkDaily.of(
                        date.toString(),
                        walk.getGoalSteps(),
                        walk.getActualSteps()
                ));
                continue;
            }

            // 과거 날짜는 기록이 없으면 그날 목표가 없었던 것이므로 채우지 않는다.
            // (기본 목표를 소급 적용하면 목표 설정 이전의 지난 날짜까지 오염됨)
            if (date.isBefore(today)) {
                continue;
            }

            if (defaultWalkGoal != null) {
                // 오늘·미래 날짜는 기록이 없어도 defaultWalkGoal이 있으면 기본 목표로 반환
                dailyData.add(WalkMonthlyResponse.WalkDaily.of(
                        date.toString(),
                        defaultWalkGoal,
                        0
                ));
            }
        }

        return dailyData;
    }

    private Member getMember(Long memberId) {
        if (memberId == null) {
            return memberUtil.getCurrentMember();
        }

        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "존재하지 않는 사용자입니다."));
    }
}
