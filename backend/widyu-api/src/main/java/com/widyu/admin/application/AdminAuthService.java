package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.AdminAuditLog;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.repository.LocalAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final LocalAccountRepository localAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final AdminAccessValidator adminAccessValidator;

    @Transactional
    public TokenPairResponse login(String email, String password) {
        LocalAccount localAccount = localAccountRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_EMAIL));

        if (!passwordEncoder.matches(password, localAccount.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        Member member = localAccount.getMember();
        adminAccessValidator.validateMember(member);

        TokenPairResponse tokens = jwtTokenProvider.generateTokenPair(member.getId(), member.getRole(), "local");
        adminAuditLogRepository.save(
                AdminAuditLog.of(member.getId(), member.getName(), AdminAction.ADMIN_LOGIN, null, null, null)
        );
        return tokens;
    }

    @Transactional
    public TokenPairResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "리프레시 토큰이 없습니다.");
        }
        RefreshTokenDto refreshTokenDto = jwtTokenProvider.retrieveRefreshToken(refreshToken);
        adminAccessValidator.validateMemberId(refreshTokenDto.memberId());
        return jwtTokenProvider.generateTokenPair(refreshTokenDto.memberId(), MemberRole.ADMIN, "local");
    }
}
