package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.request.FcmTokenLoginRequest;
import com.widyu.fcm.api.dto.request.FcmTokenLogoutRequest;
import com.widyu.fcm.application.MemberFcmTokenService;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 로그인/로그아웃 시 FCM 토큰 처리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fcm/token")
@RequiredArgsConstructor
public class MemberFcmTokenController {

    private final MemberFcmTokenService memberFcmTokenService;

    @PostMapping("/login")
    public ApiResponseTemplate<Void> saveFcmToken(
            @RequestBody FcmTokenLoginRequest fcmTokenLoginRequest
    ) {
        memberFcmTokenService.saveOrActivateFcmToken(
                fcmTokenLoginRequest.token(),
                fcmTokenLoginRequest.deviceInfo()
        );

        return ApiResponseTemplate.ok()
                .code("FCM_2002")
                .message("로그인 시 FCM 토큰 처리 완료")
                .build();
    }

    @PostMapping("/logout")
    public ApiResponseTemplate<Void> deactivateFcmToken(
            @RequestBody FcmTokenLogoutRequest fcmTokenLogoutRequest
    ) {
        memberFcmTokenService.deactivateFcmToken(fcmTokenLogoutRequest.token());

        return ApiResponseTemplate.ok()
                .code("FCM_2003")
                .message("로그아웃 시 FCM 토큰 비활성화 완료")
                .build();
    }
}
