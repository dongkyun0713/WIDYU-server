package com.widyu.admin.application;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 현재 요청을 수행하는 관리자의 식별자·이름을 해석한다. 감사 로그와 접속기록이 함께 쓴다.
 */
@Component
@RequiredArgsConstructor
public class AdminIdentityResolver {

    public static final Long UNAUTHENTICATED_ADMIN_ID = -1L;

    private final MemberRepository memberRepository;

    public Long resolveAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PrincipalDetails principal) {
            return principal.getMemberId();
        }
        return UNAUTHENTICATED_ADMIN_ID;
    }

    public String resolveAdminName(Long adminId) {
        if (adminId == null || adminId < 0) {
            return "시스템";
        }
        return memberRepository.findById(adminId)
                .map(Member::getName)
                .orElse("알 수 없음");
    }
}
