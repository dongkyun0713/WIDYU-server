package com.widyu.global.util;

import com.widyu.auth.dto.TemporaryTokenDto;
import com.widyu.auth.TemporaryMember;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.MemberRole;
import com.widyu.global.security.MemberSessionService;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TemporaryMemberUtil {

    private final JwtUtil jwtUtil;
    private final TemporaryMemberRepository temporaryMemberRepository;
    private final MemberSessionService memberSessionService;

    @Transactional(readOnly = true)
    public TemporaryMember getTemporaryMemberFromRequest(HttpServletRequest request) {
        String token = JwtUtil.extractTemporaryTokenFromHeader(request);
        if (token == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        TemporaryTokenDto dto;
        try {
            dto = jwtUtil.parseTemporaryToken(token);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TEMPORARY_TOKEN_EXPIRED);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // 역할 확인(임시 토큰만 허용)
        if (dto == null || dto.memberRole() == null || !(dto.memberRole() == MemberRole.TEMPORARY)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String tempId = dto.temporaryMemberId();
        TemporaryMember temporary = temporaryMemberRepository.findById(tempId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (temporary.getMemberId() != null
                && !memberSessionService.isCurrent(temporary.getMemberId(), temporary.getAuthVersion())) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }
        return temporary;
    }

    @Transactional(readOnly = true)
    public void deleteTemporaryMember(String temporaryMemberId) {
        temporaryMemberRepository.deleteById(temporaryMemberId);
    }
}
