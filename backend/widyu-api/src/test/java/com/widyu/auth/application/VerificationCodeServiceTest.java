package com.widyu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceTest {
    @Mock private AuthLimitStore authLimitStore;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private TemporaryMemberRepository temporaryMemberRepository;
    @InjectMocks private VerificationCodeService service;

    @Test
    @DisplayName("코드를 소비하면 저장된 이름으로 임시 회원과 토큰을 생성한다")
    void 코드를_소비하면_저장된_이름으로_임시회원과_토큰을_생성한다() {
        // given
        given(authLimitStore.consumeCode("01000000000", "123456")).willReturn("합성 사용자");
        TemporaryMember member = TemporaryMember.createTemporaryMember("합성 사용자", "01000000000");
        given(temporaryMemberRepository.save(any())).willReturn(member);
        TemporaryTokenResponse expected = new TemporaryTokenResponse("synthetic-token");
        given(jwtTokenProvider.generateTemporaryToken(member)).willReturn(expected);
        // when
        TemporaryTokenResponse result = service.verifyAndIssueTemporaryToken("01000000000", "123456");
        // then
        assertThat(result).isEqualTo(expected);
        verify(temporaryMemberRepository).save(org.mockito.ArgumentMatchers.argThat(
                value -> value.getName().equals("합성 사용자") && value.getPhoneNumber().equals("01000000000")));
    }

    @Test
    @DisplayName("코드 소비가 거부되면 임시 회원과 토큰을 생성하지 않는다")
    void 코드_소비가_거부되면_임시회원과_토큰을_생성하지_않는다() {
        // given
        given(authLimitStore.consumeCode("01000000000", "000000"))
                .willThrow(new BusinessException(ErrorCode.SMS_VERIFICATION_CODE_MISMATCH));
        // when / then
        assertThatThrownBy(() -> service.verifyAndIssueTemporaryToken("01000000000", "000000"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(temporaryMemberRepository, jwtTokenProvider);
    }

    @Test
    @DisplayName("소비 후 저장이 실패하면 원인 민감값 없는 503 예외를 반환한다")
    void 소비_후_저장이_실패하면_민감값_없는_예외를_반환한다() {
        // given
        given(authLimitStore.consumeCode("01000000000", "123456")).willReturn("합성 사용자");
        given(temporaryMemberRepository.save(any())).willThrow(new IllegalStateException("synthetic-secret"));
        // when / then
        assertThatThrownBy(() -> service.verifyAndIssueTemporaryToken("01000000000", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE)
                .hasNoCause().hasMessageNotContaining("synthetic-secret");
        verifyNoInteractions(jwtTokenProvider);
    }
}
