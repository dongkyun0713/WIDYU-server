package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/** H2가 실제로 던지는 무결성 예외로 심박 UK 판별을 확인한다. MySQL은 {@code HeartBatchResendMySqlTest}가 본다(LLD-0089). */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("심박 UK 충돌 판별 H2 테스트")
class HeartEventConflictTest {

    private static final LocalDateTime MEASURED_AT = LocalDateTime.of(2026, 10, 19, 9, 0, 1);

    @Autowired private HeartRateEventRepository heartRateEventRepository;
    @Autowired private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("같은 회원·측정 시각의 심박 행을 다시 저장하면 심박 UK 충돌로 판별한다")
    void 같은_시각_심박을_다시_저장하면_심박_UK_충돌로_판별한다() {
        // given
        Member member = persistSenior("01011112222");
        heartRateEventRepository.saveAndFlush(event(member));

        // when
        DataIntegrityViolationException error = catchThrowableOfType(
                () -> heartRateEventRepository.saveAndFlush(event(member)), DataIntegrityViolationException.class);

        // then
        assertThat(HeartEventConflict.violatesMemberTimeUk(error)).isTrue();
    }

    @Test
    @DisplayName("이름이 비슷한 위급 행 UK에 걸리면 심박 UK 충돌로 보지 않는다")
    void 위급_행_UK에_걸리면_심박_UK_충돌로_보지_않는다() {
        // given
        Member member = persistSenior("01033334444");
        heartRateEmergencyRepository.saveAndFlush(HeartRateEmergency.of(member, 182, MEASURED_AT, null));

        // when
        DataIntegrityViolationException error = catchThrowableOfType(
                () -> heartRateEmergencyRepository.saveAndFlush(HeartRateEmergency.of(member, 182, MEASURED_AT, null)),
                DataIntegrityViolationException.class);

        // then
        // uk_heart_emergency_member_time도 같은 (member_id, measured_at)이다. 이름까지 봐야 둘을 가른다.
        assertThat(HeartEventConflict.violatesMemberTimeUk(error)).isFalse();
    }

    private Member persistSenior(String phoneNumber) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }

    private HeartRateEvent event(Member member) {
        return HeartRateEvent.of(member, 182, MEASURED_AT, HeartRateStatus.EMERGENCY, "HIGH", null);
    }
}
