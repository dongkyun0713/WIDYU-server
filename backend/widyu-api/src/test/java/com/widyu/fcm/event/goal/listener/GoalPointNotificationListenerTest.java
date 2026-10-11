package com.widyu.fcm.event.goal.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.goal.dto.GoalAchievedEvent;
import com.widyu.fcm.event.point.dto.PointChangedEvent;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistory;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GoalPointNotificationListenerTest {

    @Mock private FcmOutboxService outboxService;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @InjectMocks private GoalPointNotificationListener listener;

    @Test
    @DisplayName("포인트와 목표 사건 키를 만들면 지정된 형식을 지키고 40자 이내로 만든다")
    void 포인트와_목표_사건_키를_만들면_형식과_길이를_지킨다() {
        PointHistory earned = PointHistory.earn(null, 10L, "적립");
        PointHistory used = PointHistory.use(null, 50L, "사용");
        ReflectionTestUtils.setField(earned, "id", Long.MAX_VALUE);
        ReflectionTestUtils.setField(used, "id", Long.MAX_VALUE);
        List<String> eventIds = List.of(
                PointChangedEvent.from(1L, earned).eventId(),
                PointChangedEvent.from(1L, used).eventId(),
                GoalAchievedEvent.forWalk(1L, Long.MAX_VALUE, 25L).eventId(),
                GoalAchievedEvent.forHealthSchedule(1L, Long.MAX_VALUE, 100L).eventId(),
                GoalAchievedEvent.forMedicationDay(1L, Long.MAX_VALUE, 30L).eventId());

        assertThat(eventIds).containsExactly(
                "POINT:E:" + Long.MAX_VALUE, "POINT:U:" + Long.MAX_VALUE,
                "G01:W:" + Long.MAX_VALUE, "G01:H:" + Long.MAX_VALUE, "G01:M:" + Long.MAX_VALUE);
        assertThat(eventIds).allMatch(id -> id.length() <= 40);
    }

    @Test
    @DisplayName("일반 포인트를 적립하면 시니어 센터에만 P01을 기록한다")
    void 일반_포인트를_적립하면_센터에만_P01을_기록한다() {
        // given
        PointChangedEvent event = new PointChangedEvent(1L, PointHistoryType.EARN,
                10L, "약 복용 인증", "POINT:E:9");
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.onPointChanged(event);

        // then
        then(outboxService).should().enqueue(org.mockito.ArgumentMatchers.eq(1L), message.capture());
        assertThat(message.getValue().notificationType()).isEqualTo(NotificationType.POINT_EARNED);
        assertThat(message.getValue().notificationType().deliveryMode()).isEqualTo(DeliveryMode.CENTER_ONLY);
        assertThat(message.getValue().title()).isEqualTo("10P를 받았어요.");
        assertThat(message.getValue().content()).isEqualTo("약 복용 인증");
        assertThat(message.getValue().deepLink()).isEqualTo("widyu://points");
        assertThat(message.getValue().eventId()).isEqualTo("POINT:E:9");
    }

    @Test
    @DisplayName("앨범 해금 포인트를 차감하면 시니어 센터에만 P02를 기록한다")
    void 앨범_해금_포인트를_차감하면_센터에만_P02를_기록한다() {
        // given
        PointChangedEvent event = new PointChangedEvent(1L, PointHistoryType.USE,
                50L, "앨범 해금", "POINT:U:10");
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.onPointChanged(event);

        // then
        then(outboxService).should().enqueue(org.mockito.ArgumentMatchers.eq(1L), message.capture());
        assertThat(message.getValue().notificationType()).isEqualTo(NotificationType.POINT_USED);
        assertThat(message.getValue().notificationType().deliveryMode()).isEqualTo(DeliveryMode.CENTER_ONLY);
        assertThat(message.getValue().title()).isEqualTo("50P를 사용했어요.");
        assertThat(message.getValue().content()).isEqualTo("앨범 해금");
        assertThat(message.getValue().deepLink()).isEqualTo("widyu://points");
    }

    @Test
    @DisplayName("걷기 목표를 달성하면 시니어와 같은 가족 보호자에게 G01을 기록한다")
    void 걷기_목표를_달성하면_가족에게_G01을_기록한다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "부모님");
        Member guardian = member(2L, MemberType.GUARDIAN, "보호자");
        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 3L);
        SeniorProfile profile = SeniorProfile.createSeniorProfile(
                senior, family, "서울", "INV1234", LocalDate.of(1950, 1, 1));
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(profile));
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(3L))
                .willReturn(List.of(FamilyMembership.createMembership(family, guardian)));
        ArgumentCaptor<Long> recipient = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<FcmSendDto> message = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.onGoalAchieved(GoalAchievedEvent.forWalk(1L, 7L, 25L));

        // then
        then(outboxService).should(org.mockito.Mockito.times(2)).enqueue(recipient.capture(), message.capture());
        assertThat(recipient.getAllValues()).containsExactly(1L, 2L);
        assertThat(message.getAllValues()).allMatch(item -> item.notificationType() == NotificationType.GOAL_ACHIEVED);
        assertThat(message.getAllValues()).allMatch(item -> item.eventId().equals("G01:W:7"));
        assertThat(message.getAllValues()).allMatch(item -> item.content().equals("25P가 자동으로 적립됐어요."));
        assertThat(message.getAllValues().get(0).title()).isEqualTo("걷기 목표를 달성했어요!");
        assertThat(message.getAllValues().get(0).dataForEnqueue("G01:W:7"))
                .containsEntry("deepLink", "widyu://goals/7");
        assertThat(message.getAllValues().get(1).title()).isEqualTo("부모님 님이 걷기 목표를 달성했어요!");
        assertThat(message.getAllValues().get(1).relatedMemberId()).isEqualTo(1L);
        assertThat(message.getAllValues().get(1).deepLink())
                .isEqualTo("/goal/medicine?seniorId=1");
        assertThat(message.getAllValues().get(1).dataForEnqueue("G01:W:7"))
                .containsEntry("deepLink", "/goal/medicine?seniorId=1");
    }

    private Member member(Long id, MemberType type, String name) {
        Member member = Member.createMember(type, name, "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
