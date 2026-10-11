package com.widyu.goal.healthschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.location.SeniorLocation;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.SeniorProfileService;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("방문 인증 완료 건강 일정 수정·삭제 잠금 H2 통합 테스트")
class HealthScheduleEditLockIntegrationTest {

    @Autowired private HealthScheduleRepository schedules;
    @Autowired private MemberRepository members;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private JPAQueryFactory queryFactory;

    @Test
    @DisplayName("수동 방문 인증을 먼저 커밋하면 뒤따른 수정은 409이고 완료 기록을 유지한다")
    void 수동_방문_인증을_먼저_커밋하면_수정은_409이고_완료_기록을_유지한다() {
        // given
        Long[] ids = saveSchedule("01010000001");
        Member senior = new TransactionTemplate(transactionManager).execute(status ->
                members.findById(ids[0]).orElseThrow());
        completeAndCommit(ids[1], senior);
        HealthScheduleService service = editService(senior);
        HealthScheduleUpdateRequest request = new HealthScheduleUpdateRequest(
                "다른 일정", "다른 장소", 37.4, 127.1, LocalDateTime.now().plusDays(1), ProgressStatus.UPCOMING);

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.updateHealthSchedule(ids[1], request)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HEALTH_SCHEDULE_EDIT_LOCKED);
        HealthSchedule stored = new TransactionTemplate(transactionManager).execute(status ->
                schedules.findById(ids[1]).orElseThrow());
        assertThat(stored.getScheduleName()).isEqualTo("정기검진");
        assertThat(stored.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        assertThat(stored.getIsReward()).isTrue();
        assertThat(stored.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("수동 방문 인증을 먼저 커밋하면 뒤따른 삭제는 409이고 완료 행을 유지한다")
    void 수동_방문_인증을_먼저_커밋하면_삭제는_409이고_완료_행을_유지한다() {
        // given
        Long[] ids = saveSchedule("01010000002");
        Member senior = new TransactionTemplate(transactionManager).execute(status ->
                members.findById(ids[0]).orElseThrow());
        completeAndCommit(ids[1], senior);
        HealthScheduleService service = editService(senior);

        // when & then
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.deleteHealthSchedule(ids[1])))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HEALTH_SCHEDULE_EDIT_LOCKED);
        HealthSchedule stored = new TransactionTemplate(transactionManager).execute(status ->
                schedules.findById(ids[1]).orElseThrow());
        assertThat(stored.getProgressStatus()).isEqualTo(ProgressStatus.COMPLETED);
        assertThat(stored.getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("인증 전 당일 일정을 먼저 삭제하면 행은 논리 삭제되고 수동 완료는 일정을 찾지 못한다")
    void 인증_전_당일_일정을_먼저_삭제하면_논리_삭제되고_수동_완료는_거부된다() {
        // given
        Long[] ids = saveSchedule("01010000003");
        Member senior = new TransactionTemplate(transactionManager).execute(status ->
                members.findById(ids[0]).orElseThrow());
        HealthScheduleService service = editService(senior);

        // when
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.deleteHealthSchedule(ids[1]));

        // then
        assertThat(schedules.findById(ids[1])).isEmpty();
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM health_schedule WHERE health_schedule_id = ?", String.class, ids[1]);
        assertThat(storedStatus).isEqualTo("DELETED");
        assertThatThrownBy(() -> completeAndCommit(ids[1], senior))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
    }

    private Long[] saveSchedule(String phone) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Member senior = members.save(Member.createMember(MemberType.SENIOR, "부모님", phone));
            HealthSchedule schedule = schedules.save(HealthSchedule.create(
                    senior, "정기검진", "서울시", 37.5, 127.0, LocalDateTime.now()));
            return new Long[] {senior.getId(), schedule.getId()};
        });
    }

    private void completeAndCommit(Long scheduleId, Member senior) {
        MemberUtil memberUtil = mock(MemberUtil.class);
        SeniorLocationRepository locations = mock(SeniorLocationRepository.class);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(locations.findBySeniorId(senior.getId())).willReturn(
                Optional.of(SeniorLocation.of(senior.getId(), 37.5, 127.0)));
        HealthScheduleProgressService progress = new HealthScheduleProgressService(
                schedules, locations, mock(SeniorProfileRepository.class),
                mock(FamilyMembershipRepository.class), mock(SeniorProfileService.class),
                memberUtil, mock(ApplicationEventPublisher.class));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                progress.completeSchedule(scheduleId));
    }

    private HealthScheduleService editService(Member senior) {
        MemberUtil memberUtil = mock(MemberUtil.class);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        return new HealthScheduleService(schedules, members, mock(SeniorProfileRepository.class),
                mock(FamilyMembershipRepository.class), memberUtil, mock(ApplicationEventPublisher.class));
    }
}
