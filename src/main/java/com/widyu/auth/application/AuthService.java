package com.widyu.auth.application;

import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.dto.request.LocalGuardianSignupRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.auth.repository.VerificationCodeRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.JwtUtil;
import com.widyu.member.domain.LocalAccount;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SmsService smsService;
    private final VerificationCodeRepository verificationCodeRepository;
    private final MemberRepository memberRepository;
    private final LocalAccountRepository localAccountRepository;
    private final TemporaryMemberRepository temporaryMemberRepository;

    @Transactional
    public void sendSmsVerification(final SmsVerificationRequest request) {
        smsService.sendVerificationSms(request.phoneNumber(), request.name());
    }

    @Transactional
    public TemporaryTokenResponse verifySmsCode(final SmsCodeRequest request) {
        if (smsService.verifyCode(request.phoneNumber(), request.code())) {
            String name = verificationCodeRepository.findById(request.phoneNumber())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND))
                    .getName();
            TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember(
                    request.phoneNumber(),
                    name
            );
            clearVerificationData(request.phoneNumber());
            temporaryMemberRepository.save(temporaryMember);

            return jwtTokenProvider.generateTemporaryToken(temporaryMember);

        }
        throw new IllegalArgumentException("Invalid verification code.");

    }

    @Transactional
    public TokenPairResponse localGuardianSignup(HttpServletRequest httpServletRequest,
                                                 final LocalGuardianSignupRequest request) {
        if (localAccountRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }
        String temporaryToken = JwtUtil.extractTemporaryTokenFromHeader(httpServletRequest);
        if (temporaryToken == null) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }

        TemporaryTokenDto temporaryTokenDto = jwtTokenProvider.retrieveTemporaryToken(temporaryToken);
        if (temporaryTokenDto == null) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }

        TemporaryMember temporaryMember = temporaryMemberRepository.findById(temporaryTokenDto.temporaryMemberId())
                .orElseThrow(() -> new IllegalArgumentException("Temporary member not found."));

        Member member = Member.createMember(
                MemberType.GUARDIAN,
                temporaryMember.getName(),
                temporaryMember.getPhoneNumber()
        );

        LocalAccount localAccount = LocalAccount.createLocalAccount(
                member,
                request.email(),
                passwordEncoder.encode(request.password())
        );

        memberRepository.save(member);
        localAccountRepository.save(localAccount);

        clearTemporaryMemberData(temporaryMember.getId());
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }

    private void clearVerificationData(String phoneNumber) {
        verificationCodeRepository.deleteById(phoneNumber);
    }

    private void clearTemporaryMemberData(String temporaryMemberId) {
        temporaryMemberRepository.deleteById(temporaryMemberId);
    }
}
