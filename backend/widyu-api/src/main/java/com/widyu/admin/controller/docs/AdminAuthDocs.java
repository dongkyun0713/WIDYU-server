package com.widyu.admin.controller.docs;

import com.widyu.admin.dto.response.AdminLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

public interface AdminAuthDocs {

    @Operation(summary = "관리자 로그인", description = "현재 DB 역할이 ADMIN이고 상태가 ACTIVE인 계정만 로그인할 수 있습니다.")
    @ApiResponse(responseCode = "403", description = "현재 관리자 권한이 없거나 비활성 상태입니다.")
    AdminLoginResponse login(Map<String, String> request, HttpServletResponse response);

    @Operation(summary = "관리자 토큰 재발급", description = "admin_refresh_token 쿠키를 검증하고 현재 DB의 관리자 권한·활성 상태를 확인합니다.")
    @ApiResponse(responseCode = "403", description = "일반 회원, 비활성 관리자 또는 존재하지 않는 회원입니다.")
    AdminLoginResponse refresh(HttpServletRequest request, HttpServletResponse response);
}
