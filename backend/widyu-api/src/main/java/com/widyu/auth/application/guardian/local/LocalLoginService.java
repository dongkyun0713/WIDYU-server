package com.widyu.auth.application.guardian.local;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.infrastructure.ClientIpResolver;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.LocalSignupResponse;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.SignUpUserInfo;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.MemberSessionService;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SocialAccount;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocalLoginService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final LocalAccountRepository localAccountRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberUtil temporaryMemberUtil;
    private final AuthLimitStore authLimitStore;
    private final ClientIpResolver clientIpResolver;

    // Valid BCrypt hash used only to equalize the password work for unknown accounts.
    private static final String DUMMY_PASSWORD = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private final MemberSessionService memberSessionService;

    @Transactional
    public LocalSignupResponse signupGuardianWithLocal(TemporaryMember temp, String email, String rawPassword) {
        if (localAccountRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }

        Member member;
        if (temp.getMemberId() != null) {
            // 기존 회원의 본인확인은 식별자를 고정한다. 탈퇴/삭제 후 신규 가입으로 우회하지 않는다.
            member = memberSessionService.validateTemporaryMember(temp, temp.getMemberId());
        } else {
            member = memberRepository.findByPhoneNumberAndName(temp.getPhoneNumber(), temp.getName())
                .map(existing -> memberSessionService.validateTemporaryMember(temp, existing.getId()))
                .orElseGet(() -> {
                    // 기존 멤버가 없으면 새로 생성
                    Member newMember = Member.createMember(
                            MemberType.GUARDIAN,
                            temp.getName(),
                            temp.getPhoneNumber()
                    );
                    return memberRepository.save(newMember);
                });
        }

        member.requireActive();

        // 로컬 계정 생성 및 멤버와 연결
        LocalAccount local = LocalAccount.createLocalAccount(
                member,
                email,
                passwordEncoder.encode(rawPassword)
        );

        localAccountRepository.save(local);

        if (temp.getMemberId() != null) {
            // Redis 삭제 전에 선조회한 다른 요청도 이전 버전으로 재사용할 수 없다.
            member = memberSessionService.revoke(member.getId());
        }

        // 토큰 생성
        TokenPairResponse tokenPair = jwtTokenProvider.generateTokenPair(member.getId(), member.getRole(), "local");
        
        // 사용자 프로필 생성
        SignUpUserInfo profile = SignUpUserInfo.of(member.getName(), member.getPhoneNumber(), email);

        return LocalSignupResponse.ofTokenPair(tokenPair, profile);
    }

    @Transactional(readOnly = true)
    public boolean isEmailRegistered(EmailCheckRequest request) {
        return !localAccountRepository.existsByEmail(request.email());
    }

    @Transactional
    public TokenPairResponse signIn(LocalGuardianSignInRequest request) {
        if (request.email() == null || request.email().isBlank() || request.email().length() > 254
                || request.password() == null || request.password().isBlank() || request.password().length() > 256) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        String clientIp = clientIpResolver.resolve();
        LocalAccount localAccount = localAccountRepository.findByEmail(request.email()).orElse(null);
        String accountKey = "unknown:" + request.email();
        if (localAccount != null) {
            // Use the persisted identity, so DB collation aliases share the same limit.
            accountKey = "id:" + localAccount.getId();
        }
        AuthLimitStore.LoginAttempt attempt = authLimitStore.reserveLogin(accountKey, clientIp);
        if (localAccount == null) {
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD);
            authLimitStore.completeLogin(attempt, false);
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        Member member = memberSessionService.lock(localAccount.getMember().getId());
        member.requireActive();
        localAccount = memberSessionService.lockLocalAccount(member.getId());
        boolean matches = passwordEncoder.matches(request.password(), localAccount.getPassword());
        authLimitStore.completeLogin(attempt, matches);
        if (!matches) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole(), "local");
    }

    public MemberInfoResponse findMemberByPhoneNumberAndName(SmsVerificationRequest request) {
        Member member = memberRepository.findByPhoneNumberAndName(request.phoneNumber(), request.name())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        return MemberInfoResponse.from(member);
    }

    @Transactional
    public boolean changePassword(ChangePasswordRequest request, HttpServletRequest httpServletRequest) {
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);

        String phoneNumber = temporaryMember.getPhoneNumber();
        String name = temporaryMember.getName();

        Member member = memberRepository.findByPhoneNumberAndName(phoneNumber, name)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        String encodedPw = passwordEncoder.encode(request.password());
        memberSessionService.validateTemporaryMember(temporaryMember, member.getId());
        member = memberSessionService.revoke(member.getId());
        member.requireActive();
        memberSessionService.lockLocalAccount(member.getId()).changePassword(encodedPw);

        temporaryMemberUtil.deleteTemporaryMember(temporaryMember.getId());

        return true;
    }

    @Transactional(readOnly = true)
    public UserProfile getUserProfileByTemporaryToken(HttpServletRequest httpServletRequest) {
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);
        Member member = memberRepository.findByPhoneNumberAndName(temporaryMember.getPhoneNumber(), temporaryMember.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (!member.getId().equals(temporaryMember.getMemberId())
                || !memberSessionService.isCurrent(member.getId(), temporaryMember.getAuthVersion())) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }

        String email = member.getSocialAccounts().stream()
                .map(SocialAccount::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);

        List<String> providers = member.getSocialAccounts().stream()
                .map(SocialAccount::getProvider)
                .toList();


        return UserProfile.of(member.getName(), member.getPhoneNumber(), email, providers);
    }
}
