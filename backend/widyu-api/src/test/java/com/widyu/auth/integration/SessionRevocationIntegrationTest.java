package com.widyu.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.admin.application.AdminMemberService;
import com.widyu.auth.RefreshToken;
import com.widyu.auth.TemporaryMember;
import com.widyu.auth.application.LogoutService;
import com.widyu.auth.application.guardian.MemberWithdrawService;
import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.MemberWithdrawRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.properties.JwtProperties;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.MemberSessionService;
import com.widyu.global.util.JwtUtil;
import com.widyu.global.util.MemberUtil;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.global.websocket.WsSessionGuard;
import com.widyu.goal.medicineschedule.application.MedicationProofDeletionService;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, MemberSessionService.class, JwtTokenProvider.class,
        LogoutService.class,
        MemberWithdrawService.class,
        LocalLoginService.class,
        AdminMemberService.class,
        com.widyu.admin.application.AdminAuthService.class,
        com.widyu.admin.validator.AdminAccessValidator.class,
        com.widyu.auth.application.SocialTemporaryTokenService.class,
        com.widyu.auth.application.guardian.oauth.SocialLoginService.class,
        com.widyu.auth.application.guardian.GuardianAuthService.class,
        com.widyu.auth.application.guardian.GuardianTokenService.class,
        FamilyAccessService.class,
        WsSessionGuard.class,
        SessionRevocationIntegrationTest.Tokens.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("DB 인증 버전 세션 폐기 통합 검증")
class SessionRevocationIntegrationTest {
    @Autowired private MemberRepository members;
    @Autowired private MemberSessionService sessions;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private JwtUtil jwt;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private RefreshTokenRepository refreshTokens;
    @Autowired private LogoutService logout;
    @Autowired private MemberWithdrawService withdrawal;
    @Autowired private LocalLoginService localLogin;
    @Autowired private AdminMemberService adminMembers;
    @Autowired private com.widyu.admin.application.AdminAuthService adminLogin;
    @Autowired private com.widyu.auth.application.SocialTemporaryTokenService socialTemporaryTokens;
    @Autowired private com.widyu.auth.application.guardian.oauth.SocialLoginService socialLogin;
    @Autowired private com.widyu.auth.application.guardian.GuardianAuthService guardianAuth;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private LocalAccountRepository localAccounts;
    @Autowired private FamilyAccessService familyAccess;
    @Autowired private WsSessionGuard wsGuard;
    @Autowired private FamilyRepository families;
    @Autowired private SeniorProfileRepository seniors;
    @Autowired private FamilyMembershipRepository memberships;
    @MockBean private MemberUtil memberUtil;
    @MockBean private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @MockBean private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;
    @MockBean private TemporaryMemberUtil temporaryMembers;
    @MockBean private PasswordEncoder passwords;
    @MockBean private AdminAuditLogService audit;
    @MockBean private MedicationProofDeletionService proofDeletion;
    @MockBean private SocialLoginStrategyFactory socialStrategies;
    @MockBean private com.widyu.auth.application.guardian.GuardianSmsService guardianSms;
    @MockBean private com.widyu.auth.application.TemporaryTokenService temporaryTokenService;

    @TestConfiguration
    static class Tokens {
        @Bean JPAQueryFactory queryFactory(jakarta.persistence.EntityManager entityManager) {
            return new JPAQueryFactory(entityManager);
        }

