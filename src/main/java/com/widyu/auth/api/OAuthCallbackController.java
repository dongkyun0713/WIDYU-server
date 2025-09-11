package com.widyu.auth.api;

import com.widyu.auth.application.callback.OAuthCallbackService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
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

    @CrossOrigin(origins = "https://appleid.apple.com")
    @PostMapping(value = "/apple",consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void appleCallbackPost(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String id_token,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse httpServletResponse
    ) throws IOException {

        oAuthCallbackService.generateAppleCallbackIntentUrl(code, id_token, error, httpServletResponse);
    }
}
