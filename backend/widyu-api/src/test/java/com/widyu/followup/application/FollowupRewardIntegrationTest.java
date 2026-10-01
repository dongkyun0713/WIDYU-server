package com.widyu.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.FcmOutboxRepository;
import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupCardState;
import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;
import com.widyu.followup.dto.request.FollowupAnswerRequest;
import com.widyu.followup.repository.FollowupAnswerRepository;
import com.widyu.followup.repository.FollowupCardRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.PointHistoryRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "sensor.followup.enabled=true",
        "sensor.followup.reward-enabled=true",
        "firebase.config-path=/dev/null",
        "s3.credentials.access-key=test",
        "s3.credentials.secret-key=test",
        "s3.region.statics=ap-northeast-2",
        "s3.bucket-name=test-bucket",
        "coolsms.api-key=test",
        "coolsms.api-secret=test",
        "coolsms.api-url=https://api.coolsms.co.kr",
        "coolsms.from-phone-number=01000000000",
        "coolsms.verification-code-length=6",
        "coolsms.verification-code-ttl=300",
        "coolsms.message-template=인증번호: {code}"
})
class FollowupRewardIntegrationTest {
    @Autowired private FollowupCardService service;
    @Autowired private FollowupCardRepository cards;
    @Autowired private FollowupAnswerRepository answers;
    @Autowired private MemberRepository members;
    @Autowired private FamilyRepository families;
    @Autowired private SeniorProfileRepository profiles;
    @Autowired private PointHistoryRepository histories;
    @Autowired private FcmNotificationRepository notifications;
    @Autowired private FcmOutboxRepository outbox;

    @MockBean private FcmTransport fcmTransport;
    @MockBean private MemberUtil memberUtil;
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private RefreshTokenRepository refreshTokenRepository;
    @MockBean private S3Client s3Client;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @AfterEach
    void 정리한다() {
        notifications.deleteAll();
        histories.deleteAll();
        answers.deleteAll();
        cards.deleteAll();
        profiles.deleteAll();
        families.deleteAll();
        members.deleteAll();
    }

