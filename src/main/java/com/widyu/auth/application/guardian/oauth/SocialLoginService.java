package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.application.guardian.oauth.strategy.UserInfo;
import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.request.AppleSignUpRequest;
import com.widyu.auth.dto.request.SocialIntegrationRequest;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.auth.dto.response.NewSocialAccountInfo;
import com.widyu.auth.dto.response.SocialClientResponse;
import com.widyu.auth.dto.response.SocialLoginResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.MemberUtil;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberRole;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.SocialAccount;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SocialLoginService {

    private final SocialLoginStrategyFactory strategyFactory;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberUtil temporaryMemberUtil;
    private final MemberUtil memberUtil;

    public SocialLoginResponse socialLogin(String providerName, SocialLoginRequest request) {
        log.info("소셜 로그인 시도: provider={}", providerName);

        SocialLoginStrategy strategy = strategyFactory.getStrategy(providerName);
        OAuthProvider provider = strategy.getSupportedProvider();

        // 1. 요청 검증
        strategy.validateLoginRequest(request);

        // 2. 소셜 제공자에서 사용자 정보 획득
        SocialClientResponse socialResponse = strategy.getUserInfo(request);

        // 3. 사용자 정보 후처리
        UserInfo userInfo = strategy.processUserInfo(socialResponse, request);

        // 4. 사용자 정보 검증
        strategy.validateUserInfo(userInfo);

        // 5. 기존 회원 확인
        Optional<Member> existingMember = findMemberByProvider(provider, socialResponse.oauthId());

        return existingMember
                .map(member -> handleExistingMemberLogin(member, provider, socialResponse.oauthId()))
                .orElseGet(() -> handleNewSocialAccount(provider, socialResponse, userInfo, socialResponse.oauthId()));
    }

    public void updatePhoneNumberIfAppleSignUp(AppleSignUpRequest request, HttpServletRequest httpServletRequest) {
        Member member = findAppleMemberByEmail(request.email());
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);

        member.updatePhoneNumber(temporaryMember.getPhoneNumber());
        log.info("애플 사용자 전화번호 업데이트 완료: memberId={}, email={}", member.getId(), request.email());
    }

    public TokenPairResponse integrateSocialAccount(SocialIntegrationRequest request) {
        // 현재 인증된 사용자 조회 (기존 회원)
        Member member = memberRepository.findByPhoneNumberAndName(request.phoneNumber(), request.name())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 요청한 제공자 검증
        OAuthProvider provider = OAuthProvider.from(request.provider());

        // 현재 사용자가 이미 해당 제공자 계정을 가지고 있는지 확인
        boolean alreadyLinked = member.getSocialAccounts().stream()
                .anyMatch(account -> account.getProvider().equals(provider.getValue()));

        if (alreadyLinked) {
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
        }

        // 다른 회원이 이미 해당 소셜 계정(새로운 소셜 로그인 정보)을 사용하고 있는지 확인
        Optional<Member> conflictMember = memberRepository.findBySocialAccounts_EmailAndSocialAccounts_Provider(
                request.email(), provider.getValue());

        if (conflictMember.isPresent() && !conflictMember.get().getId().equals(member.getId())) {
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        }

        // 새로운 소셜 계정을 현재 사용자에게 연동
        addSocialAccountToMember(member, provider, request.oauthId(), request.email());

        log.info("소셜 계정 연동 완료: memberId={}, provider={}, email={}",
                member.getId(), provider.getValue(), request.email());

        // 토큰 생성 후 반환
        return generateTokenPair(member);
    }

    private SocialLoginResponse handleExistingMemberLogin(Member member, OAuthProvider provider, String oauthId) {
        log.info("기존 회원 로그인: providerId={}, oauthId={}, memberId={}",
                provider.getValue(), oauthId, member.getId());

        member.markSocialAsNotFirst(provider.getValue(), oauthId);
        boolean isFirstLogin = member.getSocialAccount(provider.getValue()).isFirst();

        return createSuccessfulLoginResponse(member, isFirstLogin);
    }

    private SocialLoginResponse handleNewSocialAccount(OAuthProvider provider,
                                                       SocialClientResponse socialResponse,
                                                       UserInfo userInfo,
                                                       String oauthId) {
        Optional<Member> existingMember = findExistingMemberByUserInfo(userInfo);

        if (existingMember.isPresent() && hasOtherSocialAccounts(existingMember.get())) {
            return handleConflictingSocialAccount(existingMember.get(), provider, userInfo, oauthId);
        }

        Member member = createOrUpdateMember(provider, socialResponse, userInfo, existingMember);
        boolean isFirstLogin = member.getSocialAccount(provider.getValue()).isFirst();

        return createSuccessfulLoginResponse(member, isFirstLogin);
    }

    private boolean hasOtherSocialAccounts(Member member) {
        return !member.getSocialAccounts().isEmpty();
    }

    private SocialLoginResponse handleConflictingSocialAccount(Member existingMember,
                                                               OAuthProvider provider,
                                                               UserInfo userInfo,
                                                               String oauthId) {
        log.info("다른 소셜 계정을 가진 기존 회원 발견: memberId={}, 시도한 제공자={}",
                existingMember.getId(), provider.getValue());

        UserProfile profile = createUserProfile(existingMember);
        NewSocialAccountInfo newSocialAccount = NewSocialAccountInfo.of(
                provider.getValue(),
                userInfo.email(),
                userInfo.name(),
                oauthId
        );

        return SocialLoginResponse.ofWithNewAccount(false, null, null, profile, newSocialAccount);
    }

    private Member createOrUpdateMember(OAuthProvider provider,
                                        SocialClientResponse socialResponse,
                                        UserInfo userInfo,
                                        Optional<Member> existingMember) {
        Member member = existingMember.orElseGet(() -> {
            log.info("신규 회원 생성: provider={}, phoneNumber={}, email={}",
                    provider.getValue(), userInfo.phoneNumber(), userInfo.email());
            return Member.createMember(MemberType.GUARDIAN, userInfo.name(), userInfo.phoneNumber());
        });

        addSocialAccountToMember(member, provider, socialResponse.oauthId(), userInfo.email());
        logMemberCreationOrUpdate(member, provider, userInfo.email());

        return member;
    }

    private void addSocialAccountToMember(Member member, OAuthProvider provider, String oauthId, String email) {
        SocialAccount socialAccount = SocialAccount.createSocialAccount(email, provider.getValue(), oauthId, member);
        member.getSocialAccounts().add(socialAccount);
    }

    private void logMemberCreationOrUpdate(Member member, OAuthProvider provider, String email) {
        if (member.getId() != null) {
            log.info("기존 회원에 소셜 계정 추가: memberId={}, provider={}, email={}",
                    member.getId(), provider.getValue(), email);
        } else {
            log.info("신규 회원 생성 완료: provider={}, email={}", provider.getValue(), email);
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

    private Optional<Member> findExistingMemberByUserInfo(UserInfo userInfo) {
        if (userInfo.hasPhoneNumber()) {
            Optional<Member> member = memberRepository.findByPhoneNumber(userInfo.phoneNumber());
            if (member.isPresent()) {
                log.info("전화번호로 기존 회원 발견: phoneNumber={}", userInfo.phoneNumber());
                return member;
            }
        }

        if (userInfo.hasEmail()) {
            Optional<Member> member = memberRepository.findBySocialAccounts_Email(userInfo.email());
            if (member.isPresent()) {
                log.info("이메일로 기존 회원 발견: email={}", userInfo.email());
                return member;
            }
        }

        return Optional.empty();
    }

    private SocialLoginResponse createSuccessfulLoginResponse(Member member, boolean isFirstLogin) {
        TokenPairResponse tokenPair = generateTokenPair(member);
        UserProfile profile = createUserProfile(member);

        log.info("소셜 로그인 성공: memberId={}, 최초로그인={}", member.getId(), isFirstLogin);
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
}
