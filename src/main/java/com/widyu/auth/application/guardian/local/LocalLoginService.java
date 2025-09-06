package com.widyu.auth.application.guardian.local;

import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.dto.response.UserProfile;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.domain.LocalAccount;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberType;
import com.widyu.member.domain.SocialAccount;
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

    @Transactional
    public TokenPairResponse signupGuardianWithLocal(TemporaryMember temp, String email, String rawPassword) {
        if (localAccountRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }

        Member member = Member.createMember(
                MemberType.GUARDIAN,
                temp.getName(),
                temp.getPhoneNumber()
        );

        LocalAccount local = LocalAccount.createLocalAccount(
                member,
                email,
                passwordEncoder.encode(rawPassword)
        );

        memberRepository.save(member);
        localAccountRepository.save(local);

        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }

    @Transactional(readOnly = true)
    public boolean isEmailRegistered(EmailCheckRequest request) {
        return !localAccountRepository.existsByEmail(request.email());
    }

    @Transactional(readOnly = true)
    public TokenPairResponse signIn(LocalGuardianSignInRequest request) {
        LocalAccount localAccount = findLocalAccountByEmail(request.email());
        validatePassword(request.password(), localAccount.getPassword());

        Member member = localAccount.getMember();
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }

    private LocalAccount findLocalAccountByEmail(String email) {
        return localAccountRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_EMAIL));
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
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
        member.getLocalAccount().changePassword(encodedPw);

        temporaryMemberUtil.deleteTemporaryMember(temporaryMember.getId());

        return true;
    }

    @Transactional(readOnly = true)
    public UserProfile getUserProfileByTemporaryToken(HttpServletRequest httpServletRequest) {
        TemporaryMember temporaryMember = temporaryMemberUtil.getTemporaryMemberFromRequest(httpServletRequest);
        Member member = memberRepository.findByPhoneNumberAndName(temporaryMember.getPhoneNumber(), temporaryMember.getName())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

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
