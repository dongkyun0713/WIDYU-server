package com.widyu.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.BDDMockito.given;

import com.widyu.auth.RefreshToken;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.JwtUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueAdapter;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.mapping.RedisMappingContext;
import org.springframework.data.redis.repository.support.RedisRepositoryFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * JwtTokenProvider의 refresh token 회전·비교 동작을 실제 Redis로 검증한다.
 *
 * <p>Mock만으로는 Redis key/value 저장·덮어쓰기 회귀를 충분히 보호할 수 없으므로
 * 로컬 Redis(localhost:6379)에 직접 연결한다.
 * Redis가 없는 환경에서는 {@code assumeTrue}로 건너뛴다.
 * JwtUtil은 Mock으로 두고 JWT 서명은 검증하지 않는다 — 검증 대상은 Redis 저장값 회전·비교이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtTokenProvider Redis 통합 테스트")
class JwtTokenProviderRedisTest {

    private static final Long MEMBER_ID = 100L;
    private static final long TTL_SECONDS = 3600L;
    private static final String REFRESH_TOKEN_KEY_PREFIX = "refreshToken:";

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, Object> redisTemplate;
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private JwtUtil jwtUtil;
    private JwtTokenProvider jwtTokenProvider;
    @Mock private MemberSessionService memberSessionService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        assumeTrue(isRedisReachable(), "로컬 Redis(localhost:6379)가 없어 테스트를 건너뜁니다.");

        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();

        RedisMappingContext mappingContext = new RedisMappingContext();
        RedisKeyValueAdapter adapter = new RedisKeyValueAdapter(redisTemplate, mappingContext);
        RedisKeyValueTemplate keyValueTemplate = new RedisKeyValueTemplate(adapter, mappingContext);
        RedisRepositoryFactory repositoryFactory = new RedisRepositoryFactory(keyValueTemplate);
        refreshTokenRepository = repositoryFactory.getRepository(RefreshTokenRepository.class);

