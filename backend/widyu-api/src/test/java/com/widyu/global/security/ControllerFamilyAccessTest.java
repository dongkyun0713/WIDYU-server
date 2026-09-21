package com.widyu.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.global.annotation.ValidateFamilyAccess;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("컨트롤러 가족 접근 검증 회귀 테스트")
class ControllerFamilyAccessTest {

    private static final String CONTROLLER_PACKAGE = "com.widyu";

    /**
     * {@code memberId}를 받지만 {@link ValidateFamilyAccess} 없이도 되는 핸들러.
     *
     * <p>이 프로젝트의 인가 검증 경로는 두 갈래다 — AOP 애노테이션, 그리고 서비스 계층의 직접 검증.
     * 어느 쪽도 거치지 않는 엔드포인트가 생기면 조용히 IDOR이 된다(#489 리뷰에서 GoalHomeController가
     * 실제로 그랬다). 새 핸들러는 애노테이션을 붙이거나, 서비스에서 검증한다는 근거와 함께 아래에 등록한다.
     */
    private static final Set<String> SERVICE_LAYER_VALIDATED = Set.of(
            // SecurityConfig:68 — /api/v1/admin/** 은 hasRole("ADMIN")으로 차단된다
            "AdminMemberController#getMemberDetail",
            "AdminMemberController#changeStatus",
            "AdminConsentController#getConsentHistory",

            // HealthScheduleService:184,225 — existsByGuardianIdAndSeniorProfileId
            "HealthScheduleController#getHealthScheduleCalendarForSenior",
            "HealthScheduleController#getHealthSchedulesByDateForSenior",

            // RealtimeLocationService:190,262 — existsByGuardianIdAndSeniorProfileId
            "RealtimeLocationRestController#getLastLocation",
            "RealtimeLocationRestController#getLocationTrail",

            // ParentLocationService:86 — familyAccessService.verifyFamilyAccess (AOP와 같은 검증기)
            "ParentLocationController#deleteParentLocation",

            // GuardianMyPageService:330 getSeniorProfileWithAccessCheck
            "GuardianMyPageController#getSeniorProfile",
            "GuardianMyPageController#updateSeniorName",
            "GuardianMyPageController#updateSeniorPhone",
            "GuardianMyPageController#updateSeniorAddress",
            "GuardianMyPageController#updateSeniorProfileImage",
            "GuardianMyPageController#updateSeniorInviteCode",

            // GuardianMyPageService:248,270 — 방장 권한 + 대상이 같은 가족인지 확인
            "GuardianMyPageController#changeLeader",
            "GuardianMyPageController#deleteFamilyMember",

            // SeniorMyPageService:103 — 대상이 본인 가족의 보호자인지 확인
            "SeniorMyPageController#updateRepresentativeContact"
    );

    @Test
    @DisplayName("memberId를 받는 핸들러는 AOP 검증을 붙이거나 서비스 계층 검증 목록에 등록해야 한다")
    void memberId를_받는_핸들러는_가족_접근_검증을_거친다() {
        List<String> unprotected = new ArrayList<>();

        for (Class<?> controller : scanControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isHandler(method) || !takesMemberId(method)) {
                    continue;
                }
                if (method.isAnnotationPresent(ValidateFamilyAccess.class)) {
                    continue;
                }
                String key = controller.getSimpleName() + "#" + method.getName();
                if (SERVICE_LAYER_VALIDATED.contains(key)) {
                    continue;
                }
                unprotected.add(key);
            }
        }

        assertThat(unprotected)
                .withFailMessage(
                        "가족 접근 검증을 거치지 않는 핸들러가 있습니다: %s%n"
                                + "@ValidateFamilyAccess를 붙이거나, 서비스 계층에서 검증한다면 "
                                + "SERVICE_LAYER_VALIDATED에 검증 위치와 함께 등록하세요.",
                        unprotected)
                .isEmpty();
    }

    private List<Class<?>> scanControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                controllers.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(controllers).isNotEmpty();
        return controllers;
    }

    private boolean isHandler(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
                || method.getAnnotations().length > 0 && hasMappingMeta(method);
    }

    private boolean hasMappingMeta(Method method) {
        return java.util.Arrays.stream(method.getAnnotations())
                .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class));
    }

    private boolean takesMemberId(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.getName().equals("memberId") || parameter.getName().equals("seniorId")) {
                return true;
            }
        }
        return false;
    }
}
