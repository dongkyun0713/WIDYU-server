package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.OAuthTokenResponse;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.SocialAccount;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialLoginService {
    private final Map<OAuthProvider, OAuthClient> oAuthClients;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthStateService oAuthStateService;

    @Transactional(readOnly = true)
    public void redirectToOAuthProvider(final OAuthProvider provider, HttpServletResponse response) throws IOException {
        OAuthClient oAuthClient = oAuthClients.get(provider);
        oAuthClient.getAuthCode(provider, response);
    }

    @Transactional(readOnly = true)
    public OAuthTokenResponse getToken(final OAuthProvider provider, final String code, final String state) {
        oAuthStateService.validateAndConsumeState(state);

        OAuthClient oAuthClient = oAuthClients.get(provider);
        return oAuthClient.getToken(code, state);
    }

    @Transactional(readOnly = true)
    public SocialClientResponse authenticateFromProvider(final OAuthProvider provider, final String token) {
        OAuthClient oAuthClient = oAuthClients.get(provider);
        if (oAuthClient == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return oAuthClient.getUserInfo(token);
    }

    @Transactional
    public TokenPairResponse socialLogin(SocialLoginRequest request) {
        Member member = memberRepository
                .findByProviderAndOauthId(request.oAuthProvider(), request.oAuthId())
                .orElseGet(() -> createMember(request));

        return getLoginResponse(member);
    }

    private Member createMember(SocialLoginRequest request) {
        Member member = Member.createMember(MemberType.GUARDIAN, request.name(), request.phoneNumber());
        SocialAccount socialAccount = SocialAccount.createSocialAccount(
                request.email(),
                request.oAuthProvider(),
                request.oAuthId(),
                member
        );
        member.getSocialAccounts().add(socialAccount);
        memberRepository.save(member);

        return member;
    }

    private TokenPairResponse getLoginResponse(Member member) {
        return jwtTokenProvider.generateTokenPair(member.getId(), MemberRole.USER);
    }
}
