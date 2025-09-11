package com.widyu.domain.auth.application;

import com.widyu.domain.auth.entity.TemporaryMember;
import com.widyu.domain.auth.dto.response.TemporaryTokenResponse;
import com.widyu.domain.auth.repository.TemporaryMemberRepository;
import com.widyu.domain.auth.repository.VerificationCodeRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {

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
        if (!verifyCode(phoneNumber, code)) {
            throw new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_MISMATCH);
        }
    }

    private boolean verifyCode(final String phoneNumber, final String inputCode) {
        return verificationCodeRepository.findById(phoneNumber)
                .map(verification -> verification.getCode().equals(inputCode))
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND, "인증 코드가 존재하지 않습니다."));
    }

    private TemporaryMember createTemporaryMember(String phoneNumber) {
        String name = verificationCodeRepository.findById(phoneNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_NOT_FOUND))
                .getName();

        TemporaryMember temp = TemporaryMember.createTemporaryMember(name, phoneNumber);
        return temporaryMemberRepository.save(temp);
    }

    private void cleanupVerificationData(String phoneNumber) {
        verificationCodeRepository.deleteById(phoneNumber);
    }
}
