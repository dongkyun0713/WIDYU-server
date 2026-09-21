package com.widyu.study.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.study.StudyParticipation;
import com.widyu.study.WithdrawalScope;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("StudyParticipation ACTIVE 단일성 제약 테스트")
class StudyParticipationRepositoryTest {

    private static final String STUDY_ID = "STUDY-2026";
    private static final LocalDate CONSENTED_AT = LocalDate.of(2026, 9, 20);

    @Autowired private StudyParticipationRepository studyParticipationRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("같은 연구·회원에 ACTIVE 참여를 두 번 저장하면 예외가 발생한다")
    void 같은_연구_회원에_ACTIVE_참여를_두_번_저장하면_예외가_발생한다() {
        // given
        Member member = persistSenior("01012345678");
        studyParticipationRepository.saveAndFlush(participation(member, "part-0001"));

        // when & then
        assertThatThrownBy(() ->
                studyParticipationRepository.saveAndFlush(participation(member, "part-0002")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("앞선 참여를 철회하면 같은 연구·회원에 새 참여를 저장할 수 있다")
    void 앞선_참여를_철회하면_같은_연구_회원에_새_참여를_저장할_수_있다() {
        // given
        Member member = persistSenior("01087654321");
        StudyParticipation withdrawn =
                studyParticipationRepository.saveAndFlush(participation(member, "part-0003"));
        withdrawn.withdraw(WithdrawalScope.ALL, null, LocalDateTime.now());
        studyParticipationRepository.saveAndFlush(withdrawn);

        // when & then
        assertThat(withdrawn.getActiveKey()).isNull();
        assertThatCode(() ->
                studyParticipationRepository.saveAndFlush(participation(member, "part-0004")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른 연구의 ACTIVE 참여는 같은 회원에게도 저장할 수 있다")
    void 다른_연구의_ACTIVE_참여는_같은_회원에게도_저장할_수_있다() {
        // given
        Member member = persistSenior("01011112222");
        studyParticipationRepository.saveAndFlush(participation(member, "part-0005"));

        // when & then
        assertThatCode(() -> studyParticipationRepository.saveAndFlush(
                StudyParticipation.of("STUDY-2027", "part-0006", member, "IRB-v1", CONSENTED_AT,
                        Map.of(), null, null, null, null)))
                .doesNotThrowAnyException();
    }

    private Member persistSenior(String phoneNumber) {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", phoneNumber);
        entityManager.persistAndFlush(member);
        return member;
    }

    private StudyParticipation participation(Member member, String participationId) {
        return StudyParticipation.of(
                STUDY_ID, participationId, member, "IRB-v1", CONSENTED_AT, Map.of(),
                null, null, null, null);
    }
}
