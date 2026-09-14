package com.widyu.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.widyu.auth.RefreshToken;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.JwtUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import io.jsonwebtoken.ExpiredJwtException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtTokenProvider 예외 처리 단위 테스트")
class JwtTokenProviderTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private MemberSessionService memberSessionService;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("refresh 없이 만료 access만으로 재발급을 요청하면 만료 오류로 거절한다")
    void 만료_access_단독_재발급을_거절한다() {
        // given
        given(jwtUtil.parseAccessToken("expired"))
                .willThrow(new ExpiredJwtException(null, null, "expired"));
        // when / then
        assertThatThrownBy(() -> jwtTokenProvider.reissueAccessTokenIfExpired("expired"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_ACCESS_TOKEN);
        Mockito.verifyNoInteractions(refreshTokenRepository, memberSessionService);
    }

    private void stubCurrent(Long id, String token) {
        given(memberSessionService.lock(id)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        given(jwtUtil.refreshVersion(token)).willReturn(0L);
    }

    @Test
    @DisplayName("만료된 액세스 토큰은 EXPIRED_ACCESS_TOKEN 예외를 던진다")
    void 만료된_액세스_토큰은_예외가_발생한다() {
        // given
        given(jwtUtil.parseAccessToken("expired-token"))
                .willThrow(new ExpiredJwtException(null, null, "expired"));

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveAccessToken("expired-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_ACCESS_TOKEN)
                .hasMessageContaining("액세스 토큰이 만료되었습니다.");
    }

    @Test
    @DisplayName("파싱 결과가 없는 액세스 토큰은 INVALID_ACCESS_TOKEN 예외를 던진다")
    void 파싱_결과가_없는_액세스_토큰은_예외가_발생한다() {
        // given
        given(jwtUtil.parseAccessToken("invalid-token")).willReturn(null);

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveAccessToken("invalid-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ACCESS_TOKEN)
                .hasMessageContaining("유효하지 않은 액세스 토큰입니다.");
    }

    @Test
    @DisplayName("저장소에 없는 리프레시 토큰은 INVALID_REFRESH_TOKEN 예외를 던진다")
    void 저장소에_없는_리프레시_토큰은_예외가_발생한다() {
        // given
        given(jwtUtil.parseRefreshToken("refresh-token"))
                .willReturn(new RefreshTokenDto(1L, "refresh-token", 3600L));
        stubCurrent(1L, "refresh-token");
        given(refreshTokenRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken("refresh-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
                .hasMessageContaining("리프레시 토큰이 유효하지 않습니다.");
    }

    @Test
    @DisplayName("저장소의 최신 리프레시 토큰과 요청 토큰이 일치하면 토큰 정보를 반환한다")
    void 저장소의_최신_리프레시_토큰과_요청_토큰이_일치하면_토큰_정보를_반환한다() {
        // given
        String refreshToken = "latest-refresh-token";
        RefreshTokenDto refreshTokenDto = new RefreshTokenDto(1L, refreshToken, 3600L);
        RefreshToken savedRefreshToken = RefreshToken.builder()
                .memberId(1L)
                .token(refreshToken)
                .ttl(3600L)
                .build();

        stubCurrent(1L, refreshToken);
        given(jwtUtil.parseRefreshToken(refreshToken)).willReturn(refreshTokenDto);
        given(refreshTokenRepository.findById(1L)).willReturn(Optional.of(savedRefreshToken));

        // when
        RefreshTokenDto result = jwtTokenProvider.retrieveRefreshToken(refreshToken);

        // then
        assertThat(result).isEqualTo(refreshTokenDto);
    }

    @Test
    @DisplayName("저장소에 키가 있어도 저장된 리프레시 토큰 값과 요청 토큰이 다르면 실패한다")
    void 저장소에_키가_있어도_저장된_리프레시_토큰_값과_요청_토큰이_다르면_실패한다() {
        // given
        String oldRefreshToken = "old-refresh-token";
        String latestRefreshToken = "latest-refresh-token";
        RefreshTokenDto oldRefreshTokenDto = new RefreshTokenDto(1L, oldRefreshToken, 3600L);
        RefreshToken savedRefreshToken = RefreshToken.builder()
                .memberId(1L)
                .token(latestRefreshToken)
                .ttl(3600L)
                .build();

        stubCurrent(1L, oldRefreshToken);
        given(jwtUtil.parseRefreshToken(oldRefreshToken)).willReturn(oldRefreshTokenDto);
        given(refreshTokenRepository.findById(1L)).willReturn(Optional.of(savedRefreshToken));

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken(oldRefreshToken))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
                .hasMessageContaining("리프레시 토큰이 유효하지 않습니다.");
    }

    @Test
    @DisplayName("다른 회원의 저장값과 일치하지 않는 리프레시 토큰으로는 재발급할 수 없다")
    void 다른_회원의_저장값과_일치하지_않는_리프레시_토큰으로는_재발급할_수_없다() {
        // given
        String requestedRefreshToken = "member-one-refresh-token";
        RefreshTokenDto requestedRefreshTokenDto = new RefreshTokenDto(2L, requestedRefreshToken, 3600L);
        RefreshToken savedRefreshToken = RefreshToken.builder()
                .memberId(2L)
                .token("member-two-refresh-token")
                .ttl(3600L)
                .build();

        stubCurrent(2L, requestedRefreshToken);
        given(jwtUtil.parseRefreshToken(requestedRefreshToken)).willReturn(requestedRefreshTokenDto);
        given(refreshTokenRepository.findById(2L)).willReturn(Optional.of(savedRefreshToken));

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken(requestedRefreshToken))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("만료된 리프레시 토큰은 INVALID_REFRESH_TOKEN 예외를 던진다")
    void 만료된_리프레시_토큰은_예외가_발생한다() {
        // given
        given(jwtUtil.parseRefreshToken("expired-refresh-token"))
                .willThrow(new ExpiredJwtException(null, null, "expired"));

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken("expired-refresh-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN)
                .hasMessageContaining("리프레시 토큰이 유효하지 않습니다.");
    }

    @Test
    @DisplayName("토큰 쌍 생성 시 리프레시 토큰은 한 번만 저장된다")
    void 토큰_쌍_생성_시_리프레시_토큰은_한_번만_저장된다() {
        // given
        given(memberSessionService.lock(1L)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        given(jwtUtil.generateAccessToken(1L, MemberRole.USER, "local", 0L))
                .willReturn("access-token");
        given(jwtUtil.generateRefreshToken(1L, 0L)).willReturn("refresh-token");
        given(jwtUtil.getRefreshTokenExpirationTime()).willReturn(3600L);

        // when
        jwtTokenProvider.generateTokenPair(1L, MemberRole.USER, "local");

        // then
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo(1L);
        assertThat(captor.getValue().getToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("파싱 결과가 없는 소셜 임시 토큰은 INVALID_TEMPORARY_TOKEN 예외를 던진다")
    void 파싱_결과가_없는_소셜_임시_토큰은_예외가_발생한다() {
        // given
        given(jwtUtil.parseSocialTemporaryToken("invalid-social-token")).willReturn(null);

        // when & then
        assertThatThrownBy(() -> jwtTokenProvider.retrieveSocialTemporaryToken("invalid-social-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TEMPORARY_TOKEN)
                .hasMessageContaining("유효하지 않은 임시 토큰입니다.");
    }
}
