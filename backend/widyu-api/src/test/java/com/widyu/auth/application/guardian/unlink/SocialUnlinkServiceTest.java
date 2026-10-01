package com.widyu.auth.application.guardian.unlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.auth.SocialUnlinkTask;
import com.widyu.auth.SocialUnlinkTaskStatus;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.repository.SocialUnlinkTaskRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SocialAccount;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SocialUnlinkService 단위 테스트")
class SocialUnlinkServiceTest {

    @Mock private SocialUnlinkTaskRepository repository;
    @Mock private SocialLoginStrategyFactory strategyFactory;
    @Mock private SocialLoginStrategy strategy;

    @InjectMocks private SocialUnlinkService socialUnlinkService;

    @Test
    @DisplayName("탈퇴 회원의 연동 해제를 예약하면 카카오는 oauthId로, 애플은 토큰과 함께 작업을 저장한다")
    void 연동_해제를_예약하면_제공자별_작업을_저장한다() {
        // given
        Member member = memberWith(
                account -> SocialAccount.createSocialAccount("k@k.com", "kakao", "kakao-id", "kakao-token", account),
                account -> SocialAccount.createSocialAccount("a@a.com", "apple", "apple-id", "apple-token", account));

        // when
        socialUnlinkService.schedule(member);

        // then
        ArgumentCaptor<SocialUnlinkTask> captor = ArgumentCaptor.forClass(SocialUnlinkTask.class);
        verify(repository, times(2)).save(captor.capture());
        List<SocialUnlinkTask> tasks = captor.getAllValues();
        assertThat(tasks).extracting(SocialUnlinkTask::getProvider).containsExactly("kakao", "apple");
        assertThat(tasks).extracting(SocialUnlinkTask::getOauthId).containsExactly("kakao-id", "apple-id");
        assertThat(tasks).extracting(SocialUnlinkTask::getRefreshToken).containsExactly(null, "apple-token");
        assertThat(tasks).allMatch(task -> task.getMemberId().equals(1L) && task.isPending());
    }

    @Test
    @DisplayName("리프레시 토큰이 없는 애플 계정은 연동 해제 작업을 만들지 않는다")
    void 리프레시_토큰이_없는_애플_계정은_작업을_만들지_않는다() {
        // given
        Member member = memberWith(
                account -> SocialAccount.createSocialAccount("a@a.com", "apple", "apple-id", null, account));

        // when
        socialUnlinkService.schedule(member);

        // then
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("연동 해제 작업을 처리해 성공하면 완료로 바꾸고 oauthId와 토큰을 지운다")
    void 작업을_처리해_성공하면_완료로_바꾸고_oauthId와_토큰을_지운다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "apple", "apple-id", "apple-token", LocalDateTime.now());
        given(repository.findById(10L)).willReturn(Optional.of(task));
        given(strategyFactory.getStrategy("apple")).willReturn(strategy);

        // when
        socialUnlinkService.process(10L);

        // then
        verify(strategy).withdrawSocialAccount("apple-token", "apple-id");
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.COMPLETED);
        assertThat(task.getOauthId()).isNull();
        assertThat(task.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("연동 해제 호출이 실패하면 예외를 밖으로 던지지 않고 시도 횟수를 올린다")
    void 연동_해제_호출이_실패하면_시도_횟수를_올린다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "kakao", "kakao-id", null, LocalDateTime.now());
        given(repository.findById(10L)).willReturn(Optional.of(task));
        given(strategyFactory.getStrategy("kakao")).willReturn(strategy);
        willThrow(new BusinessException(ErrorCode.KAKAO_WITHDRAW_ERROR))
                .given(strategy).withdrawSocialAccount(any(), any());

        // when
        socialUnlinkService.process(10L);

        // then
        assertThat(task.getStatus()).isEqualTo(SocialUnlinkTaskStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(1);
        assertThat(task.getLastErrorType()).isEqualTo("BusinessException");
        assertThat(task.getOauthId()).isEqualTo("kakao-id");
    }

    @Test
    @DisplayName("이미 끝난 연동 해제 작업은 다시 호출하지 않는다")
    void 이미_끝난_작업은_다시_호출하지_않는다() {
        // given
        SocialUnlinkTask task = SocialUnlinkTask.pending(1L, "kakao", "kakao-id", null, LocalDateTime.now());
        task.complete(LocalDateTime.now());
        given(repository.findById(10L)).willReturn(Optional.of(task));

        // when
        socialUnlinkService.process(10L);

        // then
        verifyNoInteractions(strategyFactory);
    }

    @SafeVarargs
    private Member memberWith(Function<Member, SocialAccount>... accountFactories) {
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        List<SocialAccount> accounts = Arrays.stream(accountFactories).map(factory -> factory.apply(member)).toList();
        ReflectionTestUtils.setField(member, "socialAccounts", accounts);
        return member;
    }
}
