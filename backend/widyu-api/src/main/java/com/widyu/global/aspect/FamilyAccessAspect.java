package com.widyu.global.aspect;

import com.widyu.global.annotation.ValidateFamilyAccess;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;


@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class FamilyAccessAspect {

    private final MemberUtil memberUtil;
    private final FamilyAccessService familyAccessService;

    @Before("@annotation(validateFamilyAccess)")
    public void validateFamilyAccess(JoinPoint joinPoint, ValidateFamilyAccess validateFamilyAccess) {
        Member currentMember = memberUtil.getCurrentMember();
        String memberIdParamName = validateFamilyAccess.memberIdParam();

        Long targetMemberId = extractMemberId(joinPoint, memberIdParamName);

        if (targetMemberId == null || targetMemberId.equals(currentMember.getId())) {
            return;
        }

        if (currentMember.getType() != MemberType.GUARDIAN) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "보호자만 다른 사용자의 리소스에 접근할 수 있습니다.");
        }

        familyAccessService.verifyFamilyAccess(currentMember.getId(), targetMemberId);

        log.debug("가족 관계 검증 성공: guardianId={}, seniorId={}",
                currentMember.getId(), targetMemberId);
    }

    private Long extractMemberId(JoinPoint joinPoint, String paramName) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(paramName)) {
                Object value = args[i];
                if (value instanceof Long) {
                    return (Long) value;
                }
                return null;
            }
        }

        throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                String.format("파라미터 '%s'를 찾을 수 없습니다.", paramName)
        );
    }
}
