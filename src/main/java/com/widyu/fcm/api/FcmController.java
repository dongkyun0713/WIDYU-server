package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.application.FcmService;
import com.widyu.global.response.ApiResponseTemplate;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/api/v1/fcm")
@RequiredArgsConstructor
public class FcmController implements FcmDocs{

    private final FcmService fcmService;

    @PostMapping("/send")
    public ApiResponseTemplate<Integer> pushMessage(
            @RequestBody @Valid FcmSendDto fcmSendDto
    ) throws IOException {
        log.debug("[+] 로그인 유저에게 푸시 메시지를 전송합니다.");

        int result = fcmService.sendMessageTo(fcmSendDto);

        return ApiResponseTemplate.ok()
                .code("FCM_2001")
                .message("푸시 메시지 전송 성공")
                .body(result);
    }
}
