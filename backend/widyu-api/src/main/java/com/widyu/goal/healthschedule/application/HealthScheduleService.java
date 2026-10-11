package com.widyu.goal.healthschedule.application;

import com.widyu.global.util.MemberUtil;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateForSeniorRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDayResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailWithRewardResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleWeekListResponse;
import com.widyu.goal.event.GuardianGoalChangedEvent;
import com.widyu.fcm.NotificationType;
import java.time.LocalDate;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleResponse;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HealthScheduleService {

    private final HealthScheduleRepository healthScheduleRepository;
    private final MemberRepository memberRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final MemberUtil memberUtil;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 시니어가 본인 일정 생성
     */
    @Transactional
    public HealthScheduleResponse createHealthScheduleForMe(HealthScheduleCreateRequest request) {
        Member member = memberUtil.getCurrentMember();

        HealthSchedule healthSchedule = HealthSchedule.create(
                member,
                request.scheduleName(),
                request.placeAddress(),
                request.latitude(),
                request.longitude(),
                request.scheduledAt()
        );

        HealthSchedule saved = healthScheduleRepository.save(healthSchedule);

        return HealthScheduleResponse.from(saved);
    }

    /**
     * 보호자가 시니어 일정 생성
     */
    @Transactional
    public HealthScheduleResponse createHealthScheduleForSenior(HealthScheduleCreateForSeniorRequest request) {
        Member currentMember = memberUtil.getCurrentMember();

        // 시니어 조회
        Member seniorMember = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "시니어를 찾을 수 없습니다."));

        // 시니어 프로필 조회
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(request.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        // 보호자-시니어 연결 확인
        boolean isConnected = familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(
                currentMember.getId(), seniorProfile.getId()
        );

        if (!isConnected) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어의 일정을 생성할 권한이 없습니다.");
        }

        HealthSchedule healthSchedule = HealthSchedule.create(
                seniorMember,
                request.scheduleName(),
                request.placeAddress(),
                request.latitude(),
                request.longitude(),
                request.scheduledAt()
        );

        HealthSchedule saved = healthScheduleRepository.save(healthSchedule);

        publishGuardianChange(currentMember, seniorMember, NotificationType.HEALTH_SCHEDULE_CREATED, saved.getId());

        return HealthScheduleResponse.from(saved);
    }

    @Transactional
    public HealthScheduleResponse updateHealthSchedule(Long healthScheduleId, HealthScheduleUpdateRequest request) {
        HealthSchedule healthSchedule = healthScheduleRepository.findById(healthScheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "건강 일정을 찾을 수 없습니다."));

        // 권한 체크
        validateHealthScheduleAccess(healthSchedule);

        healthSchedule.update(
                request.scheduleName(),
                request.placeAddress(),
                request.latitude(),
                request.longitude(),
                request.scheduledAt(),
                request.progressStatus()
        );

        publishGuardianChange(memberUtil.getCurrentMember(), healthSchedule.getMember(),
                NotificationType.HEALTH_SCHEDULE_UPDATED, healthSchedule.getId());

        return HealthScheduleResponse.from(healthSchedule);
    }

    @Transactional
    public void deleteHealthSchedule(Long healthScheduleId) {
        HealthSchedule healthSchedule = healthScheduleRepository.findById(healthScheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "건강 일정을 찾을 수 없습니다."));

        // 권한 체크
        validateHealthScheduleAccess(healthSchedule);

        healthScheduleRepository.delete(healthSchedule);
        publishGuardianChange(memberUtil.getCurrentMember(), healthSchedule.getMember(),
                NotificationType.HEALTH_SCHEDULE_DELETED, healthScheduleId);
    }

    private void publishGuardianChange(Member actor, Member target, NotificationType type, Long scheduleId) {
        if (actor.getType() != MemberType.GUARDIAN || target.getType() != MemberType.SENIOR
                || actor.getId().equals(target.getId())) {
            return;
        }
        eventPublisher.publishEvent(GuardianGoalChangedEvent.healthSchedule(
                target.getId(), type, scheduleId, actor.getName()));
    }


    private void validateHealthScheduleAccess(HealthSchedule healthSchedule) {
        Member currentMember = memberUtil.getCurrentMember();

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

    /**
     * 시니어 본인 캘린더 조회
     */
    public List<HealthScheduleDayResponse> getHealthScheduleCalendarForMe(int year, int month) {
        Member currentMember = memberUtil.getCurrentMember();

        // 해당 월의 시작일과 종료일 계산
        LocalDateTime startDate = LocalDateTime.of(year, month, 1, 0, 0, 0);
        LocalDateTime endDate = startDate.plusMonths(1);

        // 해당 월의 일정 조회
        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndYearMonth(
                currentMember.getId(), startDate, endDate
        );

        return schedules.stream()
                .map(HealthScheduleDayResponse::from)
                .toList();
    }

    /**
     * 보호자가 시니어 캘린더 조회
     */
    public List<HealthScheduleDayResponse> getHealthScheduleCalendarForSenior(Long memberId, int year, int month) {
        Member currentMember = memberUtil.getCurrentMember();

        // 시니어 프로필 조회
        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        // 보호자-시니어 연결 확인
        boolean isConnected = familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(
                currentMember.getId(), seniorProfile.getId()
        );

        if (!isConnected) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어의 일정을 조회할 권한이 없습니다.");
        }

        // 해당 월의 시작일과 종료일 계산
        LocalDateTime startDate = LocalDateTime.of(year, month, 1, 0, 0, 0);
        LocalDateTime endDate = startDate.plusMonths(1);

        // 해당 월의 일정 조회
        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndYearMonth(
                memberId, startDate, endDate
        );

        return schedules.stream()
                .map(HealthScheduleDayResponse::from)
                .toList();
    }

    public List<HealthScheduleDetailWithRewardResponse> getHealthSchedulesByDateForMe(LocalDate date) {
        Member currentMember = memberUtil.getCurrentMember();

        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndDate(
                currentMember.getId(), date.atStartOfDay(), date.plusDays(1).atStartOfDay()
        );

        return schedules.stream()
                .map(HealthScheduleDetailWithRewardResponse::from)
                .toList();
    }

    public List<HealthScheduleDetailResponse> getHealthSchedulesByDateForSenior(Long memberId, LocalDate date) {
        Member currentMember = memberUtil.getCurrentMember();

        SeniorProfile seniorProfile = seniorProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SENIOR_PROFILE_NOT_FOUND));

        boolean isConnected = familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(
                currentMember.getId(), seniorProfile.getId()
        );

        if (!isConnected) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "해당 시니어의 일정을 조회할 권한이 없습니다.");
        }

        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndDate(
                memberId, date.atStartOfDay(), date.plusDays(1).atStartOfDay()
        );

        return schedules.stream()
                .map(HealthScheduleDetailResponse::from)
                .toList();
    }

    /**
     * 시니어 본인 일주일치 일정 조회 (로그인 시)
     */
    public HealthScheduleWeekListResponse getHealthSchedulesForWeek() {
        Member currentMember = memberUtil.getCurrentMember();

        // 오늘부터 7일 후까지
        LocalDateTime startDate = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime endDate = startDate.plusDays(7);

        // 일주일치 일정 조회
        List<HealthSchedule> schedules = healthScheduleRepository.findByMemberIdAndWeek(
                currentMember.getId(), startDate, endDate
        );

        return HealthScheduleWeekListResponse.from(schedules);
    }
}
