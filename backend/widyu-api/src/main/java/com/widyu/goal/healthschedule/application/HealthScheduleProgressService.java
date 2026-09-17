package com.widyu.goal.healthschedule.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.retry.RetryOnPointConflict;
import com.widyu.global.util.GeoUtils;
import com.widyu.global.util.MemberUtil;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.location.SeniorLocation;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class HealthScheduleProgressService {

    private static final double VISIT_COMPLETION_RADIUS_METERS = 75.0;
    private static final String VISIT_REWARD_DESCRIPTION = "건강 일정 방문";
    private static final String VISIT_REWARD_OPERATION_KEY_PREFIX = "HEALTH_SCHEDULE_REWARD:";

    private final HealthScheduleRepository healthScheduleRepository;
    private final SeniorLocationRepository seniorLocationRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileService seniorProfileService;
    private final MemberUtil memberUtil;

    /**
     * 시니어가 건강 일정을 완료 처리
     */
    @RetryOnPointConflict
    @Transactional
    public void completeSchedule(Long healthScheduleId) {
        Member currentMember = memberUtil.getCurrentMember();

        HealthSchedule healthSchedule = healthScheduleRepository.findById(healthScheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "건강 일정을 찾을 수 없습니다."));

        // 권한 체크
        validateHealthScheduleAccess(healthSchedule, currentMember);

        if (!healthSchedule.canCompleteAt(LocalDateTime.now())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "건강 일정 방문 인증은 당일 00시부터 일정 시간 30분 후까지만 가능합니다.");
        }

        SeniorLocation currentLocation = seniorLocationRepository.findBySeniorId(healthSchedule.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "최근 위치 정보가 없습니다."));

        if (!isArrivedAtSchedule(healthSchedule, currentLocation.getLatitude(), currentLocation.getLongitude())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "건강 일정 장소 반경 " + (int) VISIT_COMPLETION_RADIUS_METERS + "m 안에서만 방문 인증할 수 있습니다.");
        }

        // COMPLETED로 변경하고 최초 1회 포인트 적립
        completeAndReward(healthSchedule);
    }

    /**
     * 실시간 위치가 들어올 때, 당일 방문 인증 가능창 안의 건강 일정에 도착했으면 자동 완료 처리한다.
     */
    @Transactional
    public void completeArrivedSchedules(Long memberId, Double latitude, Double longitude) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startOfDay = now.toLocalDate().atStartOfDay();
        LocalDateTime startOfNextDay = startOfDay.plusDays(1);

        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndStatusAndDate(
                memberId, ProgressStatus.UPCOMING, startOfDay, startOfNextDay);

        for (HealthSchedule schedule : schedules) {
            if (!schedule.canCompleteAt(now)) {
                continue;
            }

            if (isArrivedAtSchedule(schedule, latitude, longitude)) {
                completeAndReward(schedule);
            }
        }
    }

    /**
     * 일정을 COMPLETED로 바꾸고, 아직 적립하지 않았다면 rewardPoint를 1회만 적립한다.
     * 적립 멱등성은 {@code isReward} 가드와 {@code PointHistory.operationKey} unique 제약이 함께 보장한다.
     * 소유자가 SENIOR가 아니면(보호자 본인 일정 생성 경로) 적립 없이 완료만 처리해,
     * SeniorProfile이 없는 회원 때문에 완료까지 롤백되지 않게 한다.
     */
    private void completeAndReward(HealthSchedule schedule) {
        schedule.complete();

        if (schedule.getIsReward()) {
            return;
        }

        Member owner = schedule.getMember();
        if (owner.getType() != MemberType.SENIOR) {
            log.info("건강 일정 소유자가 시니어가 아니어서 포인트를 적립하지 않습니다: healthScheduleId={}, memberId={}",
                    schedule.getId(), owner.getId());
            return;
        }

        seniorProfileService.addPointsToMember(
                owner.getId(),
                schedule.getRewardPoint().longValue(),
                VISIT_REWARD_DESCRIPTION,
                VISIT_REWARD_OPERATION_KEY_PREFIX + schedule.getId()
        );
        schedule.claimReward();
    }

    private void validateHealthScheduleAccess(HealthSchedule healthSchedule, Member currentMember) {
        if (currentMember.getType() == MemberType.SENIOR) {
            // 시니어는 본인의 일정만 접근 가능
            if (!healthSchedule.getMember().getId().equals(currentMember.getId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "해당 일정에 접근할 권한이 없습니다.");
            }
        } else if (currentMember.getType() == MemberType.GUARDIAN) {
            // 보호자는 연결된 시니어의 일정만 접근 가능
            SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(healthSchedule.getMember().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

            boolean isConnected = familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(
                    currentMember.getId(), seniorProfile.getId()
            );

            if (!isConnected) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "해당 일정에 접근할 권한이 없습니다.");
            }
        }
    }

    private boolean isArrivedAtSchedule(HealthSchedule schedule, Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return false;
        }

        if (schedule.getLatitude() == null || schedule.getLongitude() == null) {
            return false;
        }

        return GeoUtils.isWithinRadius(
                latitude,
                longitude,
                schedule.getLatitude(),
                schedule.getLongitude(),
                VISIT_COMPLETION_RADIUS_METERS
        );
    }

    /**
     * 완료 허용창(예정 시각 + COMPLETION_GRACE_MINUTES)이 지난 UPCOMING 일정을 INCOMPLETE로 변경.
     * 표시 상태(getDisplayProgressStatus)와 동일한 경계를 사용해, 아직 인증 가능한 일정을
     * 자정에 조기 마감하지 않는다. 배치가 하루 이상 누락되어도 밀린 지난 일정까지 일괄 정리한다.
     */
    @Transactional
    public void markOverdueSchedulesAsIncomplete() {
        LocalDateTime completionWindowClosedBefore =
                LocalDateTime.now().minusMinutes(HealthSchedule.COMPLETION_GRACE_MINUTES);

        List<HealthSchedule> overdueSchedules = healthScheduleRepository.findByStatusAndScheduledAtBefore(
                ProgressStatus.UPCOMING, completionWindowClosedBefore
        );

        for (HealthSchedule schedule : overdueSchedules) {
            schedule.markIncomplete();
        }
    }
}
