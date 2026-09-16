package com.widyu.goal.medicineschedule.controller;

import com.widyu.global.annotation.ValidateFamilyAccess;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.goal.medicineschedule.application.ExternalMedicineService;
import com.widyu.goal.medicineschedule.application.MedicationProofService;
import com.widyu.goal.medicineschedule.application.MedicineScheduleService;
import com.widyu.goal.medicineschedule.application.MedicationAlarmSyncService;
import com.widyu.goal.medicineschedule.controller.docs.MedicineScheduleDocs;
import com.widyu.goal.medicineschedule.dto.request.CreateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.dto.request.UpdateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.dto.response.MedicationProofResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineHomeResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineMonthlyResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleDetailResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleIdResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleDailyResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicationAlarmSyncResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineSearchResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/goals/medicine-schedules")
public class MedicineScheduleController implements MedicineScheduleDocs {

    private final MedicineScheduleService medicineScheduleService;
    private final MedicationAlarmSyncService medicationAlarmSyncService;
    private final MedicationProofService medicationProofService;
    private final ExternalMedicineService externalMedicineService;

    @Override
    @GetMapping("/alarm-sync")
    public ApiResponseTemplate<MedicationAlarmSyncResponse> getAlarmSync() {
        return ApiResponseTemplate.ok().code("MEDICINE_2010").message("복약 알람 동기화 조회 성공")
                .body(medicationAlarmSyncService.getCurrentMemberSnapshot());
    }

    @Override
    @GetMapping("/daily")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<MedicineScheduleDailyResponse> getDailySchedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long memberId
    ) {
        MedicineScheduleDailyResponse response = medicineScheduleService.getDailySchedules(memberId, date);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2001")
                .message("일자별 약 복용 현황 조회 성공")
                .body(response);
    }

    @Override
    @GetMapping("/monthly")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<MedicineMonthlyResponse> getMonthlyStats(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) Long memberId
    ) {
        MedicineMonthlyResponse response = medicineScheduleService.getMonthlyStats(year, month, memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2002")
                .message("날짜별 약 조회 성공")
                .body(response);
    }

    @Override
    @GetMapping("/home")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<MedicineHomeResponse> getHomeSchedules(
            @RequestParam(required = false) Long memberId
    ) {
        MedicineHomeResponse response = medicineScheduleService.getHomeSchedules(memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2003")
                .message("시니어 약복용 홈 조회 성공")
                .body(response);
    }

    @Override
    @GetMapping("/{scheduleId}")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<MedicineScheduleDetailResponse> getScheduleDetail(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) Long memberId
    ) {
        MedicineScheduleDetailResponse response = medicineScheduleService.getScheduleDetail(scheduleId, memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2004")
                .message("약 복용 상세 조회 성공")
                .body(response);
    }

    @Override
    @PostMapping
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<MedicineScheduleIdResponse> createSchedule(
            @Valid @RequestBody CreateMedicineScheduleRequest request,
            @RequestParam(required = false) Long memberId
    ) {
        MedicineScheduleIdResponse response = medicineScheduleService.createSchedule(request, memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2005")
                .message("약 복용 스케줄 생성 성공")
                .body(response);
    }

    @Override
    @PutMapping("/{scheduleId}")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<Void> updateSchedule(
            @PathVariable Long scheduleId,
            @Valid @RequestBody UpdateMedicineScheduleRequest request,
            @RequestParam(required = false) Long memberId
    ) {
        medicineScheduleService.updateSchedule(scheduleId, request, memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2006")
                .message("약 복용 스케줄 수정 성공")
                .build();
    }

    @Override
    @DeleteMapping("/{scheduleId}")
    @ValidateFamilyAccess(memberIdParam = "memberId")
    public ApiResponseTemplate<Void> deleteSchedule(
            @PathVariable Long scheduleId,
            @RequestParam(required = false) Long memberId
    ) {
        medicineScheduleService.deleteSchedule(scheduleId, memberId);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2007")
                .message("약 복용 스케줄 삭제 성공")
                .build();
    }

    @Override
    @PostMapping("/{scheduleId}/verify")
    public ApiResponseTemplate<MedicationProofResponse> verifyMedication(
            @PathVariable Long scheduleId,
            @RequestPart(required = false) List<MultipartFile> medicationProofImage
    ) {
        MedicationProofResponse response = medicationProofService.verifyMedication(scheduleId, medicationProofImage);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2008")
                .message("약 복용 인증 성공")
                .body(response);
    }

    @Override
    @GetMapping("/search")
    public ApiResponseTemplate<MedicineSearchResponse> searchMedicines(
            @RequestParam String keyword
    ) {
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(keyword);
        return ApiResponseTemplate.ok()
                .code("MEDICINE_2009")
                .message("약품 검색 성공")
                .body(response);
    }
}
