package com.widyu.fcm.event.album.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.fcm.event.album.dto.AlbumLikedEvent;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@ExtendWith(MockitoExtension.class)
@DisplayName("AlbumNotificationListener 예외 처리 단위 테스트")
class AlbumNotificationListenerTest {

    @Mock private FcmService fcmService;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AlbumViewRepository albumViewRepository;
    @Mock private AlbumRepository albumRepository;

    @InjectMocks
    private AlbumNotificationListener albumNotificationListener;

    @Test
    @DisplayName("앨범 이벤트 핸들러는 업무 트랜잭션에 참여한다")
    void 앨범_이벤트_핸들러는_업무_트랜잭션에_참여한다() throws NoSuchMethodException {
        // when & then
        assertTransactionalHandler("handleAlbumCreated", AlbumCreatedEvent.class);
        assertTransactionalHandler("handleAlbumViewed", AlbumViewedEvent.class);
        assertTransactionalHandler("handleAlbumCommented", AlbumCommentedEvent.class);
        assertTransactionalHandler("handleAlbumLiked", AlbumLikedEvent.class);
        assertTransactionalHandler("handleAlbumUnlocked", AlbumUnlockedEvent.class);
    }

    @Test
    @DisplayName("앨범 생성 이벤트 작성자가 없으면 NOTIFICATION_MEMBER_NOT_FOUND 예외를 던지고 FCM을 전송하지 않는다")
    void 앨범_생성_작성자가_없으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumNotificationListener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND)
                .hasMessageContaining("회원을 찾을 수 없습니다.");
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("앨범 생성이 완료되면 업로더에게 완료 알림을 전송한다")
    void 앨범_생성_완료_시_업로더에게_완료_알림을_전송한다() {
        // given
        Member author = member(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(author));

        // when
        albumNotificationListener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L));

        // then
        then(fcmService).should().sendMessageToUser(eq(1L), argThat(dto ->
                dto.title().equals("앨범 업로드가 완료되었어요!")
                        && dto.content().equals("업로드한 앨범을 확인해보세요.")
                        && dto.fcmCategory() == FcmCategory.ALBUM
        ));
    }

    @Test
    @DisplayName("댓글 작성자가 없으면 NOTIFICATION_MEMBER_NOT_FOUND 예외를 던지고 FCM을 전송하지 않는다")
    void 댓글_작성자가_없으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumNotificationListener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND)
                .hasMessageContaining("회원을 찾을 수 없습니다.");
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("잠금해제 이벤트 앨범이 없으면 ALBUM_NOT_FOUND 예외를 던지고 FCM을 전송하지 않는다")
    void 잠금해제_앨범이_없으면_예외가_발생한다() {
        // given
        Member parent = member(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(parent));
        given(albumRepository.findByIdWithMember(10L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> albumNotificationListener.handleAlbumUnlocked(new AlbumUnlockedEvent(10L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALBUM_NOT_FOUND)
                .hasMessageContaining("앨범을 찾을 수 없습니다.");
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("댓글 알림 저장이 실패하면 업무 롤백을 위해 예외를 전파한다")
    void 댓글_알림_저장_실패_시_예외를_전파한다() {
        // given
        Member commenter = member(1L);
        Member albumAuthor = member(2L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(commenter));
        given(memberRepository.findById(2L)).willReturn(Optional.of(albumAuthor));
        willThrow(new RuntimeException("fcm failed"))
                .given(fcmService)
                .sendMessageToUser(eq(2L), any(FcmSendDto.class));

        // when & then
        assertThatThrownBy(() -> albumNotificationListener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 2L)))
                .isInstanceOf(RuntimeException.class).hasMessage("fcm failed");
        then(fcmService).should().sendMessageToUser(eq(2L), argThat(dto -> dto.relatedMemberId().equals(1L)));
    }

    @Test
    @DisplayName("가족 알림 저장이 실패하면 예외를 전파하고 후속 저장을 중단한다")
    void 가족_알림_저장_실패_시_예외를_전파한다() {
        // given
        Member author = member(1L);
        FamilyMembership firstMembership = membership(member(2L));
        FamilyMembership secondMembership = mock(FamilyMembership.class);

        given(memberRepository.findById(1L)).willReturn(Optional.of(author));
        given(seniorProfileRepository.findFamilyIdByMemberId(1L)).willReturn(Optional.of(100L));
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(100L))
                .willReturn(List.of(firstMembership, secondMembership));
        willDoNothing().given(fcmService)
                .sendMessageToUser(eq(1L), any(FcmSendDto.class));
        willThrow(new RuntimeException("fcm failed"))
                .given(fcmService)
                .sendMessageToUser(eq(2L), any(FcmSendDto.class));

        // when & then
        assertThatThrownBy(() -> albumNotificationListener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L)))
                .isInstanceOf(RuntimeException.class).hasMessage("fcm failed");
        then(fcmService).should().sendMessageToUser(eq(1L), argThat(dto ->
                dto.title().equals("앨범 업로드가 완료되었어요!")));
        then(fcmService).should().sendMessageToUser(eq(2L), argThat(dto -> dto.relatedMemberId().equals(1L)));
        then(secondMembership).should(never()).getGuardian();
    }

    @Test
    @DisplayName("본인 댓글 이벤트는 FCM을 전송하지 않는다")
    void 본인_댓글_이벤트는_FCM을_전송하지_않는다() {
        // given
        Member member = member(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        // when
        albumNotificationListener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 1L));

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    private void assertTransactionalHandler(String methodName, Class<?> eventType) throws NoSuchMethodException {
        Method method = AlbumNotificationListener.class.getDeclaredMethod(methodName, eventType);
        EventListener listener = method.getAnnotation(EventListener.class);

        assertThat(listener).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
    }

    private FamilyMembership membership(Member guardian) {
        FamilyMembership membership = mock(FamilyMembership.class);
        given(membership.getGuardian()).willReturn(guardian);
        return membership;
    }

    private Member member(Long id) {
        Member member = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