        jwtTokenProvider = new JwtTokenProvider(jwtUtil, refreshTokenRepository, memberSessionService);
    }

    @AfterEach
    void tearDown() {
        if (refreshTokenRepository != null) {
            refreshTokenRepository.deleteById(MEMBER_ID);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private boolean isRedisReachable() {
        try {
            return "PONG".equalsIgnoreCase(connectionFactory.getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("저장된 최신 리프레시 토큰으로 retrieveRefreshToken을 호출하면 토큰 정보를 반환한다")
    void 저장된_최신_리프레시_토큰으로_retrieveRefreshToken을_호출하면_토큰_정보를_반환한다() {
        // given
        given(memberSessionService.lock(MEMBER_ID)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        String refreshToken = "refresh-token-1";
        RefreshToken savedToken = RefreshToken.builder()
                .memberId(MEMBER_ID)
                .token(refreshToken)
                .ttl(TTL_SECONDS)
                .build();
        refreshTokenRepository.save(savedToken);

        given(jwtUtil.refreshVersion(refreshToken)).willReturn(0L);
        given(jwtUtil.parseRefreshToken(refreshToken))
                .willReturn(new RefreshTokenDto(MEMBER_ID, refreshToken, TTL_SECONDS));

        // when
        RefreshTokenDto result = jwtTokenProvider.retrieveRefreshToken(refreshToken);

        // then
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.tokenValue()).isEqualTo(refreshToken);
    }

    @Test
    @DisplayName("회전 이후 이전 리프레시 토큰으로 재사용하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    void 회전_이후_이전_리프레시_토큰으로_재사용하면_예외가_발생한다() {
        // given — token A 저장 후 token B로 덮어쓰기 (회전)
        given(memberSessionService.lock(MEMBER_ID)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        String tokenA = "refresh-token-old";
        String tokenB = "refresh-token-new";

        RefreshToken initialToken = RefreshToken.builder()
                .memberId(MEMBER_ID)
                .token(tokenA)
                .ttl(TTL_SECONDS)
                .build();
        refreshTokenRepository.save(initialToken);

        // 회전: tokenB로 덮어쓰기
        RefreshToken rotatedToken = RefreshToken.builder()
                .memberId(MEMBER_ID)
                .token(tokenB)
                .ttl(TTL_SECONDS)
                .build();
        refreshTokenRepository.save(rotatedToken);

        // 이전 token A로 parseRefreshToken stub
        given(jwtUtil.refreshVersion(tokenA)).willReturn(0L);
        given(jwtUtil.parseRefreshToken(tokenA))
                .willReturn(new RefreshTokenDto(MEMBER_ID, tokenA, TTL_SECONDS));

        // when & then — 이전 token A는 Redis 저장값(tokenB)와 불일치 → 실패
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken(tokenA))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("재발급 성공 후 Redis 저장값은 새 토큰과 일치하고 이전 토큰은 검증에 실패한다")
    void 재발급_성공_후_Redis_저장값은_새_토큰과_일치하고_이전_토큰은_검증에_실패한다() {
        // given — token A 저장
        given(memberSessionService.lock(MEMBER_ID)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        String oldToken = "refresh-token-before-reissue";
        String newToken = "refresh-token-after-reissue";

        RefreshToken initialToken = RefreshToken.builder()
                .memberId(MEMBER_ID)
                .token(oldToken)
                .ttl(TTL_SECONDS)
                .build();
        refreshTokenRepository.save(initialToken);

        // when — 재발급 성공 시 generateTokenPair 내부에서 새 token 저장 (stub)
        given(jwtUtil.generateRefreshToken(MEMBER_ID, 0L)).willReturn(newToken);
        given(jwtUtil.getRefreshTokenExpirationTime()).willReturn(TTL_SECONDS);
        given(jwtUtil.generateAccessToken(MEMBER_ID, MemberRole.USER, "local", 0L))
                .willReturn("access-token");

        jwtTokenProvider.generateTokenPair(MEMBER_ID, MemberRole.USER, "local");

        // then — Redis 저장값이 newToken과 일치
        RefreshToken storedToken = refreshTokenRepository.findById(MEMBER_ID).orElseThrow();
        assertThat(storedToken.getToken()).isEqualTo(newToken);

        // 이전 토큰(oldToken)으로 retrieveRefreshToken 호출 시 실패
        given(jwtUtil.refreshVersion(oldToken)).willReturn(0L);
        given(jwtUtil.parseRefreshToken(oldToken))
                .willReturn(new RefreshTokenDto(MEMBER_ID, oldToken, TTL_SECONDS));

        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken(oldToken))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    @DisplayName("로그아웃으로 삭제 후 리프레시 토큰을 재사용하면 INVALID_REFRESH_TOKEN 예외가 발생한다")
    void 로그아웃으로_삭제_후_리프레시_토큰을_재사용하면_예외가_발생한다() {
        // given — token 저장 후 삭제 (로그아웃)
        given(memberSessionService.lock(MEMBER_ID)).willReturn(Member.createMember(MemberType.GUARDIAN, "회원", "01012345678"));
        String refreshToken = "refresh-token-to-logout";

        RefreshToken savedToken = RefreshToken.builder()
                .memberId(MEMBER_ID)
                .token(refreshToken)
                .ttl(TTL_SECONDS)
                .build();
        refreshTokenRepository.save(savedToken);
        refreshTokenRepository.deleteById(MEMBER_ID);

        given(jwtUtil.refreshVersion(refreshToken)).willReturn(0L);
        given(jwtUtil.parseRefreshToken(refreshToken))
                .willReturn(new RefreshTokenDto(MEMBER_ID, refreshToken, TTL_SECONDS));

        // when & then — 삭제 후 재사용 실패
        assertThatThrownBy(() -> jwtTokenProvider.retrieveRefreshToken(refreshToken))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
    }
}