        @Bean JwtUtil jwtUtil() {
            return new JwtUtil(new JwtProperties("access-test-only-key-01234567890123456789", 3600L,
                    "refresh-test-only-key-01234567890123456789", 7200L,
                    "temporary-test-only-key-01234567890123456789", 1800L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "suspend", "withdraw", "password"})
    @DisplayName("실제 로그아웃 정지 탈퇴 비밀번호 변경 경로가 모든 기기 토큰을 폐기한다")
    void 각_서비스_경로가_전체_기기를_폐기한다(String action) throws Exception {
        // given
        redisStorage();
        Long id = createMember();
        Member member = members.findById(id).orElseThrow();
        given(memberUtil.getCurrentMember()).willReturn(member);
        TokenPairResponse first = tokens.generateTokenPair(id, MemberRole.USER, "local");
        TokenPairResponse second = tokens.generateTokenPair(id, MemberRole.USER, "local");
        var socket = Mockito.mock(WebSocketSession.class);
        given(socket.getId()).willReturn(action);
        given(socket.getAttributes()).willReturn(Map.of("memberId", id, "authVersion", 0L));
        wsGuard.register(socket);

        // when
        switch (action) {
            case "logout" -> guardianAuth.logout();
            case "suspend" -> adminMembers.changeStatus(id, Status.INACTIVE);
            case "withdraw" -> withdrawal.withdrawMember(new MemberWithdrawRequest("탈퇴"));
            case "password" -> {
                // 이름과 전화번호를 이 케이스에서만 고유하게 만들어 본인확인 대상을 지정한다.
                tx().executeWithoutResult(status -> sessions.lock(id).updateName("비밀번호회원" + id));
                localAccounts.saveAndFlush(LocalAccount.createLocalAccount(member, id + "@test.example", "old"));
                var request = new MockHttpServletRequest();
                var temporary = TemporaryMember.createTemporaryMember("비밀번호회원" + id, "01000000000");
                temporary.bindSession(id, 0L);
                given(temporaryMembers.getTemporaryMemberFromRequest(request)).willReturn(temporary);
                given(passwords.encode("new")).willReturn("encoded-new");
                localLogin.changePassword(new ChangePasswordRequest("new"), request);
                assertThat(localAccounts.findByEmail(id + "@test.example").orElseThrow().getPassword()).isEqualTo("encoded-new");
            }
            default -> throw new IllegalArgumentException(action);
        }

        // then
        assertThat(members.findById(id).orElseThrow().getAuthVersion()).isEqualTo(1);
        assertThatThrownBy(() -> tokens.retrieveAccessToken(first.accessToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.retrieveAccessToken(second.accessToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> refresh(second.refreshToken())).isInstanceOf(BusinessException.class);
        Mockito.verify(socket).close(CloseStatus.POLICY_VIOLATION);
        if (action.equals("withdraw")) {
            assertThat(members.findById(id).orElseThrow().getStatus()).isEqualTo(Status.DELETED);
        }
    }

    @Test
    @DisplayName("폐기 트랜잭션이 롤백되면 버전과 기존 WS 연결이 유지된다")
    void 폐기_롤백은_연결을_종료하지_않는다() throws Exception {
        // given
        Long id = createMember();
        var socket = Mockito.mock(WebSocketSession.class);
        given(socket.getId()).willReturn("rollback");
        given(socket.getAttributes()).willReturn(Map.of("memberId", id, "authVersion", 0L));
        wsGuard.register(socket);
        // when
        tx().executeWithoutResult(status -> {
            sessions.revoke(id);
            status.setRollbackOnly();
        });
        // then
        assertThat(sessions.isCurrent(id, 0L)).isTrue();
        Mockito.verify(socket, Mockito.never()).close(any(CloseStatus.class));
        wsGuard.remove("rollback");
    }

    @Test
    @DisplayName("실제 가족 연결을 삭제하면 기존 WS 세션의 outbound 접근을 거절한다")
    void 가족_DB_연결_삭제_후_기존_WS_전달을_차단한다() throws Exception {
        // given
        Long guardianId = createMember();
        Member senior = members.saveAndFlush(Member.createMember(MemberType.SENIOR, "시니어", "01099999999"));
        var family = families.saveAndFlush(Family.createFamily("F60301"));
        seniors.saveAndFlush(SeniorProfile.createSeniorProfile(senior, family, "주소", "I603001", java.time.LocalDate.of(1950, 1, 1)));
        memberships.saveAndFlush(FamilyMembership.createMembership(family, members.findById(guardianId).orElseThrow()));
        var guard = new WsSessionGuard(sessions, familyAccess);
        var socket = Mockito.mock(WebSocketSession.class);
        given(socket.getId()).willReturn("actual-family");
        given(socket.getAttributes()).willReturn(Map.of("memberId", guardianId, "authVersion", 0L));
        guard.register(socket);
        var header = StompHeaderAccessor.create(StompCommand.MESSAGE);
        header.setSessionId("actual-family");
        header.setDestination("/topic/heart-rate/" + senior.getId());
        var message = MessageBuilder.createMessage(new byte[0], header.getMessageHeaders());
        assertThat(guard.preSend(message, null)).isSameAs(message);

        // when
        tx().executeWithoutResult(status -> memberships.deleteByGuardianId(guardianId));

        // then
        assertThat(guard.beforeHandle(message, null, ignored -> {})).isNull();
        Mockito.verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("두 기기 토큰을 폐기하면 Redis eviction과 복원 후에도 이전 버전을 거절한다")
    void 모든_기기의_토큰은_Redis_삭제와_복원_후에도_폐기된다() {
        // given
        var redis = redisStorage();
        Long id = createMember();
        TokenPairResponse first = tokens.generateTokenPair(id, MemberRole.USER, "local");
        TokenPairResponse second = tokens.generateTokenPair(id, MemberRole.USER, "local");
        assertThat(tokens.retrieveAccessToken(first.accessToken()).memberId()).isEqualTo(id);
        assertThat(jwt.accessVersion(first.accessToken())).isZero();
        assertThat(jwt.refreshVersion(second.refreshToken())).isZero();
        RefreshToken stored = redis.get(id);

        // when
        sessions.revoke(id);
        redis.clear();

        // then
        assertThatThrownBy(() -> tokens.retrieveAccessToken(first.accessToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.retrieveAccessToken(second.accessToken())).isInstanceOf(BusinessException.class);
        redis.put(id, stored);
        assertThatThrownBy(() -> refresh(second.refreshToken())).isInstanceOf(BusinessException.class);
        assertThat(members.findById(id).orElseThrow().getAuthVersion()).isEqualTo(1);
        TokenPairResponse relogin = tokens.generateTokenPair(id, MemberRole.USER, "local");
        assertThat(jwt.accessVersion(relogin.accessToken())).isEqualTo(1);
        assertThat(tokens.retrieveAccessToken(relogin.accessToken()).memberId()).isEqualTo(id);
    }

    @Test
    @DisplayName("서명이 유효해도 버전 없는 access와 refresh는 거절한다")
    void 버전이_없는_구토큰은_거절한다() {
        // given
        var redis = redisStorage();
        Long id = createMember();
        String legacyAccess = jwt.generateAccessToken(id, MemberRole.USER, "local");
        String legacyRefresh = jwt.generateRefreshToken(id);
        redis.put(id, RefreshToken.builder().memberId(id).token(legacyRefresh).ttl(7200L).build());

        // when / then
        assertThatThrownBy(() -> tokens.retrieveAccessToken(legacyAccess)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> refresh(legacyRefresh)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("DB 정지와 탈퇴 후에는 현재 버전 토큰과 신규 발급을 모두 거절한다")
    void 비활성_회원은_현재_버전도_인증되지_않는다() {
        // given
        redisStorage();
        Long id = createMember();
        TokenPairResponse pair = tokens.generateTokenPair(id, MemberRole.USER, "local");

        // when
        tx().executeWithoutResult(status -> sessions.revoke(id).suspend());

        // then
        assertThatThrownBy(() -> tokens.retrieveAccessToken(pair.accessToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> refresh(pair.refreshToken())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tokens.generateTokenPair(id, MemberRole.USER, "local")).isInstanceOf(BusinessException.class);
        tx().executeWithoutResult(status -> sessions.revoke(id).withdraw());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> sessions.lock(id).reactivate()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> sessions.lock(id).suspend()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("refresh가 먼저 잠그면 폐기가 대기하고 커밋 후 새 토큰도 폐기한다")
    void refresh_우선_경합에서도_발급한_토큰은_폐기된다() throws Exception {
        // given
        redisStorage();
        Long id = createMember();
        TokenPairResponse pair = tokens.generateTokenPair(id, MemberRole.USER, "local");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch revokeStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refreshing = executor.submit(() -> tx().execute(status -> {
                tokens.retrieveRefreshToken(pair.refreshToken());
                locked.countDown();
                await(release);
                return tokens.generateTokenPair(id, MemberRole.USER, "local");
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var revoking = executor.submit(() -> {
                revokeStarted.countDown();
                return sessions.revoke(id).getAuthVersion();
            });
            assertThat(revokeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                assertThatThrownBy(() -> revoking.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            TokenPairResponse issued = refreshing.get(5, TimeUnit.SECONDS);
            assertThat(revoking.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            // then
            assertThatThrownBy(() -> tokens.retrieveAccessToken(issued.accessToken())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> refresh(issued.refreshToken())).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("폐기가 먼저 잠그면 refresh는 기다린 뒤 변경된 버전을 거절한다")
    void 폐기_우선_경합에서_refresh는_재발급하지_않는다() throws Exception {
        // given
        redisStorage();
        Long id = createMember();
        TokenPairResponse pair = tokens.generateTokenPair(id, MemberRole.USER, "local");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revoking = executor.submit(() -> tx().execute(status -> {
                sessions.revoke(id);
                locked.countDown();
                await(release);
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var refreshing = executor.submit(() -> {
                refreshStarted.countDown();
                return refresh(pair.refreshToken());
            });
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                assertThatThrownBy(() -> refreshing.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            revoking.get(5, TimeUnit.SECONDS);
            // then
            assertThatThrownBy(() -> refreshing.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("폐기 전에 읽은 프로필 수정이 늦게 커밋돼도 인증 버전은 되돌아가지 않는다")
    void 오래된_프로필_수정은_폐기_버전을_덮어쓰지_않는다() throws Exception {
        // given
        Long id = createMember();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var profile = executor.submit(() -> tx().execute(status -> {
                Member member = members.findById(id).orElseThrow();
                read.countDown();
                await(release);
                member.updateName("수정회원");
                return true;
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                sessions.revoke(id);
            } finally {
                release.countDown();
            }
            profile.get(5, TimeUnit.SECONDS);
            // then
            Member member = members.findById(id).orElseThrow();
            assertThat(member.getAuthVersion()).isEqualTo(1);
            assertThat(member.getName()).isEqualTo("수정회원");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("비밀번호 검증부터 발급까지 잠그면 동시 비밀번호 변경이 기다리고 발급 토큰을 폐기한다")
    void 로컬과_관리자_비밀번호_검증_중_변경은_발급_후_폐기한다(boolean admin) throws Exception {
        // given
        redisStorage();
        Member member = Member.createMember(MemberType.GUARDIAN, "로그인경합", "01033333333");
        if (admin) {
            member = Member.createAdminMember("관리자경합", "01033333333");
        }
        Member saved = members.saveAndFlush(member);
        Long id = saved.getId();
        String email = id + "@race.example";
        localAccounts.saveAndFlush(LocalAccount.createLocalAccount(saved, email, "old"));
        CountDownLatch validating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch resetting = new CountDownLatch(1);
        given(passwords.matches("old", "old")).willAnswer(call -> {
            validating.countDown();
            await(release);
            return true;
        });
        var request = new MockHttpServletRequest();
        var temporary = TemporaryMember.createTemporaryMember(saved.getName(), saved.getPhoneNumber());
        temporary.bindSession(id, 0L);
        given(temporaryMembers.getTemporaryMemberFromRequest(request)).willReturn(temporary);
        given(passwords.encode("new")).willReturn("new");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var login = executor.submit(() -> login(admin, email, "old"));
            assertThat(validating.await(5, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() -> {
                resetting.countDown();
                return localLogin.changePassword(new ChangePasswordRequest("new"), request);
            });
            assertThat(resetting.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                assertThatThrownBy(() -> reset.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            TokenPairResponse issued = login.get(5, TimeUnit.SECONDS);
            assertThat(reset.get(5, TimeUnit.SECONDS)).isTrue();
            // then
            assertThat(jwt.accessVersion(issued.accessToken())).isZero();
            assertThatThrownBy(() -> tokens.retrieveAccessToken(issued.accessToken())).isInstanceOf(BusinessException.class);
            assertThat(localAccounts.findByEmail(email).orElseThrow().getPassword()).isEqualTo("new");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("계정을 먼저 읽었어도 비밀번호 변경 커밋 후 잠그면 옛 비밀번호를 거절한다")
    void 로컬과_관리자는_잠금_전의_계정_스냅샷을_재사용하지_않는다(boolean admin) throws Exception {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "스냅샷", "01044444444");
        if (admin) {
            member = Member.createAdminMember("관리자스냅샷", "01044444444");
        }
        Member saved = members.saveAndFlush(member);
        Long id = saved.getId();
        String email = id + "@snapshot.example";
        localAccounts.saveAndFlush(LocalAccount.createLocalAccount(saved, email, "old"));
        given(passwords.matches("old", "new")).willReturn(false);
        given(passwords.matches("old", "old")).willReturn(true);
        var resetRequest = new MockHttpServletRequest();
        var temporary = TemporaryMember.createTemporaryMember(saved.getName(), saved.getPhoneNumber());
        temporary.bindSession(id, 0L);
        given(temporaryMembers.getTemporaryMemberFromRequest(resetRequest)).willReturn(temporary);
        given(passwords.encode("new")).willReturn("new");
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loggingIn = executor.submit(() -> tx().execute(status -> {
                LocalAccount before = localAccounts.findByEmail(email).orElseThrow();
                assertThat(before.getPassword()).isEqualTo("old");
                before.getMember().getName();
                read.countDown();
                await(release);
                return login(admin, email, "old");
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                assertThat(localLogin.changePassword(new ChangePasswordRequest("new"), resetRequest)).isTrue();
            } finally {
                release.countDown();
            }
            // then
            assertThatThrownBy(() -> loggingIn.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", com.widyu.global.error.ErrorCode.INVALID_PASSWORD);
        }
    }

    @Test
    @DisplayName("신규 가입 후 미flush 필드와 소셜 관계가 있어도 발급 잠금은 모두 보존한다")
    void 신규가입의_미flush_필드와_관계를_보존한다() {
        // given
        redisStorage();
        // when
        Long id = tx().execute(status -> {
            Member member = members.save(Member.createMember(MemberType.GUARDIAN, "가입", "01055555555"));
            member.updateName("가입완료");
            member.getSocialAccounts().add(com.widyu.member.SocialAccount.createSocialAccount(
                    "new@signup.example", "kakao", "signup-oauth", null, member));
            tokens.generateTokenPair(member.getId(), MemberRole.USER, "kakao");
            member.updateProfileImage("pending-image");
            tokens.generateTokenPair(member.getId(), MemberRole.USER, "kakao");
            assertThat(member.getName()).isEqualTo("가입완료");
            assertThat(member.getSocialAccounts()).hasSize(1);
            return member.getId();
        });
        // then
        tx().executeWithoutResult(status -> {
            Member member = members.findById(id).orElseThrow();
            assertThat(member.getName()).isEqualTo("가입완료");
            assertThat(member.getProfileImage()).isEqualTo("pending-image");
            assertThat(member.getSocialAccount("kakao").getOauthId()).isEqualTo("signup-oauth");
        });
    }

    @Test
    @DisplayName("미분류 과거 INACTIVE 회원은 관리자 재활성화와 정지 재설정으로 복구되지 않는다")
    void 미분류_과거_회원은_관리자도_재활성화하지_못한다() {
        // given
        Long id = createMember();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                "update member set status = 'INACTIVE', reactivation_blocked = true where id = :id")
                .setParameter("id", id).executeUpdate());
        // when / then
        assertThatThrownBy(() -> adminMembers.changeStatus(id, Status.ACTIVE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminMembers.changeStatus(id, Status.INACTIVE)).isInstanceOf(BusinessException.class);
        assertThat(members.findById(id).orElseThrow().getStatus()).isEqualTo(Status.INACTIVE);
        assertThat(members.findById(id).orElseThrow().getAuthVersion()).isZero();
    }

    @Test
    @DisplayName("소셜 임시 토큰은 폐기와 소비 후 재사용 및 구버전 발급을 거절한다")
    void 소셜_임시토큰은_폐기와_소비_후_재사용되지_않는다() {
        // given
        Long id = createMember();
        String token = tokens.generateSocialTemporaryToken(id, "kakao", "oauth", "social@example.test");
        assertThat(tokens.retrieveSocialTemporaryToken(token).memberId()).isEqualTo(id);
        // when
        sessions.revoke(id);
        // then
        assertThatThrownBy(() -> tokens.retrieveSocialTemporaryToken(token)).isInstanceOf(BusinessException.class);
        String current = tokens.generateSocialTemporaryToken(id, "kakao", "oauth", "social@example.test");
        socialTemporaryTokens.deleteSocialTemporaryToken(current);
        assertThatThrownBy(() -> socialTemporaryTokens.deleteSocialTemporaryToken(current)).isInstanceOf(BusinessException.class);
        String legacy = jwt.generateSocialTemporaryToken(id, "kakao", "oauth", "social@example.test");
        assertThatThrownBy(() -> tokens.retrieveSocialTemporaryToken(legacy)).isInstanceOf(BusinessException.class);
        tx().executeWithoutResult(status -> sessions.lock(id).suspend());
        assertThatThrownBy(() -> tokens.generateSocialTemporaryToken(id, "kakao", "oauth", "social@example.test"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("재설정 임시 데이터를 복원해도 폐기와 비밀번호 변경 후 다시 사용할 수 없다")
    void 재설정_임시데이터는_삭제에_의존하지_않고_재사용을_거절한다() {
        // given
        Long id = createMember();
        tx().executeWithoutResult(status -> sessions.lock(id).updateName("재설정" + id));
        Member member = members.findById(id).orElseThrow();
        localAccounts.saveAndFlush(LocalAccount.createLocalAccount(member, id + "@reset.example", "old"));
        var temporary = TemporaryMember.createTemporaryMember(member.getName(), member.getPhoneNumber());
        temporary.bindSession(id, 0L);
        var request = new MockHttpServletRequest();
        given(temporaryMembers.getTemporaryMemberFromRequest(request)).willReturn(temporary);
        given(passwords.encode("new")).willReturn("new");
        // when
        sessions.revoke(id);
        // then
        assertThatThrownBy(() -> localLogin.changePassword(new ChangePasswordRequest("new"), request))
                .isInstanceOf(BusinessException.class);
        temporary.bindSession(id, 1L);
        assertThat(localLogin.changePassword(new ChangePasswordRequest("new"), request)).isTrue();
        assertThatThrownBy(() -> localLogin.changePassword(new ChangePasswordRequest("new"), request))
                .isInstanceOf(BusinessException.class);
        assertThat(members.findById(id).orElseThrow().getAuthVersion()).isEqualTo(2);
    }

    private TokenPairResponse login(boolean admin, String email, String password) {
        if (admin) {
            return adminLogin.login(email, password);
        }
        return localLogin.signIn(new com.widyu.auth.dto.request.LocalGuardianSignInRequest(email, password));
    }

    @Test
    @DisplayName("실제 소셜 연동 후 계정을 해제해도 소비한 임시 토큰으로 재연동하지 못한다")
    void 소셜_임시토큰의_실제_연동_사용처는_재사용을_차단한다() {
        // given
        redisStorage();
        Long id = createMember();
        String token = tokens.generateSocialTemporaryToken(id, "kakao", "link-oauth", "link@example.test");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        // when
        TokenPairResponse pair = socialLogin.integrateSocialAccount(request);
        // then
        assertThat(jwt.accessVersion(pair.accessToken())).isEqualTo(1);
        tx().executeWithoutResult(status -> sessions.lock(id).getSocialAccounts().clear());
        assertThatThrownBy(() -> socialLogin.integrateSocialAccount(request)).isInstanceOf(BusinessException.class);
        tx().executeWithoutResult(status -> assertThat(members.findById(id).orElseThrow().getSocialAccounts()).isEmpty());
    }

    @Test
    @DisplayName("소셜 계정을 선조회해도 탈퇴의 계정 해제가 커밋되면 로그인 발급을 거절한다")
    void 소셜_로그인_선조회_후_탈퇴가_커밋되면_발급하지_않는다() throws Exception {
        // given
        Long id = createMember();
        String oauthId = "withdraw-oauth-" + id;
        tx().executeWithoutResult(status -> {
            Member member = sessions.lock(id);
            member.getSocialAccounts().add(com.widyu.member.SocialAccount.createSocialAccount(
                    id + "@social.example", "kakao", oauthId, null, member));
        });
        var strategy = Mockito.mock(com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy.class);
        given(socialStrategies.getStrategy("kakao")).willReturn(strategy);
        given(memberUtil.getCurrentMember()).willReturn(members.findById(id).orElseThrow());
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var login = executor.submit(() -> tx().execute(status -> {
                Member found = members.findWithAllAccountsById(id).orElseThrow();
                found.getSocialAccount("kakao");
                read.countDown();
                await(release);
                // 외부 OAuth 검증/기존 계정 조회가 끝난 실제 로그인 분기부터 계속한다.
                com.widyu.auth.application.guardian.oauth.SocialLoginService target =
                        org.springframework.test.util.AopTestUtils.getTargetObject(socialLogin);
                return org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,
                        "handleExistingMemberLogin", found, com.widyu.auth.OAuthProvider.KAKAO, oauthId);
            }));
            assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                withdrawal.withdrawMember(new MemberWithdrawRequest("경합"));
            } finally {
                release.countDown();
            }
            // then
            assertThatThrownBy(() -> login.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(members.findById(id).orElseThrow().getStatus()).isEqualTo(Status.DELETED);
            Mockito.verify(strategy).withdrawSocialAccount(null, oauthId);
        }
    }

    @Test
    @DisplayName("보호자 재발급 진입점이 쓰기 트랜잭션으로 버전을 검증하고 토큰을 교체한다")
    void 보호자_상위_진입점에서도_잠금과_재발급을_수행한다() {
        // given
        redisStorage();
        Long id = createMember();
        localAccounts.saveAndFlush(LocalAccount.createLocalAccount(members.findById(id).orElseThrow(), id + "@refresh.example", "encoded"));
        given(memberUtil.getMemberByMemberId(id)).willReturn(members.findById(id).orElseThrow());
        TokenPairResponse pair = tokens.generateTokenPair(id, MemberRole.USER, "local");
        // when
        TokenPairResponse next = guardianAuth.reissueTokenPair(new com.widyu.auth.dto.request.RefreshTokenRequest(pair.refreshToken()));
        // then
        assertThat(tokens.retrieveAccessToken(next.accessToken()).memberId()).isEqualTo(id);
        assertThat(next.refreshToken()).isNotEqualTo(pair.refreshToken());
    }

    private Long createMember() {
        return members.saveAndFlush(Member.createMember(MemberType.GUARDIAN, "회원", "01000000000")).getId();
    }

    @Test
    @DisplayName("같은 기존회원 임시 인증으로 다른 이메일을 동시에 연동하면 한 요청만 소비하고 새 토큰은 유효하다")
    void 기존회원_임시인증은_다른이메일_동시연동에서도_한번만_소비된다() throws Exception {
        // given
        redisStorage();
        Long id = createMember();
        var temporary = TemporaryMember.createTemporaryMember("회원", "01000000000");
        temporary.bindSession(id, 0L);
        String firstEmail = id + "-first@link.example";
        String secondEmail = id + "-second@link.example";
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        given(passwords.encode("link-password")).willAnswer(call -> {
            firstLocked.countDown();
            await(release);
            return "encoded-link";
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            // 두 요청 모두 Redis에서 같은 임시 데이터를 이미 읽은 상태다.
            var first = executor.submit(() -> localLogin.signupGuardianWithLocal(temporary, firstEmail, "link-password"));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return localLogin.signupGuardianWithLocal(temporary, secondEmail, "link-password");
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // when
            try {
                assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            var issued = first.get(5, TimeUnit.SECONDS);
            // then
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .cause().isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", com.widyu.global.error.ErrorCode.INVALID_TEMPORARY_TOKEN);
            assertThat(members.findById(id).orElseThrow().getAuthVersion()).isEqualTo(1);
            assertThat(localAccounts.existsByEmail(firstEmail)).isTrue();
            assertThat(localAccounts.existsByEmail(secondEmail)).isFalse();
            assertThat(tokens.retrieveAccessToken(issued.accessToken()).memberId()).isEqualTo(id);
            assertThat(jwt.accessVersion(issued.accessToken())).isEqualTo(1);
            assertThat(refresh(issued.refreshToken()).memberId()).isEqualTo(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdraw", "delete"})
    @DisplayName("본인확인했던 기존 회원이 탈퇴하거나 삭제되면 신규 회원 생성으로 전환하지 않는다")
    void 기존회원_임시인증은_탈퇴와_삭제_후_신규가입으로_우회하지_않는다(String action) {
        // given
        Member member = members.saveAndFlush(Member.createMember(MemberType.GUARDIAN, "fallback회원", "01077770000"));
        Long id = member.getId();
        var temporary = TemporaryMember.createTemporaryMember(member.getName(), member.getPhoneNumber());
        temporary.bindSession(id, 0L);
        tx().executeWithoutResult(status -> {
            if (action.equals("delete")) {
                members.deleteById(id);
                return;
            }
            Member withdrawing = sessions.revoke(id);
            withdrawing.maskPersonalInfo();
            withdrawing.withdraw();
        });
        long count = members.count();
        // when / then
        assertThatThrownBy(() -> localLogin.signupGuardianWithLocal(temporary, id + "@fallback.example", "password"))
                .isInstanceOf(BusinessException.class);
        assertThat(members.count()).isEqualTo(count);
        assertThat(localAccounts.existsByEmail(id + "@fallback.example")).isFalse();
    }

    @Test
    @DisplayName("Redis 본인확인 데이터를 복원해도 실제 임시 토큰 사용처가 폐기 버전을 거절한다")
    void 임시토큰_요청_파서는_복원된_Redis_데이터도_재검증한다() {
        // given
        Long id = createMember();
        var temporary = TemporaryMember.createTemporaryMember("회원", "01000000000");
        temporary.bindSession(id, 0L);
        var repository = Mockito.mock(com.widyu.auth.repository.TemporaryMemberRepository.class);
        given(repository.findById(temporary.getId())).willReturn(Optional.of(temporary));
        var util = new TemporaryMemberUtil(jwt, repository, sessions);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.generateTemporaryToken(temporary).temporaryToken());
        assertThat(util.getTemporaryMemberFromRequest(request).getAuthVersion()).isZero();
        // when
        sessions.revoke(id);
        // then
        assertThatThrownBy(() -> util.getTemporaryMemberFromRequest(request)).isInstanceOf(BusinessException.class);
    }

    private ConcurrentHashMap<Long, RefreshToken> redisStorage() {
        var values = new ConcurrentHashMap<Long, RefreshToken>();
        given(refreshTokens.save(any(RefreshToken.class))).willAnswer(call -> {
            RefreshToken token = call.getArgument(0);
            values.put(token.getMemberId(), token);
            return token;
        });
        given(refreshTokens.findById(any(Long.class))).willAnswer(call -> Optional.ofNullable(values.get(call.getArgument(0))));
        return values;
    }

    private TokenPairResponse refresh(String refresh) {
        return tx().execute(status -> {
            var parsed = tokens.retrieveRefreshToken(refresh);
            return tokens.generateTokenPair(parsed.memberId(), MemberRole.USER, "local");
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
