package com.widyu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.auth.dto.SocialTemporaryTokenDto;
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
@DisplayName("SocialTemporaryTokenService 단위 테스트")
class SocialTemporaryTokenServiceTest {

    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private com.widyu.global.security.MemberSessionService memberSessionService;

    @InjectMocks
    private SocialTemporaryTokenService socialTemporaryTokenService;

    @Test
    @DisplayName("소셜 임시 토큰 생성 시 jwtTokenProvider가 발급한 토큰이 반환된다")
    void 소셜_임시_토큰_생성_시_토큰이_반환된다() {
        // given
        given(jwtTokenProvider.generateSocialTemporaryToken(1L, "kakao", "oauth123", "test@kakao.com"))
                .willReturn("social-temp-token");

        // when
        String token = socialTemporaryTokenService.createSocialTemporaryToken(1L, "kakao", "oauth123", "test@kakao.com");

        // then
        assertThat(token).isEqualTo("social-temp-token");
    }

    @Test
    @DisplayName("유효한 소셜 임시 토큰 검증 시 토큰 DTO가 반환된다")
    void 유효한_토큰_검증_시_DTO가_반환된다() {
        // given
        SocialTemporaryTokenDto dto = new SocialTemporaryTokenDto(1L, "kakao", "oauth123", "test@kakao.com");
        given(jwtTokenProvider.retrieveSocialTemporaryToken("valid-token")).willReturn(dto);

        // when
        SocialTemporaryTokenDto result = socialTemporaryTokenService.validateAndRetrieve("valid-token");

        // then
        assertThat(result.memberId()).isEqualTo(1L);
        assertThat(result.provider()).isEqualTo("kakao");
    }

    @Test
    @DisplayName("null 토큰으로 검증 시 BusinessException을 던진다")
    void null_토큰으로_검증_시_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> socialTemporaryTokenService.validateAndRetrieve(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TEMPORARY_TOKEN);
    }

    @Test
    @DisplayName("빈 문자열 토큰으로 검증 시 BusinessException을 던진다")
    void 빈_문자열_토큰으로_검증_시_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> socialTemporaryTokenService.validateAndRetrieve("   "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TEMPORARY_TOKEN);
    }

    @Test
    @DisplayName("소셜 임시 토큰을 소비하면 회원의 이전 버전을 폐기한다")
    void 소셜_임시_토큰_소비는_이전_버전을_폐기한다() {
        // given
        given(jwtTokenProvider.retrieveSocialTemporaryToken("any-token"))
                .willReturn(new SocialTemporaryTokenDto(1L, "kakao", "oauth", "test@example.com"));
        // when
        socialTemporaryTokenService.deleteSocialTemporaryToken("any-token");
        // then
        verify(memberSessionService).revoke(1L);
    }
}
