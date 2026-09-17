package com.widyu.goal.healthschedule.application;

import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateForSeniorRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDayResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailWithRewardResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleWeekListResponse;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealthScheduleFacadeImpl implements HealthScheduleFacade {

    private final HealthScheduleService healthScheduleService;
    private final HealthScheduleProgressService healthScheduleProgressService;

    @Override
    public HealthScheduleResponse createHealthScheduleForMe(HealthScheduleCreateRequest request) {
        return healthScheduleService.createHealthScheduleForMe(request);
    }

    @Override
    public HealthScheduleResponse createHealthScheduleForSenior(HealthScheduleCreateForSeniorRequest request) {
        return healthScheduleService.createHealthScheduleForSenior(request);
    }

    @Override
    public HealthScheduleResponse updateHealthSchedule(Long healthScheduleId, HealthScheduleUpdateRequest request) {
        return healthScheduleService.updateHealthSchedule(healthScheduleId, request);
    }

    @Override
    public void deleteHealthSchedule(Long healthScheduleId) {
        healthScheduleService.deleteHealthSchedule(healthScheduleId);
    }

    @Override
    public List<HealthScheduleDayResponse> getHealthScheduleCalendarForMe(int year, int month) {
        return healthScheduleService.getHealthScheduleCalendarForMe(year, month);
    }

    @Override
    public List<HealthScheduleDayResponse> getHealthScheduleCalendarForSenior(Long seniorId, int year, int month) {
        return healthScheduleService.getHealthScheduleCalendarForSenior(seniorId, year, month);
    }

    @Override
    public List<HealthScheduleDetailWithRewardResponse> getHealthSchedulesByDateForMe(LocalDate date) {
        return healthScheduleService.getHealthSchedulesByDateForMe(date);
    }

    @Override
    public List<HealthScheduleDetailResponse> getHealthSchedulesByDateForSenior(Long seniorId, LocalDate date) {
        return healthScheduleService.getHealthSchedulesByDateForSenior(seniorId, date);
    }

    @Override
    public HealthScheduleWeekListResponse getHealthSchedulesForWeek() {
        return healthScheduleService.getHealthSchedulesForWeek();
    }

    @Override
    public void completeSchedule(Long healthScheduleId) {
        healthScheduleProgressService.completeSchedule(healthScheduleId);
    }
}
