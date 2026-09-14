package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.AdminAuditLog;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.infrastructure.ClientIpResolver;
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
    private final AuthLimitStore authLimitStore;
    private final ClientIpResolver clientIpResolver;
    private static final String DUMMY_PASSWORD = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Transactional
    public TokenPairResponse login(String email, String password) {
        if (email == null || email.isBlank() || email.length() > 254) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL);
        }
        if (password == null || password.isBlank() || password.length() > 256) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        String clientIp = clientIpResolver.resolve();
        LocalAccount localAccount = localAccountRepository.findByEmail(email).orElse(null);
        String accountKey = "unknown:" + email;
        String encodedPassword = DUMMY_PASSWORD;
        if (localAccount != null) {
            accountKey = "id:" + localAccount.getId();
            encodedPassword = localAccount.getPassword();
        }
        AuthLimitStore.LoginAttempt attempt = authLimitStore.reserveLogin(accountKey, clientIp);
        boolean matches = passwordEncoder.matches(password, encodedPassword);
        if (localAccount == null) {
            authLimitStore.completeLogin(attempt, false);
            throw new BusinessException(ErrorCode.INVALID_EMAIL);
        }

        if (!matches) {
            authLimitStore.completeLogin(attempt, false);
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }

        Member member = localAccount.getMember();
        if (member.getRole() != MemberRole.ADMIN) {
            authLimitStore.completeLogin(attempt, false);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        authLimitStore.completeLogin(attempt, true);
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
        return jwtTokenProvider.generateTokenPair(refreshTokenDto.memberId(), MemberRole.ADMIN, "local");
    }
}
