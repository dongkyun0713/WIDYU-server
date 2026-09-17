package com.widyu.goal.healthschedule.controller;

import com.widyu.goal.healthschedule.application.HealthScheduleFacade;
import com.widyu.goal.healthschedule.controller.docs.HealthScheduleDocs;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCompleteRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateForSeniorRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDayResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleDetailWithRewardResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleResponse;
import com.widyu.goal.healthschedule.dto.response.HealthScheduleWeekListResponse;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/goals/health-schedules")
public class HealthScheduleController implements HealthScheduleDocs {

    private final HealthScheduleFacade healthScheduleFacade;

    @PostMapping("/seniors")
    public ApiResponseTemplate<HealthScheduleResponse> createHealthScheduleForMe(
            @Valid @RequestBody HealthScheduleCreateRequest request
    ) {
        HealthScheduleResponse response = healthScheduleFacade.createHealthScheduleForMe(request);

        return ApiResponseTemplate.ok()
                .code("HLTH_2001")
                .message("건강 일정이 생성되었습니다.")
                .body(response);
    }

    @PostMapping("/guardians")
    public ApiResponseTemplate<HealthScheduleResponse> createHealthScheduleForSenior(
            @Valid @RequestBody HealthScheduleCreateForSeniorRequest request
    ) {
        HealthScheduleResponse response = healthScheduleFacade.createHealthScheduleForSenior(request);

        return ApiResponseTemplate.ok()
                .code("HLTH_2001")
                .message("건강 일정이 생성되었습니다.")
                .body(response);
    }

    @PatchMapping("/{healthScheduleId}")
    public ApiResponseTemplate<HealthScheduleResponse> updateHealthSchedule(
            @PathVariable Long healthScheduleId,
            @RequestBody HealthScheduleUpdateRequest request
    ) {
        HealthScheduleResponse response = healthScheduleFacade.updateHealthSchedule(healthScheduleId, request);

        return ApiResponseTemplate.ok()
                .code("HLTH_2002")
                .message("건강 일정이 수정되었습니다.")
                .body(response);
    }

    @DeleteMapping("/{healthScheduleId}")
    public ApiResponseTemplate<Void> deleteHealthSchedule(
            @PathVariable Long healthScheduleId
    ) {
        healthScheduleFacade.deleteHealthSchedule(healthScheduleId);

        return ApiResponseTemplate.ok()
                .code("HLTH_2003")
                .message("건강 일정이 삭제되었습니다.")
                .build();
    }

    @GetMapping("/calendar/seniors")
    public ApiResponseTemplate<List<HealthScheduleDayResponse>> getHealthScheduleCalendarForMe(
            @RequestParam int year,
            @RequestParam int month
    ) {
        List<HealthScheduleDayResponse> response = healthScheduleFacade.getHealthScheduleCalendarForMe(year, month);

        return ApiResponseTemplate.ok()
                .code("HLTH_2004")
                .message("건강 일정 캘린더 조회가 완료되었습니다.")
                .body(response);
    }

    @GetMapping("/calendar/guardians/{memberId}")
    public ApiResponseTemplate<List<HealthScheduleDayResponse>> getHealthScheduleCalendarForSenior(
            @PathVariable Long memberId,
            @RequestParam int year,
            @RequestParam int month
    ) {
        List<HealthScheduleDayResponse> response = healthScheduleFacade.getHealthScheduleCalendarForSenior(memberId, year, month);

        return ApiResponseTemplate.ok()
                .code("HLTH_2004")
                .message("건강 일정 캘린더 조회가 완료되었습니다.")
                .body(response);
    }

    @GetMapping("/daily/seniors")
    public ApiResponseTemplate<List<HealthScheduleDetailWithRewardResponse>> getHealthSchedulesByDateForMe(
            @RequestParam LocalDate date
    ) {
        List<HealthScheduleDetailWithRewardResponse> response = healthScheduleFacade.getHealthSchedulesByDateForMe(date);

        return ApiResponseTemplate.ok()
                .code("HLTH_2005")
                .message("건강 일정 상세 조회가 완료되었습니다.")
                .body(response);
    }

    @GetMapping("/daily/guardians/{memberId}")
    public ApiResponseTemplate<List<HealthScheduleDetailResponse>> getHealthSchedulesByDateForSenior(
            @PathVariable Long memberId,
            @RequestParam LocalDate date
    ) {
        List<HealthScheduleDetailResponse> response = healthScheduleFacade.getHealthSchedulesByDateForSenior(memberId, date);

        return ApiResponseTemplate.ok()
                .code("HLTH_2005")
                .message("건강 일정 상세 조회가 완료되었습니다.")
                .body(response);
    }

    @GetMapping("/weekly")
    public ApiResponseTemplate<HealthScheduleWeekListResponse> getHealthSchedulesForWeek() {
        HealthScheduleWeekListResponse response = healthScheduleFacade.getHealthSchedulesForWeek();

        return ApiResponseTemplate.ok()
                .code("HLTH_2007")
                .message("일주일치 건강 일정 조회가 완료되었습니다.")
                .body(response);
    }

    @PostMapping("/complete")
    public ApiResponseTemplate<Void> completeSchedule(
            @RequestBody HealthScheduleCompleteRequest request
    ) {
        healthScheduleFacade.completeSchedule(request.healthScheduleId());

        return ApiResponseTemplate.ok()
                .code("HLTH_2008")
                .message("건강 일정이 완료 처리되었습니다.")
                .build();
    }
}