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

public interface HealthScheduleFacade {

    /**
     * 시니어가 본인 건강 일정 생성
     */
    HealthScheduleResponse createHealthScheduleForMe(HealthScheduleCreateRequest request);

    /**
     * 보호자가 시니어 건강 일정 생성
     */
    HealthScheduleResponse createHealthScheduleForSenior(HealthScheduleCreateForSeniorRequest request);

    /**
     * 건강 일정 수정
     */
    HealthScheduleResponse updateHealthSchedule(Long healthScheduleId, HealthScheduleUpdateRequest request);

    /**
     * 건강 일정 삭제 (논리 삭제)
     */
    void deleteHealthSchedule(Long healthScheduleId);

    /**
     * 시니어 본인 건강 일정 캘린더 조회
     */
    List<HealthScheduleDayResponse> getHealthScheduleCalendarForMe(int year, int month);

    /**
     * 보호자가 시니어 건강 일정 캘린더 조회
     */
    List<HealthScheduleDayResponse> getHealthScheduleCalendarForSenior(Long seniorId, int year, int month);

    /**
     * 시니어 본인 특정 날짜 일정 조회
     */
    List<HealthScheduleDetailWithRewardResponse> getHealthSchedulesByDateForMe(LocalDate date);

    /**
     * 보호자가 시니어 특정 날짜 일정 조회
     */
    List<HealthScheduleDetailResponse> getHealthSchedulesByDateForSenior(Long seniorId, LocalDate date);

    /**
     * 시니어 본인 일주일치 일정 조회 (로그인 시)
     */
    HealthScheduleWeekListResponse getHealthSchedulesForWeek();

    /**
     * 건강 일정 완료 처리
     */
    void completeSchedule(Long healthScheduleId);
}
