package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.MemberNotificationSetting;
import com.widyu.fcm.PushSettingGroup;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** 스냅샷 원천 조회는 JPQL 조건이 곧 수신자 규칙이라 H2에서 실제로 실행해 검증한다. Redis는 mock이다. */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("긴급 수신자 스냅샷 갱신 테스트")
class EmergencyRecipientSnapshotServiceTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private FamilyMembershipRepository memberships;
    @Autowired private SeniorProfileRepository seniors;
    @Autowired private TestEntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("스냅샷을 만들면 탈퇴 회원, 비활성 토큰, 안전 알림을 끈 보호자를 빼고 방장은 설정과 무관하게 넣는다")
    void 스냅샷을_만들면_받을_수_없는_보호자를_뺀다() throws Exception {
        // given
        Family family = entityManager.persist(Family.createFamily("SNP001"));
        Member senior = senior(family, "김시니어");
        Member leader = guardian(family, true);
        MemberFcmToken leaderToken = token(leader, "leader-token", true);
        entityManager.persist(MemberNotificationSetting.create(leader, PushSettingGroup.SAFETY, false));
        Member member = guardian(family, false);
        MemberFcmToken memberToken = token(member, "member-token", true);
        Member optedOut = guardian(family, false);
        token(optedOut, "opted-out-token", true);
        entityManager.persist(MemberNotificationSetting.create(optedOut, PushSettingGroup.SAFETY, false));
        Member withdrawn = guardian(family, false);
        token(withdrawn, "withdrawn-token", true);
        withdrawn.withdraw();
        Member loggedOut = guardian(family, false);
        token(loggedOut, "logged-out-token", false);
        entityManager.flush();
        entityManager.clear();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(values);

        // when
        service(redis).refreshFamily(family.getId());

        // then
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        then(values).should().set(
                eq("fcm:degraded:recipients:" + senior.getId()),
                json.capture(), eq(Duration.ofHours(24)));
        EmergencyRecipientSnapshot snapshot = new ObjectMapper().readValue(json.getValue(),
                EmergencyRecipientSnapshot.class);
        assertThat(snapshot.seniorName()).isEqualTo("김시니어");
        assertThat(snapshot.recipients()).extracting(EmergencyRecipientSnapshot.Recipient::tokenId)
                .containsExactlyInAnyOrder(leaderToken.getId(), memberToken.getId());
        assertThat(snapshot.recipients()).extracting(EmergencyRecipientSnapshot.Recipient::guardianId)
                .containsExactlyInAnyOrder(leader.getId(), member.getId());
    }

    @Test
    @DisplayName("받을 보호자가 없는 시니어와 탈퇴한 시니어는 낡은 스냅샷이 남지 않게 키를 지운다")
    void 받을_보호자가_없는_시니어의_키를_지운다() {
        // given
        Family alone = entityManager.persist(Family.createFamily("SNP002"));
        Member lonelySenior = senior(alone, "홀로시니어");
        Family withdrawnFamily = entityManager.persist(Family.createFamily("SNP003"));
        Member withdrawnSenior = senior(withdrawnFamily, "탈퇴시니어");
        token(guardian(withdrawnFamily, true), "guardian-of-withdrawn", true);
        withdrawnSenior.withdraw();
        entityManager.flush();
        entityManager.clear();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        // when
        service(redis).refreshAll();

        // then
        then(redis).should().delete("fcm:degraded:recipients:" + lonelySenior.getId());
        then(redis).should().delete("fcm:degraded:recipients:" + withdrawnSenior.getId());
        then(redis).should(never()).opsForValue();
    }

    @Test
    @DisplayName("가족 하나만 다시 만들면 그 가족 시니어의 키만 갱신하고 다른 가족 시니어의 키는 건드리지 않는다")
    void 가족_하나만_다시_만들면_다른_가족_키는_건드리지_않는다() {
        // given
        Family target = entityManager.persist(Family.createFamily("SNP005"));
        Member targetSenior = senior(target, "대상시니어");
        token(guardian(target, true), "target-token", true);
        Family other = entityManager.persist(Family.createFamily("SNP006"));
        Member otherSenior = senior(other, "다른시니어");
        token(guardian(other, true), "other-token", true);
        Family empty = entityManager.persist(Family.createFamily("SNP007"));
        Member emptySenior = senior(empty, "빈시니어");
        entityManager.flush();
        entityManager.clear();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(values);

        // when
        service(redis).refreshFamily(target.getId());

        // then
        then(values).should().set(eq("fcm:degraded:recipients:" + targetSenior.getId()), anyString(),
                eq(Duration.ofHours(24)));
        then(values).should(never()).set(eq("fcm:degraded:recipients:" + otherSenior.getId()), anyString(),
                any(Duration.class));
        then(redis).should(never()).delete("fcm:degraded:recipients:" + emptySenior.getId());
        then(redis).should(never()).delete("fcm:degraded:recipients:" + otherSenior.getId());
    }

    @Test
    @DisplayName("Redis에 쓰지 못해도 예외를 밖으로 내지 않아 다음 주기에 다시 만든다")
    void Redis에_쓰지_못해도_예외를_밖으로_내지_않는다() {
        // given
        Family family = entityManager.persist(Family.createFamily("SNP004"));
        senior(family, "시니어");
        entityManager.flush();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        given(redis.delete(anyString())).willThrow(new DataAccessResourceFailureException("redis down"));

        // when / then
        assertThatCode(() -> service(redis).refreshFamily(family.getId())).doesNotThrowAnyException();
    }

    private EmergencyRecipientSnapshotService service(StringRedisTemplate redis) {
        return new EmergencyRecipientSnapshotService(memberships, seniors, redis, new ObjectMapper(),
                new FcmDegradedProperties(true, Duration.ofHours(24), Duration.ofSeconds(60), 10000L, 100),
                transactionManager);
    }

    private Member senior(Family family, String name) {
        int seq = SEQ.incrementAndGet();
        Member senior = entityManager.persist(Member.createMember(MemberType.SENIOR, name, phone(seq)));
        entityManager.persist(SeniorProfile.createSeniorProfile(
                senior, family, "서울시", String.format("S%06d", seq), LocalDate.of(1950, 1, 1)));
        return senior;
    }

    private Member guardian(Family family, boolean leader) {
        Member guardian = entityManager.persist(
                Member.createMember(MemberType.GUARDIAN, "보호자", phone(SEQ.incrementAndGet())));
        FamilyMembership membership = FamilyMembership.createMembership(family, guardian);
        membership.setLeader(leader);
        entityManager.persist(membership);
        return guardian;
    }

    private MemberFcmToken token(Member member, String token, boolean active) {
        return entityManager.persist(MemberFcmToken.builder().member(member).token(token).active(active).build());
    }

    private static String phone(int seq) {
        return String.format("010%08d", seq);
    }
}
