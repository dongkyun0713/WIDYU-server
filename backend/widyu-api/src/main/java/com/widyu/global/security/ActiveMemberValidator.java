package com.widyu.global.security;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰의 회원이 지금도 활성인지 요청마다 확인한다. 탈퇴·정지 뒤 남은 토큰을 막는다. → LLD-0060
 */
@Component
@RequiredArgsConstructor
public class ActiveMemberValidator {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public boolean isActive(Long memberId) {
        return memberRepository.existsByIdAndStatus(memberId, Status.ACTIVE);
    }

    public void validate(Long memberId) {
        if (!isActive(memberId)) {
            throw new BusinessException(ErrorCode.INACTIVE_MEMBER);
        }
    }
}
