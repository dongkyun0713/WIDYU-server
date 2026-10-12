package com.widyu.admin.controller.docs;

import com.widyu.admin.dto.response.AdminAccessLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;

@Tag(name = "관리자 접속기록", description = "관리자의 개인정보 조회·변경 접속기록을 조회합니다.")
public interface AdminAccessLogDocs {

    @Operation(summary = "관리자 접속기록 조회",
            description = "관리자 ID와 기간으로 걸러 최근순으로 조회합니다. 요청 본문과 인증 헤더는 기록하지 않습니다.")
    ApiResponseTemplate<AdminPageResponse<AdminAccessLogResponse>> getAccessLogs(
            Long adminId, LocalDateTime from, LocalDateTime to,
            @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @Min(value = 1, message = "size는 1 이상 100 이하여야 합니다.")
            @Max(value = 100, message = "size는 1 이상 100 이하여야 합니다.") int size);
}
