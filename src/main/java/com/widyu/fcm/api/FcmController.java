package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.api.dto.response.FcmNotificationResponses;
import com.widyu.fcm.api.dto.response.FcmSendResponse;
import com.widyu.fcm.application.FcmService;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.member.domain.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/v1/fcm")
@RequiredArgsConstructor
public class FcmController implements FcmDocs {

    private final FcmService fcmService;

    @PostMapping()
    public ApiResponseTemplate<FcmSendResponse> pushMessage(@RequestBody FcmSendDto fcmSendDto) throws IOException {
        FcmSendResponse response = fcmService.sendMessageTo(fcmSendDto);

        return ApiResponseTemplate.ok()
                .code("FCM_2001")
                .message("푸시 메시지 전송 성공")
                .body(response);
    }

    @GetMapping()
    public ApiResponseTemplate<FcmNotificationResponses> getNotification() {
        return ApiResponseTemplate.ok()
                .code("FCM_2002")
                .message("사용자 알림 조회 성공")
                .body(fcmService.getNotificationsForCurrentUser());
    }

    @PatchMapping()
    public ApiResponseTemplate<Void> markAllAsRead() {
        fcmService.markAllAsRead();

        return ApiResponseTemplate.ok()
                .code("FCM_2003")
                .message("전체 알림 읽음 처리 완료")
                .build();
    }

    @PatchMapping("/{notificationId}")
    public ApiResponseTemplate<Void> markAsRead(@PathVariable Long notificationId) {
        fcmService.markAsRead(notificationId);

        return ApiResponseTemplate.ok()
                .code("FCM_2004")
                .message("알림 읽음 처리 완료")
                .build();
    }
}
