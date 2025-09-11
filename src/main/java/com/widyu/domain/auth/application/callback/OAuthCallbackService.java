package com.widyu.domain.auth.application.callback;

import com.widyu.global.properties.CallbackProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthCallbackService {

    private final CallbackProperties callbackProperties;

    public void generateAppleCallbackIntentUrl(String code, String idToken, String error, HttpServletResponse httpServletResponse) throws IOException {
        String queryParams = buildQueryParams(code, idToken, error);
        String intentUrl = String.format("intent://callback?%s#Intent;package=%s;scheme=%s;end",
                queryParams,
                callbackProperties.packageName(),
                callbackProperties.schemes().apple());

        logCallbackResult(error);
        httpServletResponse.sendRedirect(intentUrl);
    }

    private String buildQueryParams(String code, String idToken, String error) {
        if (error != null) {
            return String.format("error=%s", urlEncode(error));
        }

        return String.format("code=%s&id_token=%s",
                urlEncode(code != null ? code : ""),
                urlEncode(idToken != null ? idToken : ""));
    }

    private void logCallbackResult(String error) {
        if (error != null) {
            log.warn("애플 OAuth 콜백 에러: {}", error);
            return;
        }

        log.info("애플 OAuth 콜백 성공, 인텐트 URL 생성");
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}