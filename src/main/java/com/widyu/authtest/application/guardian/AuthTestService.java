package com.widyu.authtest.application.guardian;

import com.widyu.authtest.application.guardian.oauth.SocialLoginTestService;
import com.widyu.authtest.domain.OAuthProvider;
import com.widyu.authtest.dto.request.SocialLoginRequest;
import com.widyu.authtest.dto.response.OAuthTokenResponse;
import com.widyu.authtest.dto.response.SocialClientResponse;
import com.widyu.authtest.dto.response.SocialLoginResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthTestService {

    private final SocialLoginTestService socialLoginTestService;

    @Transactional
    public String redirectToSocialLogin(String provider, HttpServletResponse response) throws IOException {
        OAuthProvider oauthProvider = OAuthProvider.from(provider);
        return socialLoginTestService.redirectToOAuthProvider(oauthProvider, response);
    }

    @Transactional
    public SocialLoginResponse processSocialLoginCallback(String provider, String code, String state) {
        OAuthProvider oauthProvider = OAuthProvider.from(provider);

        OAuthTokenResponse oAuthTokenResponse = socialLoginTestService.getToken(oauthProvider, code, state);

        SocialClientResponse socialClientResponse = socialLoginTestService.authenticateFromProvider(
                oauthProvider, oAuthTokenResponse.accessToken());

        SocialLoginRequest socialLoginRequest = SocialLoginRequest.of(
                oauthProvider.getValue(),
                socialClientResponse.oauthId(),
                socialClientResponse.email(),
                socialClientResponse.name(),
                socialClientResponse.phoneNumber()
        );

        return socialLoginTestService.socialLogin(socialLoginRequest);
    }

    public String getNaverAccessToken(String code, String state) {
        OAuthProvider naverProvider = OAuthProvider.NAVER;
        OAuthTokenResponse tokenResponse = socialLoginTestService.getToken(naverProvider, code, state);
        return tokenResponse.accessToken();
    }

    public String generateNaverAuthUrl() {
        OAuthProvider naverProvider = OAuthProvider.NAVER;
        return socialLoginTestService.generateAuthUrl(naverProvider);
    }
}
