package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.PhoneNumberUtil;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.SocialAccount;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {
    private final Map<OAuthProvider, OAuthClient> oAuthClients;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberUtil temporaryMemberUtil;

    @Transactional
    public SocialLoginResponse socialLogin(String providerName, SocialLoginRequest request) {
        OAuthProvider provider = OAuthProvider.from(providerName);

        String token = getTokenForProvider(provider, request);
        SocialClientResponse socialUserInfo = authenticateFromProvider(provider, token);

        Member member = memberRepository
                .findByProviderAndOauthId(provider.getValue(), socialUserInfo.oauthId())
                .map(existingMember -> {
                    log.info("기존 사용자 로그인: providerId={}, oauthId={}",
                            provider.getValue(), socialUserInfo.oauthId());
                    existingMember.markSocialAsNotFirst(provider.getValue(), socialUserInfo.oauthId());
                    return existingMember;
                })
                .orElseGet(() -> {
                    log.info("신규 사용자 생성: providerId={}, oauthId={}",
                            provider.getValue(), socialUserInfo.oauthId());
                    return createMember(provider, socialUserInfo, request);
                });

        boolean isFirstLogin = member.getSocialAccount(provider.getValue()).isFirst();
        TokenPairResponse tokenPair = generateTokenPair(member);
        UserProfile profile = createUserProfile(member);

        return SocialLoginResponse.of(
                isFirstLogin,
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                profile
        );
    }

    public void updatePhoneNumberIfAppleSignUp(AppleSignUpRequest request, HttpServletRequest httpServletRequest) {
        Member member = memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider(request.email(),
                        OAuthProvider.APPLE.getValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);
        String phoneNumber = temporaryMember.getPhoneNumber();

        member.updatePhoneNumber(phoneNumber);
    }

    private String getTokenForProvider(OAuthProvider provider, SocialLoginRequest request) {
        return switch (provider) {
            case APPLE -> {
                if (request.authorizationCode() == null || request.authorizationCode().isBlank()) {
                    throw new BusinessException(ErrorCode.APPLE_AUTHORIZATION_CODE_IS_BLANK);
                }
                yield request.authorizationCode();
            }
            case NAVER, KAKAO -> {
                if (request.accessToken() == null || request.accessToken().isBlank()) {
                    throw new BusinessException(ErrorCode.OAUTH_ACCESS_TOKEN_IS_BLANK);
                }
                yield request.accessToken();
            }
        };
    }

    private SocialClientResponse authenticateFromProvider(final OAuthProvider provider, final String token) {
        OAuthClient oAuthClient = oAuthClients.get(provider);
        if (oAuthClient == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return oAuthClient.getUserInfo(token);
    }

    private Member createMember(OAuthProvider provider, SocialClientResponse socialUserInfo,
                                SocialLoginRequest request) {
        UserInfo userInfo = extractUserInfo(provider, socialUserInfo, request);

        validateRequiredUserInfo(userInfo, provider);

        Member member = findExistingMember(userInfo)
                .orElseGet(() -> {
                    log.info("신규 멤버 생성: provider={}, phoneNumber={}, email={}",
                            provider.getValue(), userInfo.phoneNumber(), userInfo.email());
                    return Member.createMember(
                            MemberType.GUARDIAN,
                            userInfo.name(),
                            userInfo.phoneNumber()
                    );
                });

        SocialAccount socialAccount = SocialAccount.createSocialAccount(
                userInfo.email(),
                provider.getValue(),
                socialUserInfo.oauthId(),
                member
        );

        member.getSocialAccounts().add(socialAccount);
        memberRepository.save(member);

        if (member.getId() != null) {
            log.info("기존 멤버에 소셜 계정 추가: memberId={}, provider={}, email={}",
                    member.getId(), provider.getValue(), userInfo.email());
        } else {
            log.info("신규 멤버 생성 완료: provider={}, email={}",
                    provider.getValue(), userInfo.email());
        }

        return member;
    }

    private Optional<Member> findExistingMember(UserInfo userInfo) {
        String normalizedPhone = PhoneNumberUtil.normalize(userInfo.phoneNumber());

        if (normalizedPhone != null && !normalizedPhone.isBlank()) {
            Optional<Member> memberByPhone = memberRepository.findByPhoneNumber(normalizedPhone);
            if (memberByPhone.isPresent()) {
                log.info("전화번호로 기존 멤버 찾음: phoneNumber={}", normalizedPhone);
                return memberByPhone;
            }
        }

        if (userInfo.email() != null && !userInfo.email().isBlank()) {
            Optional<Member> memberByEmail = memberRepository.findBySocialAccounts_Email(userInfo.email());
            if (memberByEmail.isPresent()) {
                log.info("이메일로 기존 멤버 찾음: email={}", userInfo.email());
                return memberByEmail;
            }
        }

        return Optional.empty();
    }

    private UserInfo extractUserInfo(OAuthProvider provider, SocialClientResponse socialUserInfo,
                                     SocialLoginRequest request) {
        if (provider == OAuthProvider.APPLE) {
            return extractAppleUserInfo(socialUserInfo, request);
        }

        String normalizedPhone = PhoneNumberUtil.normalize(socialUserInfo.phoneNumber());
        return UserInfo.of(
                socialUserInfo.name(),
                socialUserInfo.email(),
                normalizedPhone
        );
    }

    private UserInfo extractAppleUserInfo(SocialClientResponse socialUserInfo, SocialLoginRequest request) {
        String name = socialUserInfo.name();
        String email = socialUserInfo.email();
        String phoneNumber = socialUserInfo.phoneNumber();

        if (request.profile() != null) {
            name = getValueOrDefault(name, request.profile().name());
            email = getValueOrDefault(email, request.profile().email());
        }

        String normalizedPhone = PhoneNumberUtil.normalize(phoneNumber);
        return UserInfo.of(name, email, normalizedPhone);
    }

    private String getValueOrDefault(String currentValue, String defaultValue) {
        return (currentValue != null && !currentValue.isBlank()) ? currentValue : defaultValue;
    }

    private void validateRequiredUserInfo(UserInfo userInfo, OAuthProvider provider) {
        if (userInfo.email() == null || userInfo.email().isBlank()) {
            log.error("이메일 정보가 없음: provider={}", provider.getValue());
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }

        if (provider != OAuthProvider.APPLE && (userInfo.name() == null || userInfo.name().isBlank())) {
            log.error("이름 정보가 없음: provider={}",
                    provider.getValue() + ", email=" + userInfo.email() + ", name=" + userInfo.name());
            throw new BusinessException(ErrorCode.SOCIAL_NAME_NOT_PROVIDED);
        }
    }

    private TokenPairResponse generateTokenPair(Member member) {
        return jwtTokenProvider.generateTokenPair(member.getId(), MemberRole.USER);
    }

    private UserProfile createUserProfile(Member member) {
        List<String> providers = member.getSocialAccounts().stream()
                .map(SocialAccount::getProvider)
                .toList();

        String email = member.getSocialAccounts().stream()
                .map(SocialAccount::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);

        return UserProfile.of(
                member.getName(),
                member.getPhoneNumber(),
                email,
                providers
        );
    }

    private record UserInfo(
            String name,
            String email,
            String phoneNumber
    ) {
        public static UserInfo of(String name, String email, String phoneNumber) {
            return new UserInfo(name, email, phoneNumber);
        }
    }
}
