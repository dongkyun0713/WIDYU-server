package com.widyu.fcm.event.album.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.DeliveryMode;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.GuardianDeepLinks;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.fcm.event.album.dto.AlbumLikedEvent;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
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
class AlbumNotificationListenerTest {

    @Mock private FcmService fcmService;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AlbumViewRepository albumViewRepository;
    @Mock private AlbumRepository albumRepository;
    @Mock private AlbumUnlockRepository albumUnlockRepository;
    @InjectMocks private AlbumNotificationListener listener;

    @Test
    @DisplayName("보호자가 게시물을 올리면 A01에 보호자 앱 딥링크를 넣고 시니어에게 A02를 보낸다")
    void 보호자가_게시물을_올리면_역할별_알림을_만든다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN, "보호자");
        Member senior = member(2L, MemberType.SENIOR, "시니어");
        SeniorProfile profile = mock(SeniorProfile.class);
        given(profile.getMember()).willReturn(senior);
        given(memberRepository.findById(1L)).willReturn(Optional.of(guardian));
        given(familyMembershipRepository.findFamilyIdByGuardianId(1L)).willReturn(Optional.of(7L));
        given(seniorProfileRepository.findAllByFamilyIdWithMember(7L)).willReturn(List.of(profile));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L));

        // then
        then(fcmService).should().sendMessageToUser(eq(1L), captor.capture());
        FcmSendDto upload = captor.getValue();
        assertThat(upload.notificationType()).isEqualTo(NotificationType.ALBUM_UPLOAD_COMPLETE);
        assertThat(upload.notificationType().deliveryMode()).isEqualTo(DeliveryMode.PUSH_ONLY);
        assertThat(upload.title()).isEqualTo("게시물 업로드가 완료됐어요!");
        assertThat(upload.deepLink()).isEqualTo(GuardianDeepLinks.post("10"));
        assertThat(upload.dataForEnqueue("event-1"))
                .containsEntry("deepLink", "/post?postId=10");
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        FcmSendDto created = captor.getValue();
        assertThat(created.notificationType()).isEqualTo(NotificationType.ALBUM_CREATED);
        assertThat(created.title()).isEqualTo("보호자 님이 게시물을 올렸어요!");
        assertThat(created.dataForEnqueue("event-2")).containsEntry("deepLink", "widyu://albums/10");
    }

    @Test
    @DisplayName("시니어가 게시물을 올리면 보호자에게 A02-C와 보호자 앱 딥링크를 만든다")
    void 시니어가_게시물을_올리면_보호자_앱_알림을_만든다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member guardian = member(2L, MemberType.GUARDIAN, "보호자");
        FamilyMembership membership = mock(FamilyMembership.class);
        given(membership.getGuardian()).willReturn(guardian);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(seniorProfileRepository.findFamilyIdByMemberId(1L)).willReturn(Optional.of(7L));
        given(familyMembershipRepository.findAllByFamilyIdWithGuardian(7L)).willReturn(List.of(membership));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L));

        // then
        then(fcmService).should().sendMessageToUser(eq(1L), captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo(NotificationType.ALBUM_UPLOAD_COMPLETE);
        assertThat(captor.getValue().dataForEnqueue("event-1").get("deepLink"))
                .isEqualTo("widyu://albums/10");
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("시니어 님이 새로운 소식을 전했어요!");
        assertThat(captor.getValue().seniorId()).isEqualTo(1L);
        assertThat(captor.getValue().dataForEnqueue("event-3").get("deepLink"))
                .isEqualTo(GuardianDeepLinks.post("10"));
    }

    @Test
    @DisplayName("댓글과 답글을 만들면 종류와 댓글 딥링크를 구분한다")
    void 댓글과_답글을_만들면_종류와_딥링크를_구분한다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member guardian = member(2L, MemberType.GUARDIAN, "보호자");
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(memberRepository.findById(2L)).willReturn(Optional.of(guardian));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 2L, 20L, null));
        listener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 2L, 21L, 20L));

        // then
        then(fcmService).should(org.mockito.Mockito.times(2)).sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getAllValues()).extracting(FcmSendDto::notificationType)
                .containsExactly(NotificationType.ALBUM_COMMENTED, NotificationType.ALBUM_REPLIED);
        assertThat(captor.getAllValues().get(0).dataForEnqueue("e-1").get("deepLink"))
                .isEqualTo(GuardianDeepLinks.postComment("10", "20"));
        assertThat(captor.getAllValues().get(1).dataForEnqueue("e-2").get("deepLink"))
                .isEqualTo(GuardianDeepLinks.postComment("10", "21"));
        assertThat(captor.getAllValues().get(1).title()).isEqualTo("시니어 님이 댓글에 답글을 달았어요!");
    }

    @Test
    @DisplayName("작성자가 자기 게시물에 댓글을 달면 알림을 만들지 않는다")
    void 작성자가_자기_게시물에_댓글을_달면_알림을_만들지_않는다() {
        // given
        Member member = member(1L, MemberType.GUARDIAN, "보호자");
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        // when
        listener.handleAlbumCommented(new AlbumCommentedEvent(10L, 1L, 1L, 20L, null));

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("좋아요를 누르면 작성자 센터 전용 알림을 만든다")
    void 좋아요를_누르면_센터_전용_알림을_만든다() {
        // given
        Member liker = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        given(memberRepository.findById(1L)).willReturn(Optional.of(liker));
        given(memberRepository.findById(2L)).willReturn(Optional.of(writer));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumLiked(new AlbumLikedEvent(10L, 1L, 2L));

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().notificationType().deliveryMode()).isEqualTo(DeliveryMode.CENTER_ONLY);
        assertThat(captor.getValue().title()).isEqualTo("시니어 님이 게시물에 좋아요를 눌렀어요.");
        assertThat(captor.getValue().content()).isNull();
        assertThat(captor.getValue().notificationType().foregroundPresentation()).isEqualTo("NONE");
        assertThat(captor.getValue().dataForEnqueue("event-like"))
                .containsEntry("deepLink", "/post?postId=10");
    }

    @Test
    @DisplayName("잠금 해제 뒤 남은 게시물이 있으면 A06-L과 남은 수를 전달한다")
    void 잠금_해제_뒤_남은_게시물이_있으면_A06_L을_전달한다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        Album album = album(10L, writer);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(albumRepository.findByIdWithMember(10L)).willReturn(Optional.of(album));
        given(albumUnlockRepository.countRemainingLockedByWriterAndSenior(2L, 1L, 10L)).willReturn(3L);
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumUnlocked(new AlbumUnlockedEvent(10L, 1L));

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("아직 잠금을 해제하지 않은 새 소식이 3개 남았어요.");
        assertThat(captor.getValue().seniorDisplayName()).isEqualTo("시니어");
        assertThat(captor.getValue().remainingLockedCount()).isEqualTo(3);
        assertThat(captor.getValue().entityId()).isEqualTo("10");
        assertThat(captor.getValue().seniorId()).isEqualTo(1L);
        assertThat(captor.getValue().deepLink()).isNull();
        assertThat(captor.getValue().dataForEnqueue("e-3"))
                .containsEntry("deepLink", "/post?postId=10")
                .containsEntry("seniorDisplayName", "시니어")
                .containsEntry("remainingLockedCount", "3");
    }

    @Test
    @DisplayName("잠금 해제 뒤 남은 게시물이 없으면 A06-Z를 쓰고 모두 읽음은 만들지 않는다")
    void 잠금_해제_뒤_남은_게시물이_없으면_A06_Z만_만든다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        Album album = album(10L, writer);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(albumRepository.findByIdWithMember(10L)).willReturn(Optional.of(album));
        given(albumUnlockRepository.countRemainingLockedByWriterAndSenior(2L, 1L, 10L)).willReturn(0L);
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumUnlocked(new AlbumUnlockedEvent(10L, 1L));

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("아직 잠금을 해제하지 않은 새 소식이 없어요.");
        assertThat(captor.getValue().remainingLockedCount()).isZero();
        assertThat(captor.getValue().notificationType()).isEqualTo(NotificationType.ALBUM_UNLOCKED);
        assertThat(captor.getValue().dataForEnqueue("e-4"))
                .containsEntry("remainingLockedCount", "0");
    }

    @Test
    @DisplayName("남은 수 조회에서 DB 오류가 나면 잠금 해제 알림을 만들지 않고 실패한다")
    void 남은_수_조회에서_DB_오류가_나면_알림을_만들지_않고_실패한다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        Album album = album(10L, writer);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(albumRepository.findByIdWithMember(10L)).willReturn(Optional.of(album));
        given(albumUnlockRepository.countRemainingLockedByWriterAndSenior(2L, 1L, 10L))
                .willThrow(new IllegalStateException("count unavailable"));

        // when / then
        assertThatThrownBy(() -> listener.handleAlbumUnlocked(new AlbumUnlockedEvent(10L, 1L)))
                .isInstanceOf(IllegalStateException.class);
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("시니어가 보호자 새 게시물을 모두 읽으면 A07을 전달한다")
    void 시니어가_보호자_새_게시물을_모두_읽으면_A07을_전달한다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        Album album = mock(Album.class);
        given(album.getMember()).willReturn(writer);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE))
                .willReturn(Optional.of(album));
        given(albumViewRepository.countTotalAlbumsByParent(2L)).willReturn(2L);
        given(albumViewRepository.countViewedAlbumsByGuardianAndParent(1L, 2L)).willReturn(2L);
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        listener.handleAlbumViewed(new AlbumViewedEvent(1L, 10L));

        // then
        then(fcmService).should().sendMessageToUser(eq(2L), captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo(NotificationType.ALBUM_ALL_VIEWED);
        assertThat(captor.getValue().title()).isEqualTo("시니어 님이 새 게시물을 모두 확인했어요!");
        assertThat(captor.getValue().deepLink()).isNull();
        assertThat(captor.getValue().dataForEnqueue("event-view"))
                .containsEntry("deepLink", GuardianDeepLinks.album());
        assertThat(captor.getValue().seniorId()).isEqualTo(1L);
        then(fcmService).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("보호자가 시니어 작성자 게시물을 모두 읽으면 A07을 만들지 않는다")
    void 보호자가_시니어_작성자_게시물을_모두_읽으면_A07을_만들지_않는다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN, "보호자");
        Member seniorWriter = member(2L, MemberType.SENIOR, "시니어");
        Album album = mock(Album.class);
        given(album.getMember()).willReturn(seniorWriter);
        given(memberRepository.findById(1L)).willReturn(Optional.of(guardian));
        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE))
                .willReturn(Optional.of(album));

        // when
        listener.handleAlbumViewed(new AlbumViewedEvent(1L, 10L));

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("시니어가 새 게시물을 일부만 읽으면 모두 읽음 알림을 만들지 않는다")
    void 시니어가_새_게시물을_일부만_읽으면_모두_읽음을_만들지_않는다() {
        // given
        Member senior = member(1L, MemberType.SENIOR, "시니어");
        Member writer = member(2L, MemberType.GUARDIAN, "보호자");
        Album album = mock(Album.class);
        given(album.getMember()).willReturn(writer);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior));
        given(albumRepository.findByIdAndStatusWithCollections(10L, Status.ACTIVE))
                .willReturn(Optional.of(album));
        given(albumViewRepository.countTotalAlbumsByParent(2L)).willReturn(2L);
        given(albumViewRepository.countViewedAlbumsByGuardianAndParent(1L, 2L)).willReturn(1L);

        // when
        listener.handleAlbumViewed(new AlbumViewedEvent(1L, 10L));

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("앨범 생성 이벤트 작성자가 없으면 회원 없음 예외가 발생한다")
    void 앨범_생성_작성자가_없으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> listener.handleAlbumCreated(new AlbumCreatedEvent(10L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND);
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    private Member member(Long id, MemberType type, String name) {
        Member member = Member.createMember(type, name, "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private Album album(Long id, Member writer) {
        Album album = mock(Album.class);
        given(album.getId()).willReturn(id);
        given(album.getMember()).willReturn(writer);
        return album;
    }
}
