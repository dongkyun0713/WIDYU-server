package com.widyu.auth.api;

import com.widyu.auth.application.callback.OAuthCallbackService;
import com.widyu.global.response.ApiResponseTemplate;
import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/callback")
public class OAuthCallbackController {
    
    private final OAuthCallbackService oAuthCallbackService;
    
    @GetMapping("/apple")
    public ApiResponseTemplate<String> appleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String id_token,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error
    ) throws IOException {

        String response = oAuthCallbackService.generateAppleCallbackIntentUrl(code, id_token, error);

        return ApiResponseTemplate.ok()
                .code("CALLBACK_2001")
                .message("애플 OAuth 콜백 성공")
                .body(response);
    }
}