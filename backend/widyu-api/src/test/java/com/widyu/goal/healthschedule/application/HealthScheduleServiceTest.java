package com.widyu.goal.healthschedule.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.widyu.fcm.NotificationType;
import com.widyu.goal.event.GuardianGoalChangedEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateForSeniorRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleCreateRequest;
import com.widyu.goal.healthschedule.dto.request.HealthScheduleUpdateRequest;
import com.widyu.goal.healthschedule.repository.HealthScheduleRepository;
import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthScheduleService 예외 처리 단위 테스트")
class HealthScheduleServiceTest {

    @Mock private HealthScheduleRepository healthScheduleRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private HealthScheduleService healthScheduleService;

    @Test
    @DisplayName("시니어가 자기 건강일정을 등록하면 보호자 변경 이벤트를 발행하지 않는다")
    void 시니어가_자기_건강일정을_등록하면_이벤트를_발행하지_않는다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        HealthSchedule saved = schedule(senior);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.save(any(HealthSchedule.class))).willReturn(saved);
        HealthScheduleCreateRequest request = new HealthScheduleCreateRequest(
                "병원 방문", "서울시", 37.5, 127.0, LocalDateTime.now().plusDays(1));

        // when
        healthScheduleService.createHealthScheduleForMe(request);

        // then
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("보호자가 시니어 건강일정을 등록하면 H02 이벤트를 한 번 발행한다")
    void 보호자가_시니어_건강일정을_등록하면_H02_이벤트를_발행한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(10L, senior);
        HealthSchedule saved = schedule(senior);
        ReflectionTestUtils.setField(saved, "id", 100L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(profile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);
        given(healthScheduleRepository.save(any(HealthSchedule.class))).willReturn(saved);

        // when
        healthScheduleService.createHealthScheduleForSenior(createRequest(2L));

        // then
        ArgumentCaptor<GuardianGoalChangedEvent> events = ArgumentCaptor.forClass(GuardianGoalChangedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(events.capture());
        assertThat(events.getValue().seniorId()).isEqualTo(2L);
        assertThat(events.getValue().type()).isEqualTo(NotificationType.HEALTH_SCHEDULE_CREATED);
        assertThat(events.getValue().entityId()).isEqualTo("100");
    }

    @Test
    @DisplayName("보호자가 시니어 건강일정을 변경하면 H03 이벤트를 한 번 발행한다")
    void 보호자가_시니어_건강일정을_변경하면_H03_이벤트를_발행한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(10L, senior);
        HealthSchedule schedule = schedule(senior);
        ReflectionTestUtils.setField(schedule, "id", 100L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(healthScheduleRepository.findById(100L)).willReturn(Optional.of(schedule));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(profile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);

        // when
        healthScheduleService.updateHealthSchedule(100L, updateRequest());

        // then
        assertThat(schedule.getScheduleName()).isEqualTo("수정");
        ArgumentCaptor<GuardianGoalChangedEvent> events = ArgumentCaptor.forClass(GuardianGoalChangedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(events.capture());
        assertThat(events.getValue().seniorId()).isEqualTo(2L);
        assertThat(events.getValue().type()).isEqualTo(NotificationType.HEALTH_SCHEDULE_UPDATED);
        assertThat(events.getValue().entityId()).isEqualTo("100");
    }

    @Test
    @DisplayName("보호자가 시니어 건강일정을 삭제하면 H04 이벤트를 한 번 발행한다")
    void 보호자가_시니어_건강일정을_삭제하면_H04_이벤트를_발행한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile profile = seniorProfile(10L, senior);
        HealthSchedule schedule = schedule(senior);
        ReflectionTestUtils.setField(schedule, "id", 100L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(healthScheduleRepository.findById(100L)).willReturn(Optional.of(schedule));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(profile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);

        // when
        healthScheduleService.deleteHealthSchedule(100L);

        // then
        then(healthScheduleRepository).should(times(1)).delete(schedule);
        ArgumentCaptor<GuardianGoalChangedEvent> events = ArgumentCaptor.forClass(GuardianGoalChangedEvent.class);
        then(eventPublisher).should(times(1)).publishEvent(events.capture());
        assertThat(events.getValue().type()).isEqualTo(NotificationType.HEALTH_SCHEDULE_DELETED);
        assertThat(events.getValue().entityId()).isEqualTo("100");
        assertThat(events.getValue().seniorId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("시니어가 자기 건강일정을 변경하고 삭제하면 보호자 변경 이벤트를 발행하지 않는다")
    void 시니어가_자기_건강일정을_변경하고_삭제하면_이벤트를_발행하지_않는다() {
        // given
        Member senior = member(2L, MemberType.SENIOR);
        HealthSchedule schedule = schedule(senior);
        ReflectionTestUtils.setField(schedule, "id", 100L);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        given(healthScheduleRepository.findById(100L)).willReturn(Optional.of(schedule));

        // when
        healthScheduleService.updateHealthSchedule(100L, updateRequest());
        healthScheduleService.deleteHealthSchedule(100L);

        // then
        assertThat(schedule.getScheduleName()).isEqualTo("수정");
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("보호자가 존재하지 않는 시니어 일정 생성 시 BAD_REQUEST 예외를 던지고 저장하지 않는다")
    void 존재하지_않는_시니어_일정_생성_시_예외가_발생한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> healthScheduleService.createHealthScheduleForSenior(createRequest(2L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("시니어를 찾을 수 없습니다.");
        then(healthScheduleRepository).should(never()).save(any(HealthSchedule.class));
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("보호자가 프로필 없는 시니어 일정 생성 시 SENIOR_PROFILE_NOT_FOUND 예외를 던지고 저장하지 않는다")
    void 프로필_없는_시니어_일정_생성_시_예외가_발생한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> healthScheduleService.createHealthScheduleForSenior(createRequest(2L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENIOR_PROFILE_NOT_FOUND)
                .hasMessageContaining("시니어 프로필을 찾을 수 없습니다.");
        then(healthScheduleRepository).should(never()).save(any(HealthSchedule.class));
    }

    @Test
    @DisplayName("연결되지 않은 보호자가 시니어 일정 생성 시 FORBIDDEN 예외를 던지고 저장하지 않는다")
    void 연결되지_않은_보호자_일정_생성_시_예외가_발생한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile seniorProfile = seniorProfile(10L, senior);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(memberRepository.findById(2L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> healthScheduleService.createHealthScheduleForSenior(createRequest(2L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("해당 시니어의 일정을 생성할 권한이 없습니다.");
        then(healthScheduleRepository).should(never()).save(any(HealthSchedule.class));
    }

    @Test
    @DisplayName("시니어가 타인 일정 수정 시 FORBIDDEN 예외를 던진다")
    void 시니어가_타인_일정_수정_시_예외가_발생한다() {
        // given
        Member currentSenior = member(1L, MemberType.SENIOR);
        Member otherSenior = member(2L, MemberType.SENIOR);
        HealthSchedule schedule = schedule(otherSenior);
        given(memberUtil.getCurrentMember()).willReturn(currentSenior);
        given(healthScheduleRepository.findById(100L)).willReturn(Optional.of(schedule));

        // when & then
        assertThatThrownBy(() -> healthScheduleService.updateHealthSchedule(100L, updateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("해당 일정에 접근할 권한이 없습니다.");
    }

    @Test
    @DisplayName("보호자가 연결되지 않은 시니어의 날짜별 일정 조회 시 FORBIDDEN 예외를 던진다")
    void 보호자가_연결되지_않은_시니어_날짜별_조회_시_예외가_발생한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Member senior = member(2L, MemberType.SENIOR);
        SeniorProfile seniorProfile = seniorProfile(10L, senior);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> healthScheduleService.getHealthSchedulesByDateForSenior(2L, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("해당 시니어의 일정을 조회할 권한이 없습니다.");
    }

    private HealthScheduleCreateForSeniorRequest createRequest(Long memberId) {
        return new HealthScheduleCreateForSeniorRequest(
                memberId, "병원 방문", "서울시 강남구", 37.5, 127.0, LocalDateTime.now().plusDays(1));
    }

    private HealthScheduleUpdateRequest updateRequest() {
        return new HealthScheduleUpdateRequest("수정", "서울시 서초구", 37.4, 127.1,
                LocalDateTime.now().plusDays(2), ProgressStatus.UPCOMING);
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, type.name(), "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private SeniorProfile seniorProfile(Long id, Member member) {
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(seniorProfile, "id", id);
        ReflectionTestUtils.setField(member, "seniorProfile", seniorProfile);
        return seniorProfile;
    }

    private HealthSchedule schedule(Member member) {
        return HealthSchedule.create(member, "병원 방문", "서울시", 37.5, 127.0, LocalDateTime.now().plusDays(1));
    }
}
