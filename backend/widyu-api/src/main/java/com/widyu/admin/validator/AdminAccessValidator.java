package com.widyu.admin.validator;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AdminAccessValidator {

    private final MemberRepository memberRepository;

    @Transactional(readOnly = true)
    public void validateMemberId(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        validateMember(member);
    }

    public void validateMember(Member member) {
        if (member.getRole() != MemberRole.ADMIN || member.getStatus() != Status.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
