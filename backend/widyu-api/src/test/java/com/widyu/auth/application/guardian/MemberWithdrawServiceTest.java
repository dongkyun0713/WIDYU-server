package com.widyu.auth.application.guardian;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.dto.request.MemberWithdrawRequest;
import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.goal.medicineschedule.application.MedicationProofDeletionService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SocialAccount;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberWithdrawService 단위 테스트")
class MemberWithdrawServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private FamilyRepository familyRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private SocialLoginStrategyFactory strategyFactory;
    @Mock private MemberUtil memberUtil;
    @Mock private MedicationProofDeletionService medicationProofDeletionService;
    @Mock private SocialLoginStrategy kakaoStrategy;
    @Mock private SocialLoginStrategy appleStrategy;

    @InjectMocks
    private MemberWithdrawService memberWithdrawService;

    @Test
    @DisplayName("소셜 계정이 없는 회원 탈퇴 시 리프레시 토큰 삭제 및 회원 정보를 저장한다")
    void 소셜계정_없는_회원_탈퇴_시_토큰_삭제하고_회원_저장() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());
        given(memberUtil.getCurrentMember()).willReturn(member);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("서비스 불만족"));

        // then
        verify(refreshTokenRepository).deleteById(1L);
        verify(medicationProofDeletionService).deleteAllByMember(member);
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("FamilyMembership이 없는 guardian 탈퇴 시 예외 없이 완료된다")
    void FamilyMembership_없는_guardian_탈퇴_시_예외없이_완료된다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());
        given(memberUtil.getCurrentMember()).willReturn(member);

        // when & then
        assertThatCode(() -> memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유")))
                .doesNotThrowAnyException();
        verify(familyMembershipRepository, never()).deleteByGuardianId(any());
    }

    @Test
    @DisplayName("방장이 아닌 구성원 탈퇴 시 FamilyMembership만 삭제된다")
    void 방장_아닌_구성원_탈퇴_시_멤버십만_삭제된다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());

        Family family = Family.createFamily("ABCDEF");
        ReflectionTestUtils.setField(family, "id", 100L);
        FamilyMembership membership = FamilyMembership.createMembership(family, member);

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(membership));

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(familyMembershipRepository).deleteByGuardianId(1L);
        verify(familyRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("방장이며 다른 구성원이 있을 때 탈퇴 시 예외가 발생한다")
    void 방장이며_다른_구성원_있을_때_탈퇴_시_예외가_발생한다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());

        Family family = Family.createFamily("ABCDEF");
        ReflectionTestUtils.setField(family, "id", 100L);
        FamilyMembership leaderMembership = FamilyMembership.createLeaderMembership(family, member);

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(leaderMembership));
        given(familyMembershipRepository.countByFamilyId(100L)).willReturn(2L);

        // when & then
        assertThatThrownBy(() -> memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유")))
                .isInstanceOf(BusinessException.class);
        verify(familyRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("소셜 계정 보유 방장이 다른 구성원 있을 때 탈퇴 차단 시 소셜 revoke를 호출하지 않는다")
    void 소셜_계정_보유_방장이_다른_구성원_있을_때_탈퇴_차단_시_소셜_revoke_호출하지_않는다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        SocialAccount kakaoAccount = SocialAccount.createSocialAccount("k@k.com", "kakao", "kakao-id", member);
        ReflectionTestUtils.setField(member, "socialAccounts", List.of(kakaoAccount));

        Family family = Family.createFamily("ABCDEF");
        ReflectionTestUtils.setField(family, "id", 100L);
        FamilyMembership leaderMembership = FamilyMembership.createLeaderMembership(family, member);

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(leaderMembership));
        given(familyMembershipRepository.countByFamilyId(100L)).willReturn(2L);

        // when & then
        assertThatThrownBy(() -> memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유")))
                .isInstanceOf(BusinessException.class);
        verify(kakaoStrategy, never()).withdrawSocialAccount(any(), any());
    }

    @Test
    @DisplayName("방장이며 마지막 구성원일 때 탈퇴 시 Family가 삭제된다")
    void 방장이며_마지막_구성원일_때_탈퇴_시_Family가_삭제된다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());

        Family family = Family.createFamily("ABCDEF");
        ReflectionTestUtils.setField(family, "id", 100L);
        FamilyMembership leaderMembership = FamilyMembership.createLeaderMembership(family, member);

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(leaderMembership));
        given(familyMembershipRepository.countByFamilyId(100L)).willReturn(1L);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(seniorProfileRepository).clearFamilyByFamilyId(100L);
        verify(familyMembershipRepository).deleteByGuardianId(1L);
        verify(familyRepository).deleteById(100L);
    }

    @Test
    @DisplayName("카카오 계정이 있는 회원 탈퇴 시 카카오 탈퇴 API를 호출한다")
    void 카카오_계정_보유_회원_탈퇴_시_카카오_탈퇴_API_호출() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        SocialAccount kakaoAccount = SocialAccount.createSocialAccount("k@k.com", "kakao", "kakao-id", member);
        ReflectionTestUtils.setField(member, "socialAccounts", List.of(kakaoAccount));

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(strategyFactory.getStrategy("kakao")).willReturn(kakaoStrategy);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(kakaoStrategy).withdrawSocialAccount(null, "kakao-id");
    }

    @Test
    @DisplayName("애플 계정(리프레시 토큰 있음) 탈퇴 시 리프레시 토큰으로 탈퇴 API를 호출한다")
    void 애플_계정_보유_회원_탈퇴_시_리프레시토큰으로_탈퇴_API_호출() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        SocialAccount appleAccount = SocialAccount.createSocialAccount(
                "a@a.com", "apple", "apple-id", "apple-refresh-token", member
        );
        ReflectionTestUtils.setField(member, "socialAccounts", List.of(appleAccount));

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(strategyFactory.getStrategy("apple")).willReturn(appleStrategy);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(appleStrategy).withdrawSocialAccount("apple-refresh-token", "apple-id");
    }

    @Test
    @DisplayName("카카오 탈퇴 실패 시에도 전체 탈퇴 흐름(토큰 삭제, 회원 저장)은 계속 진행된다")
    void 카카오_탈퇴_실패_시에도_전체_탈퇴_흐름이_계속된다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        SocialAccount kakaoAccount = SocialAccount.createSocialAccount("k@k.com", "kakao", "kakao-id", member);
        ReflectionTestUtils.setField(member, "socialAccounts", List.of(kakaoAccount));

        given(memberUtil.getCurrentMember()).willReturn(member);
        given(strategyFactory.getStrategy("kakao")).willReturn(kakaoStrategy);
        willThrow(new RuntimeException("카카오 서버 오류")).given(kakaoStrategy).withdrawSocialAccount(any(), any());

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(refreshTokenRepository).deleteById(1L);
        verify(memberRepository).save(member);
    }

    @Test
    @DisplayName("리프레시 토큰이 없는 애플 계정은 탈퇴 API를 호출하지 않는다")
    void 리프레시토큰_없는_애플_계정은_탈퇴_API_호출하지_않는다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        SocialAccount appleAccountNoToken = SocialAccount.createSocialAccount(
                "a@a.com", "apple", "apple-id", null, member
        );
        ReflectionTestUtils.setField(member, "socialAccounts", List.of(appleAccountNoToken));

        given(memberUtil.getCurrentMember()).willReturn(member);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(appleStrategy, never()).withdrawSocialAccount(any(), any());
    }

    @Test
    @DisplayName("회원 탈퇴 시 개인정보가 마스킹된다")
    void 회원_탈퇴_시_개인정보가_마스킹된다() {
        // given
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "socialAccounts", new ArrayList<>());
        given(memberUtil.getCurrentMember()).willReturn(member);

        // when
        memberWithdrawService.withdrawMember(new MemberWithdrawRequest("탈퇴 사유"));

        // then
        verify(memberRepository).save(
                org.mockito.ArgumentMatchers.argThat(
                        m -> !"홍길동".equals(m.getName())
                )
        );
    }
}
