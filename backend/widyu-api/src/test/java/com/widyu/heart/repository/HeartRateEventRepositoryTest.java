package com.widyu.heart.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateStatus;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("HeartRateEventRepository 정리 쿼리 테스트")
class HeartRateEventRepositoryTest {

    @Autowired private HeartRateEventRepository heartRateEventRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    private Member persistSenior(String phoneNumber) {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }

    private HeartRateEvent persistEvent(Member member, LocalDateTime measuredAt) {
        HeartRateEvent event = HeartRateEvent.of(member, 80, measuredAt, HeartRateStatus.NORMAL);
        entityManager.persistAndFlush(event);
        return event;
    }

    @Test
    @DisplayName("제외 회원을 지정해 정리하면 그 회원의 오래된 이벤트만 남는다")
    void 제외_회원을_지정해_정리하면_그_회원의_오래된_이벤트만_남는다() {
        // given
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        Member exempt = persistSenior("01011112222");
        Member others = persistSenior("01033334444");
        HeartRateEvent exemptOld = persistEvent(exempt, cutoff.minusDays(1));
        HeartRateEvent othersOld = persistEvent(others, cutoff.minusDays(1));
        HeartRateEvent othersRecent = persistEvent(others, cutoff.plusDays(1));

        // when
        int deleted = heartRateEventRepository.deleteByMeasuredAtBeforeAndMemberIdNotIn(cutoff, List.of(exempt.getId()));
        entityManager.clear();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(heartRateEventRepository.findAllById(
                        List.of(exemptOld.getId(), othersOld.getId(), othersRecent.getId())))
                .extracting(HeartRateEvent::getId)
                .containsExactlyInAnyOrder(exemptOld.getId(), othersRecent.getId());
    }

    @Test
    @DisplayName("제외 회원 없이 정리하면 기준 시각 이전 이벤트가 모두 삭제된다")
    void 제외_회원_없이_정리하면_기준_시각_이전_이벤트가_모두_삭제된다() {
        // given
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        Member member = persistSenior("01055556666");
        HeartRateEvent old = persistEvent(member, cutoff.minusDays(1));
        HeartRateEvent recent = persistEvent(member, cutoff.plusDays(1));

        // when
        int deleted = heartRateEventRepository.deleteByMeasuredAtBefore(cutoff);
        entityManager.clear();

        // then
        assertThat(deleted).isEqualTo(1);
        assertThat(heartRateEventRepository.findAllById(List.of(old.getId(), recent.getId())))
                .extracting(HeartRateEvent::getId)
                .containsExactly(recent.getId());
    }
}
