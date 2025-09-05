package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.SocialAccount;
import com.widyu.member.repository.MemberRepository;
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


    @Transactional(readOnly = true)
    public SocialClientResponse authenticateFromProvider(final OAuthProvider provider, final String token) {
        OAuthClient oAuthClient = oAuthClients.get(provider);
        if (oAuthClient == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return oAuthClient.getUserInfo(token);
    }

    @Transactional
    public SocialLoginResponse socialLogin(String providerName, SocialLoginRequest request) {
        OAuthProvider provider = OAuthProvider.from(providerName);
        
        SocialClientResponse socialUserInfo = authenticateFromProvider(provider, request.accessToken());
        
        Member member = memberRepository
                .findByProviderAndOauthId(provider.getValue(), socialUserInfo.oauthId())
                .map(m -> {
                    m.markSocialAsNotFirst(provider.getValue(), socialUserInfo.oauthId());
                    return m;
                })
                .orElseGet(() -> createMember(provider, socialUserInfo));

        return SocialLoginResponse.of(member.getSocialAccount(provider.getValue()).isFirst(),
                getLoginResponse(member));
    }

    private Member createMember(OAuthProvider provider, SocialClientResponse socialUserInfo) {
        Member member = Member.createMember(MemberType.GUARDIAN, socialUserInfo.name(), socialUserInfo.phoneNumber());
        SocialAccount socialAccount = SocialAccount.createSocialAccount(
                socialUserInfo.email(),
                provider.getValue(),
                socialUserInfo.oauthId(),
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
