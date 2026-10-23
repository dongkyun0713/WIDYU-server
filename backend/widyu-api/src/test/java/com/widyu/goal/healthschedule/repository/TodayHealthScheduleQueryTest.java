package com.widyu.goal.healthschedule.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.goal.healthschedule.dto.response.TodayHealthScheduleItem;
import com.widyu.healthschedule.HealthSchedule;
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
@DisplayName("홈·목표 화면 오늘 건강 일정 조회 경계 H2 테스트")
class TodayHealthScheduleQueryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 10, 0);
    private static final LocalDateTime TODAY_START = NOW.toLocalDate().atStartOfDay();
    private static final LocalDateTime TOMORROW_START = TODAY_START.plusDays(1);

    @Autowired private HealthScheduleRepository healthScheduleRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("보호자 홈 조회 범위로 조회하면 오늘 00:00 일정은 포함하고 내일 00:00 일정과 삭제된 일정은 뺀다")
    void 보호자_홈_조회_범위는_오늘_00시를_포함하고_내일_00시를_뺀다() {
        // given
        Member senior = persistSchedules();

        // when
        List<TodayHealthScheduleItem> todaySchedules = TodayHealthScheduleItem.listFrom(
                healthScheduleRepository.findByMemberIdAndDate(senior.getId(), TODAY_START, TOMORROW_START), NOW);

        // then
        assertThat(todaySchedules).extracting(TodayHealthScheduleItem::scheduleName)
                .containsExactly("오늘 00시", "오늘 09시30분", "오늘 23시59분59초");
    }

    @Test
    @DisplayName("목표 홈·시니어 홈 조회 범위로 조회하면 오늘 목록은 오늘 일정만 담고 내일 일정은 다음 일정 후보로만 남긴다")
    void 목표_홈_조회_범위는_오늘_목록에서_내일_일정을_뺀다() {
        // given
        Member senior = persistSchedules();

        // when
        List<HealthSchedule> fromToday = healthScheduleRepository
                .findByMemberIdAndWeek(senior.getId(), TODAY_START, NOW.plusMonths(1));
        List<TodayHealthScheduleItem> todaySchedules = TodayHealthScheduleItem.listFrom(fromToday, NOW);

        // then
        assertThat(fromToday).extracting(HealthSchedule::getScheduleName)
                .containsExactly("오늘 00시", "오늘 09시30분", "오늘 23시59분59초", "내일 00시");
        assertThat(todaySchedules).extracting(TodayHealthScheduleItem::scheduleName, TodayHealthScheduleItem::elapsed)
                .containsExactly(
                        tuple("오늘 00시", true),
                        tuple("오늘 09시30분", true),
                        tuple("오늘 23시59분59초", false));
    }

    private Member persistSchedules() {
        Member senior = Member.createMember(MemberType.SENIOR, "부모님", "01012340001");
        entityManager.persist(senior);
        persist(senior, "어제 23시59분59초", TODAY_START.minusSeconds(1));
        persist(senior, "오늘 00시", TODAY_START);
        persist(senior, "오늘 09시30분", NOW.withHour(9).withMinute(30));
        persist(senior, "오늘 23시59분59초", TOMORROW_START.minusSeconds(1));
        persist(senior, "내일 00시", TOMORROW_START);
        HealthSchedule deleted = persist(senior, "오늘 삭제됨", NOW.withHour(11));
        entityManager.remove(deleted);
        entityManager.flush();
        entityManager.clear();
        return senior;
    }

    private HealthSchedule persist(Member member, String name, LocalDateTime scheduledAt) {
        return entityManager.persist(HealthSchedule.create(member, name, "서울", 37.5, 127.0, scheduledAt));
    }
}
