package com.widyu.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.admin.AdminAccessLog;
import com.widyu.admin.dto.response.AdminAccessLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.admin.repository.AdminAccessLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@DisplayName("관리자 접속기록 서비스 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AdminAccessLogServiceTest {

    @Mock
    private AdminAccessLogRepository accessLogRepository;

    @Mock
    private AdminIdentityResolver adminIdentityResolver;

    @InjectMocks
    private AdminAccessLogService adminAccessLogService;

    @Test
    @DisplayName("인증된 관리자의 요청을 기록하면 관리자 ID와 이름을 함께 저장한다")
    void 인증된_관리자의_요청은_관리자_ID와_이름을_함께_저장한다() {
        // given
        LocalDateTime accessedAt = LocalDateTime.of(2026, 9, 21, 10, 0);
        given(adminIdentityResolver.resolveAdminId()).willReturn(7L);
        given(adminIdentityResolver.resolveAdminName(7L)).willReturn("관리자");

        // when
        adminAccessLogService.record("GET", "/api/v1/admin/members/42", "detail=true", 42L, null,
                200, "10.0.0.7", "widyu-admin/1.0", accessedAt);

        // then
        ArgumentCaptor<AdminAccessLog> saved = ArgumentCaptor.forClass(AdminAccessLog.class);
        then(accessLogRepository).should().save(saved.capture());
        AdminAccessLog log = saved.getValue();
        assertThat(log.getAdminId()).isEqualTo(7L);
        assertThat(log.getAdminName()).isEqualTo("관리자");
        assertThat(log.getMethod()).isEqualTo("GET");
        assertThat(log.getPath()).isEqualTo("/api/v1/admin/members/42");
        assertThat(log.getTargetMemberId()).isEqualTo(42L);
        assertThat(log.getStatus()).isEqualTo(200);
        assertThat(log.getAccessedAt()).isEqualTo(accessedAt);
    }

    @Test
    @DisplayName("인증 전 요청을 기록하면 관리자 ID를 -1로 저장한다")
    void 인증_전_요청은_관리자_ID를_음수_1로_저장한다() {
        // given
        given(adminIdentityResolver.resolveAdminId()).willReturn(-1L);

        // when
        adminAccessLogService.record("POST", "/api/v1/auth/admin/login", null, null, null,
                200, "10.0.0.9", null, LocalDateTime.of(2026, 9, 21, 10, 0));

        // then
        ArgumentCaptor<AdminAccessLog> saved = ArgumentCaptor.forClass(AdminAccessLog.class);
        then(accessLogRepository).should().save(saved.capture());
        assertThat(saved.getValue().getAdminId()).isEqualTo(-1L);
        assertThat(saved.getValue().getAdminName()).isEqualTo("인증 전");
        then(adminIdentityResolver).should(never()).resolveAdminName(any());
    }

    @Test
    @DisplayName("500자를 넘는 쿼리 문자열을 기록하면 500자로 잘라 저장한다")
    void 긴_쿼리_문자열은_잘라서_저장한다() {
        // given
        String longQuery = "a".repeat(700);
        given(adminIdentityResolver.resolveAdminId()).willReturn(7L);
        given(adminIdentityResolver.resolveAdminName(7L)).willReturn("관리자");

        // when
        adminAccessLogService.record("GET", "/api/v1/admin/members", longQuery, null, null,
                200, "10.0.0.7", "a".repeat(300), LocalDateTime.of(2026, 9, 21, 10, 0));

        // then
        ArgumentCaptor<AdminAccessLog> saved = ArgumentCaptor.forClass(AdminAccessLog.class);
        then(accessLogRepository).should().save(saved.capture());
        assertThat(saved.getValue().getQuery()).hasSize(500);
        assertThat(saved.getValue().getUserAgent()).hasSize(200);
    }

    @Test
    @DisplayName("관리자 ID와 기간으로 조회하면 최근순 페이지로 반환한다")
    void 관리자_ID와_기간으로_조회하면_최근순_페이지를_반환한다() {
        // given
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 21, 0, 0);
        AdminAccessLog log = AdminAccessLog.of(7L, "관리자", "GET", "/api/v1/admin/members/42", null,
                42L, null, 200, "10.0.0.7", "widyu-admin/1.0", from);
        given(accessLogRepository.search(eq(7L), eq(from), eq(to), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 30), 1));

        // when
        AdminPageResponse<AdminAccessLogResponse> result = adminAccessLogService.search(7L, from, to, 0, 30);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(accessLogRepository).should().search(eq(7L), eq(from), eq(to), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).adminId()).isEqualTo(7L);
        assertThat(result.content().get(0).targetMemberId()).isEqualTo(42L);
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
