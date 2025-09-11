package com.widyu.authtest.application.guardian.oauth;

import com.widyu.domain.auth.dto.response.TokenPairResponse;
import com.widyu.authtest.domain.OAuthProvider;
import com.widyu.authtest.dto.request.SocialLoginRequest;
import com.widyu.authtest.dto.response.OAuthTokenResponse;
import com.widyu.authtest.dto.response.SocialClientResponse;
import com.widyu.authtest.dto.response.SocialLoginResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.domain.member.entity.Member;
import com.widyu.domain.member.entity.MemberRole;
import com.widyu.domain.member.entity.MemberType;
import com.widyu.domain.member.entity.SocialAccount;
import com.widyu.domain.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SocialLoginTestService {
    private final Map<OAuthProvider, OAuthTestClient> oAuthClients;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthStateTestService oAuthStateTestService;

    @Transactional(readOnly = true)
    public String redirectToOAuthProvider(final OAuthProvider provider, HttpServletResponse response) throws IOException {
        OAuthTestClient oAuthTestClient = oAuthClients.get(provider);
        return oAuthTestClient.getAuthCode(provider, response);
    }

    @Transactional(readOnly = true)
    public String generateAuthUrl(final OAuthProvider provider) {
        OAuthTestClient oAuthTestClient = oAuthClients.get(provider);
        return oAuthTestClient.generateAuthUrl(provider);
    }

    @Transactional(readOnly = true)
    public OAuthTokenResponse getToken(final OAuthProvider provider, final String code, final String state) {
        oAuthStateTestService.validateAndConsumeState(state);

        OAuthTestClient oAuthTestClient = oAuthClients.get(provider);
        return oAuthTestClient.getToken(code, state);
    }

    @Transactional(readOnly = true)
    public SocialClientResponse authenticateFromProvider(final OAuthProvider provider, final String token) {
        OAuthTestClient oAuthTestClient = oAuthClients.get(provider);
        if (oAuthTestClient == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return oAuthTestClient.getUserInfo(token);
    }

    @Transactional
    public SocialLoginResponse socialLogin(SocialLoginRequest request) {
        Member member = memberRepository
                .findByProviderAndOauthId(request.oAuthProvider(), request.oAuthId())
                .map(m -> {
                    m.markSocialAsNotFirst(request.oAuthProvider(), request.oAuthId());
                    return m;
                })
                .orElseGet(() -> createMember(request));

        return SocialLoginResponse.of(member.getSocialAccount(request.oAuthProvider()).isFirst(),
                getLoginResponse(member));
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
        // 테스트용 소셜 로그인이므로 첫 번째 소셜 계정의 provider를 loginType으로 사용
        String loginType = member.getSocialAccounts().stream()
                .findFirst()
                .map(account -> account.getProvider())
                .orElse("test");
        return jwtTokenProvider.generateTokenPair(member.getId(), MemberRole.USER, loginType);
    }
}
