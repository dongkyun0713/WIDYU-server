package com.widyu.member.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.dto.request.FamilyJoinRequest;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.FamilyRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyConnectionService 예외 처리 단위 테스트")
class FamilyConnectionServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private FamilyRepository familyRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;

    @InjectMocks
    private FamilyConnectionService familyConnectionService;

    @Test
    @DisplayName("시니어가 가족 참여 시 FORBIDDEN 예외를 던진다")
    void 시니어가_가족_참여_시_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(member(1L, MemberType.SENIOR));

        // when & then
        assertThatThrownBy(() -> familyConnectionService.joinFamily(new FamilyJoinRequest("ABC123")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("보호자 회원만 초대코드로 가족에 참여할 수 있습니다.");
        then(familyMembershipRepository).should(never()).save(any(FamilyMembership.class));
    }

    @Test
    @DisplayName("가족 코드가 없으면 INVITE_CODE_NOT_FOUND 예외를 던진다")
    void 가족_코드가_없으면_예외가_발생한다() {
        // given
        given(memberUtil.getCurrentMember()).willReturn(member(1L, MemberType.GUARDIAN));
        given(familyRepository.findByFamilyCode("ABC123")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> familyConnectionService.joinFamily(new FamilyJoinRequest("ABC123")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_CODE_NOT_FOUND)
                .hasMessageContaining("초대코드를 찾을 수 없습니다.");
        then(familyMembershipRepository).should(never()).save(any(FamilyMembership.class));
    }

    @Test
    @DisplayName("이미 가족에 연결된 보호자는 ALREADY_CONNECTED_TO_FAMILY 예외를 던진다")
    void 이미_가족에_연결된_보호자는_예외가_발생한다() {
        // given
        Member guardian = member(1L, MemberType.GUARDIAN);
        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 10L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.findByFamilyCode("ABC123")).willReturn(Optional.of(family));
        given(familyMembershipRepository.findByGuardianId(1L))
                .willReturn(Optional.of(FamilyMembership.createMembership(family, guardian)));

        // when & then
        assertThatThrownBy(() -> familyConnectionService.joinFamily(new FamilyJoinRequest("ABC123")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_CONNECTED_TO_FAMILY)
                .hasMessageContaining("이미 가족에 소속되어 있습니다.");
        then(familyMembershipRepository).should(never()).save(any(FamilyMembership.class));
    }

    @Test
    @DisplayName("보호자가 가족에 가입하면 마지막 순서 다음에 배치하고 revision을 증가시킨다")
    void 보호자가_가족에_가입하면_마지막_순서에_배치하고_revision을_증가시킨다() {
        // given
        Member guardian = member(2L, MemberType.GUARDIAN);
        Family family = Family.createFamily("ABC123");
        ReflectionTestUtils.setField(family, "id", 10L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        given(familyRepository.findByFamilyCode("ABC123")).willReturn(Optional.of(family));
        given(familyMembershipRepository.existsByFamilyIdAndIsLeaderTrue(10L)).willReturn(true);
        given(familyMembershipRepository.findMaxSortOrderByFamilyId(10L)).willReturn(3);
        ArgumentCaptor<FamilyMembership> membership = ArgumentCaptor.forClass(FamilyMembership.class);

        // when
        familyConnectionService.joinFamily(new FamilyJoinRequest("ABC123"));

        // then
        then(familyMembershipRepository).should().save(membership.capture());
        assertThat(membership.getValue().getSortOrder()).isEqualTo(4);
        then(familyRepository).should().incrementOrderRevision(10L);
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, type.name(), "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
