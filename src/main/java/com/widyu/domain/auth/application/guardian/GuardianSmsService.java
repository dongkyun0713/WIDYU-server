package com.widyu.domain.auth.application.guardian;

import com.widyu.domain.auth.application.SmsService;
import com.widyu.domain.auth.application.VerificationCodeService;
import com.widyu.domain.auth.dto.request.FindPasswordRequest;
import com.widyu.domain.auth.dto.request.SmsCodeRequest;
import com.widyu.domain.auth.dto.request.SmsVerificationRequest;
import com.widyu.domain.auth.dto.response.TemporaryTokenResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보호자 SMS 인증 전용 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianSmsService {

    private final SmsService smsService;
    private final VerificationCodeService verificationCodeService;
    private final MemberRepository memberRepository;

    /**
     * SMS 인증 요청 발송
     */
    @Transactional
    public void sendVerificationSms(SmsVerificationRequest request) {
        log.info("SMS 인증 요청: phoneNumber={}, name={}", request.phoneNumber(), request.name());
        smsService.sendVerificationSms(request.phoneNumber(), request.name());
    }

    /**
     * 비밀번호 찾기를 위한 SMS 인증 발송 (기존 회원 확인 후)
     */
    @Transactional
    public void sendVerificationSmsForPasswordReset(FindPasswordRequest request) {
        log.info("비밀번호 찾기 SMS 인증 요청: phoneNumber={}, email={}", 
                request.phoneNumber(), request.email());
        
        // 회원 존재 여부 확인
        memberRepository.findByPhoneNumberAndNameAndLocalAccount_Email(
                request.phoneNumber(), 
                request.name(),
                request.email()
        ).orElseThrow(() -> {
            log.error("비밀번호 찾기 대상 회원을 찾을 수 없음: phoneNumber={}, email={}", 
                    request.phoneNumber(), request.email());
            return new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        });
        
        smsService.sendVerificationSms(request.phoneNumber(), request.name());
    }

    /**
     * SMS 인증 코드 검증 및 임시 토큰 발급
     */
    @Transactional
    public TemporaryTokenResponse verifyCodeAndIssueToken(SmsCodeRequest request) {
        log.info("SMS 인증 코드 검증: phoneNumber={}", request.phoneNumber());
        return verificationCodeService.verifyAndIssueTemporaryToken(request.phoneNumber(), request.code());
    }
}
