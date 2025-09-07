package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.domain.OAuthProvider;
import com.widyu.infrastructure.external.oauth.naver.OAuthClient;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.NewSocialAccountInfo;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginService {

    private final Map<OAuthProvider, OAuthClient> oAuthClients;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberUtil temporaryMemberUtil;

    public SocialLoginResponse socialLogin(String providerName, SocialLoginRequest request) {
        OAuthProvider provider = OAuthProvider.from(providerName);
        SocialClientResponse socialUserInfo = getSocialUserInfo(provider, request);

        Optional<Member> existingMember = findMemberByProvider(provider, socialUserInfo.oauthId());

        return existingMember.map(member -> handleExistingMemberLogin(member, provider, socialUserInfo.oauthId()))
                .orElseGet(() -> handleNewSocialAccount(provider, socialUserInfo, request));
    }

    public void updatePhoneNumberIfAppleSignUp(AppleSignUpRequest request, HttpServletRequest httpServletRequest) {
        Member member = findAppleMemberByEmail(request.email());
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);

        member.updatePhoneNumber(temporaryMember.getPhoneNumber());
    }

    private SocialClientResponse getSocialUserInfo(OAuthProvider provider, SocialLoginRequest request) {
        String token = getTokenForProvider(provider, request);
        return authenticateFromProvider(provider, token);
    }

    private SocialLoginResponse handleExistingMemberLogin(Member member, OAuthProvider provider, String oauthId) {
        log.info("기존 사용자 로그인: providerId={}, oauthId={}", provider.getValue(), oauthId);

        member.markSocialAsNotFirst(provider.getValue(), oauthId);
        boolean isFirstLogin = member.getSocialAccount(provider.getValue()).isFirst();

        return createSuccessfulLoginResponse(member, isFirstLogin);
    }

    private SocialLoginResponse handleNewSocialAccount(OAuthProvider provider, SocialClientResponse socialUserInfo, SocialLoginRequest request) {
        UserInfo userInfo = extractUserInfo(provider, socialUserInfo, request);
        validateRequiredUserInfo(userInfo, provider);

        Optional<Member> existingMember = findExistingMember(userInfo);

        if (existingMember.isPresent() && hasOtherSocialAccounts(existingMember.get())) {
            return handleConflictingSocialAccount(existingMember.get(), provider, socialUserInfo, request);
        }

        Member member = createOrUpdateMember(provider, socialUserInfo, request, existingMember);
        boolean isFirstLogin = member.getSocialAccount(provider.getValue()).isFirst();

        return createSuccessfulLoginResponse(member, isFirstLogin);
    }

    private boolean hasOtherSocialAccounts(Member member) {
        return !member.getSocialAccounts().isEmpty();
    }

    private SocialLoginResponse handleConflictingSocialAccount(Member existingMember, OAuthProvider provider, 
                                                             SocialClientResponse socialUserInfo, SocialLoginRequest request) {
        log.info("기존 회원에게 다른 소셜 계정이 존재함: memberId={}, 시도한 provider={}",
                existingMember.getId(), provider.getValue());

        UserProfile profile = createUserProfile(existingMember);
        UserInfo newAccountInfo = extractUserInfo(provider, socialUserInfo, request);
        NewSocialAccountInfo newSocialAccount = NewSocialAccountInfo.of(
                provider.getValue(), 
                newAccountInfo.email(), 
                newAccountInfo.name()
        );

        return SocialLoginResponse.ofWithNewAccount(false, null, null, profile, newSocialAccount);
    }

    private Member createOrUpdateMember(OAuthProvider provider, SocialClientResponse socialUserInfo,
                                        SocialLoginRequest request, Optional<Member> existingMember) {
        UserInfo userInfo = extractUserInfo(provider, socialUserInfo, request);

        Member member = existingMember.orElseGet(() -> {
            log.info("신규 멤버 생성: provider={}, phoneNumber={}, email={}",
                    provider.getValue(), userInfo.phoneNumber(), userInfo.email());
            return Member.createMember(MemberType.GUARDIAN, userInfo.name(), userInfo.phoneNumber());
        });

        addSocialAccountToMember(member, provider, socialUserInfo.oauthId(), userInfo.email());

        logMemberCreationOrUpdate(member, provider, userInfo.email());
        return member;
    }

    private void addSocialAccountToMember(Member member, OAuthProvider provider, String oauthId, String email) {
        SocialAccount socialAccount = SocialAccount.createSocialAccount(email, provider.getValue(), oauthId, member);
        member.getSocialAccounts().add(socialAccount);
        memberRepository.save(member);
    }

    private void logMemberCreationOrUpdate(Member member, OAuthProvider provider, String email) {
        if (member.getId() != null) {
            log.info("기존 멤버에 소셜 계정 추가: memberId={}, provider={}, email={}",
                    member.getId(), provider.getValue(), email);
        } else {
            log.info("신규 멤버 생성 완료: provider={}, email={}", provider.getValue(), email);
        }
    }


    private Optional<Member> findMemberByProvider(OAuthProvider provider, String oauthId) {
        return memberRepository.findByProviderAndOauthId(provider.getValue(), oauthId);
    }

    private Member findAppleMemberByEmail(String email) {
        return memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider(
                        email, OAuthProvider.APPLE.getValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private String getTokenForProvider(OAuthProvider provider, SocialLoginRequest request) {
        return switch (provider) {
            case APPLE -> {
                validateAppleAuthorizationCode(request.authorizationCode());
                yield request.authorizationCode();
            }
            case NAVER, KAKAO -> {
                validateAccessToken(request.accessToken());
                yield request.accessToken();
            }
        };
    }

    private void validateAppleAuthorizationCode(String authorizationCode) {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new BusinessException(ErrorCode.APPLE_AUTHORIZATION_CODE_IS_BLANK);
        }
    }

    private void validateAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_ACCESS_TOKEN_IS_BLANK);
        }
    }

    private SocialClientResponse authenticateFromProvider(OAuthProvider provider, String token) {
        OAuthClient oAuthClient = oAuthClients.get(provider);
        if (oAuthClient == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return oAuthClient.getUserInfo(token);
    }

    private Optional<Member> findExistingMember(UserInfo userInfo) {
        return findMemberByPhoneNumber(userInfo.phoneNumber())
                .or(() -> findMemberByEmail(userInfo.email()));
    }

    private Optional<Member> findMemberByPhoneNumber(String phoneNumber) {
        String normalizedPhone = PhoneNumberUtil.normalize(phoneNumber);

        if (normalizedPhone != null && !normalizedPhone.isBlank()) {
            Optional<Member> member = memberRepository.findByPhoneNumber(normalizedPhone);
            if (member.isPresent()) {
                log.info("전화번호로 기존 멤버 찾음: phoneNumber={}", normalizedPhone);
            }
            return member;
        }

        return Optional.empty();
    }

    private Optional<Member> findMemberByEmail(String email) {
        if (email != null && !email.isBlank()) {
            Optional<Member> member = memberRepository.findBySocialAccounts_Email(email);
            if (member.isPresent()) {
                log.info("이메일로 기존 멤버 찾음: email={}", email);
            }
            return member;
        }

        return Optional.empty();
    }

    private UserInfo extractUserInfo(OAuthProvider provider, SocialClientResponse socialUserInfo, SocialLoginRequest request) {
        if (provider == OAuthProvider.APPLE) {
            return extractAppleUserInfo(socialUserInfo, request);
        }

        String normalizedPhone = PhoneNumberUtil.normalize(socialUserInfo.phoneNumber());
        return UserInfo.of(socialUserInfo.name(), socialUserInfo.email(), normalizedPhone);
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
        validateEmail(userInfo.email(), provider);
        validateName(userInfo.name(), provider);
    }

    private void validateEmail(String email, OAuthProvider provider) {
        if (email == null || email.isBlank()) {
            log.error("이메일 정보가 없음: provider={}", provider.getValue());
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }
    }

    private void validateName(String name, OAuthProvider provider) {
        if (provider != OAuthProvider.APPLE && (name == null || name.isBlank())) {
            log.error("이름 정보가 없음: provider={}", provider.getValue());
            throw new BusinessException(ErrorCode.SOCIAL_NAME_NOT_PROVIDED);
        }
    }

    private SocialLoginResponse createSuccessfulLoginResponse(Member member, boolean isFirstLogin) {
        TokenPairResponse tokenPair = generateTokenPair(member);
        UserProfile profile = createUserProfile(member);

        return SocialLoginResponse.of(isFirstLogin, tokenPair.accessToken(), tokenPair.refreshToken(), profile);
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

        return UserProfile.of(member.getName(), member.getPhoneNumber(), email, providers);
    }

    private record UserInfo(String name, String email, String phoneNumber) {
        public static UserInfo of(String name, String email, String phoneNumber) {
            return new UserInfo(name, email, phoneNumber);
        }
    }
}
