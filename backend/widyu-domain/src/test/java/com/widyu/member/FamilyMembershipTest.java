package com.widyu.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FamilyMembershipTest {

    @Test
    @DisplayName("방장 멤버십은 대표 연락처로 생성한다")
    void 방장_멤버십은_대표_연락처로_생성한다() {
        // given
        Family family = Family.createFamily("ABC123");
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");

        // when
        FamilyMembership membership = FamilyMembership.createLeaderMembership(family, guardian);

        // then
        assertThat(membership.isLeader()).isTrue();
        assertThat(membership.isRepresentative()).isTrue();
    }

    @Test
    @DisplayName("일반 멤버십은 대표 연락처 없이 생성한다")
    void 일반_멤버십은_대표_연락처_없이_생성한다() {
        // given
        Family family = Family.createFamily("ABC123");
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");

        // when
        FamilyMembership membership = FamilyMembership.createMembership(family, guardian);

        // then
        assertThat(membership.isLeader()).isFalse();
        assertThat(membership.isRepresentative()).isFalse();
    }

    @Test
    @DisplayName("보호자 순서를 변경하면 새 sortOrder를 보관한다")
    void 보호자_순서를_변경하면_sortOrder를_보관한다() {
        // given
        Family family = Family.createFamily("ABC123");
        Member guardian = Member.createMember(MemberType.GUARDIAN, "보호자", "01011112222");
        FamilyMembership membership = FamilyMembership.createMembership(family, guardian, 3);

        // when
        membership.updateSortOrder(1);

        // then
        assertThat(membership.getSortOrder()).isEqualTo(1);
    }
}
