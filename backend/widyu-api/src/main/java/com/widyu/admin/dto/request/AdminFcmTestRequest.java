package com.widyu.admin.dto.request;

import com.widyu.fcm.FcmCategory;
import jakarta.validation.constraints.Size;

public record AdminFcmTestRequest(
        Long memberId,
        @Size(max = 255, message = "알림 제목은 255자 이하로 입력해 주세요.")
        String title,
        @Size(max = 255, message = "알림 내용은 255자 이하로 입력해 주세요.")
        String content,
        FcmCategory category
) {}