    @Test
    @DisplayName("첫 답변을 제출하면 10P와 P01을 한 건씩 남기고 재제출하면 상태를 유지한다")
    void 첫_답변을_제출하면_포인트와_P01을_남기고_재제출하면_상태를_유지한다() {
        // given
        Member senior = seniorWithProfile("01098110201", "F15101", "I151001");
        FollowupCard card = card(senior.getId(), "inc-reward-answer");
        FollowupAnswerRequest request = new FollowupAnswerRequest("HR_V1", FollowupQ1.DONT_KNOW,
                FollowupQ2.REFUSE, null, System.currentTimeMillis());
        long startingPoints = profiles.findByMemberId(senior.getId()).orElseThrow().getPoints();

        // when
        service.answer(senior.getId(), card.getId(), request);

        // then
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.ANSWERED);
        assertThat(answers.count()).isEqualTo(1);
        assertThat(profiles.findByMemberId(senior.getId()).orElseThrow().getPoints())
                .isEqualTo(startingPoints + 10);
        assertThat(histories.findAll()).singleElement().satisfies(history -> {
            assertThat(history.getType()).isEqualTo(PointHistoryType.EARN);
            assertThat(history.getAmount()).isEqualTo(10L);
            assertThat(history.getOperationKey()).isEqualTo("FOLLOWUP:" + card.getId());
        });
        assertThat(notifications.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.POINT_EARNED);
            assertThat(notification.getRecipientMember().getId()).isEqualTo(senior.getId());
            assertThat(notification.getBody()).isEqualTo("후속 질문 답변");
        });
        assertThat(outbox.count()).isZero();

        // when / then: 중복 요청은 커밋된 상태를 바꾸지 않는다.
        assertThatThrownBy(() -> service.answer(senior.getId(), card.getId(), request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FOLLOWUP_ALREADY_SUBMITTED);
        assertThat(profiles.findByMemberId(senior.getId()).orElseThrow().getPoints())
                .isEqualTo(startingPoints + 10);
        assertThat(histories.count()).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(outbox.count()).isZero();
    }

    @Test
    @DisplayName("첫 전체 거절을 제출하면 원답 내용 없이 10P와 P01을 남긴다")
    void 첫_전체_거절을_제출하면_원답_내용_없이_포인트와_P01을_남긴다() {
        // given
        Member senior = seniorWithProfile("01098110202", "F15102", "I151002");
        FollowupCard card = card(senior.getId(), "inc-reward-decline");

        // when
        service.decline(senior.getId(), card.getId(), System.currentTimeMillis());

        // then
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.DECLINED);
        assertThat(answers.findAll()).singleElement().satisfies(answer -> {
            assertThat(answer.getQ1()).isNull();
            assertThat(answer.getQ2()).isNull();
        });
        assertThat(profiles.findByMemberId(senior.getId()).orElseThrow().getPoints()).isEqualTo(110L);
        assertThat(histories.findAll()).singleElement().satisfies(history -> {
            assertThat(history.getAmount()).isEqualTo(10L);
            assertThat(history.getOperationKey()).isEqualTo("FOLLOWUP:" + card.getId());
        });
        assertThat(notifications.findAll()).singleElement().satisfies(notification ->
                assertThat(notification.getType()).isEqualTo(NotificationType.POINT_EARNED));
        assertThat(outbox.count()).isZero();
    }

    @Test
    @DisplayName("보상 대상에게 시니어 프로필이 없으면 카드 전이와 원답도 롤백한다")
    void 보상_대상에게_시니어_프로필이_없으면_카드_전이와_원답도_롤백한다() {
        // given
        Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "01098110203"));
        FollowupCard card = card(senior.getId(), "inc-reward-no-profile");

        // when / then
        assertThatThrownBy(() -> service.answer(senior.getId(), card.getId(),
                new FollowupAnswerRequest("HR_V1", FollowupQ1.YES, FollowupQ2.NEEDED,
                        null, System.currentTimeMillis())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SENIOR_PROFILE_NOT_FOUND);
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.ISSUED);
        assertThat(answers.count()).isZero();
        assertThat(histories.count()).isZero();
        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("만료 전 제출한 전체 거절이 늦게 도착하면 10P와 P01을 남긴다")
    void 만료_전_제출한_전체_거절이_늦게_도착하면_포인트와_P01을_남긴다() {
        // given
        Member senior = seniorWithProfile("01098110204", "F15104", "I151004");
        FollowupCard card = cards.save(FollowupCard.issue("inc-reward-late", senior.getId(),
                "HR_V1", 1L, System.currentTimeMillis() - 43_201_000L));
        service.expireIfDue(card.getId(), System.currentTimeMillis());
        long beforeExpiry = card.getExpiresAtMs() - 1;

        // when
        service.decline(senior.getId(), card.getId(), beforeExpiry);

        // then
        assertThat(cards.findById(card.getId()).orElseThrow().getState()).isEqualTo(FollowupCardState.DECLINED);
        assertThat(answers.count()).isEqualTo(1);
        assertThat(profiles.findByMemberId(senior.getId()).orElseThrow().getPoints()).isEqualTo(110L);
        assertThat(histories.findAll()).singleElement().satisfies(history ->
                assertThat(history.getOperationKey()).isEqualTo("FOLLOWUP:" + card.getId()));
        assertThat(notifications.count()).isEqualTo(1);
        assertThat(outbox.count()).isZero();
    }

    private Member seniorWithProfile(String phone, String familyCode, String inviteCode) {
        Family family = families.save(Family.createFamily(familyCode));
        Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", phone));
        profiles.save(SeniorProfile.createSeniorProfile(senior, family,
                "서울시 강남구", inviteCode, LocalDate.of(1950, 1, 1)));
        return senior;
    }

    private FollowupCard card(Long seniorId, String ref) {
        return cards.save(FollowupCard.issue(ref, seniorId, "HR_V1", 1L, System.currentTimeMillis()));
    }
}
