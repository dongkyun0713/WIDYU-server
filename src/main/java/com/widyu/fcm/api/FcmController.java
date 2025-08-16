package com.widyu.fcm.api;

import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.application.FcmService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FCM 관리하는 Controller
 *
 * @author : lee
 * @fileName : FcmController
 * @since : 2/21/24
 */

@Slf4j
@RestController
@RequestMapping("/api/v1/fcm")
@RequiredArgsConstructor
public class FcmController {

    private final FcmService fcmService;

    @PostMapping("/send")
    public ResponseEntity<Integer> pushMessage(
            @RequestBody @Validated FcmSendDto fcmSendDto) throws IOException {

        log.debug("[+] 푸시 메시지를 전송합니다.");
        int result = fcmService.sendMessageTo(fcmSendDto);

        return ResponseEntity.ok(result);
    }

}