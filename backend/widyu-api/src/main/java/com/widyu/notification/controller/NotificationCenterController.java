package com.widyu.notification.controller;

import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.notification.application.NotificationCenterService;
import com.widyu.notification.controller.docs.NotificationCenterDocs;
import com.widyu.notification.dto.response.NotificationCenterResponse;
import com.widyu.notification.dto.response.NotificationReadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationCenterController implements NotificationCenterDocs {
    private final NotificationCenterService notificationCenterService;

    @GetMapping
    public ApiResponseTemplate<NotificationCenterResponse> list(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String cursor) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("OK")
                .body(notificationCenterService.list(filter, cursor));
    }

    @PatchMapping("/{id}/read")
    public ApiResponseTemplate<NotificationReadResponse> markAsRead(@PathVariable Long id) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("OK")
                .body(notificationCenterService.markAsRead(id));
    }
}
