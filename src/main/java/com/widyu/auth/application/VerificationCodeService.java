package com.widyu.auth.application;

import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.auth.repository.VerificationCodeRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private final SmsService smsService;
    private final VerificationCodeRepository verificationCodeRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final TemporaryMemberRepository temporaryMemberRepository;


    public TemporaryTokenResponse verifyAndIssueTemporaryToken(String phoneNumber, String code) {
        validateVerificationCode(phoneNumber, code);

        TemporaryMember temp = createTemporaryMember(phoneNumber);

        cleanupVerificationData(phoneNumber);

        return jwtTokenProvider.generateTemporaryToken(temp);
    }

    private void validateVerificationCode(String phoneNumber, String code) {
        if (!smsService.verifyCode(phoneNumber, code)) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_MISMATCH);
        }
    }

    private TemporaryMember createTemporaryMember(String phoneNumber) {
        String name = verificationCodeRepository.findById(phoneNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND))
                .getName();

        TemporaryMember temp = TemporaryMember.createTemporaryMember(phoneNumber, name);
        return temporaryMemberRepository.save(temp);
    }

    private void cleanupVerificationData(String phoneNumber) {
        verificationCodeRepository.deleteById(phoneNumber);
    }
}
