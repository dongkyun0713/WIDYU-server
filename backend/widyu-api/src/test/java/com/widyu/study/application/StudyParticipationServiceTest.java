package com.widyu.study.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.global.error.BusinessException;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.study.DataPolicy;
import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationStatus;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import com.widyu.study.repository.StudyParticipationRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StudyParticipationService 단위 테스트")
class StudyParticipationServiceTest {

    private static final LocalDate IDENTIFIED_UNTIL = LocalDate.of(2027, 3, 31);
    private static final LocalDate PSEUDONYMIZED_AT = LocalDate.of(2027, 3, 31);
    private static final LocalDate RESEARCH_UNTIL = LocalDate.of(2029, 3, 31);

    @Mock private StudyParticipationRepository studyParticipationRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AdminAuditLogService adminAuditLogService;

    @InjectMocks
    private StudyParticipationService studyParticipationService;

    @Test
    @DisplayName("필수 필드를 모두 갖춘 참여를 등록하면 ACTIVE 상태로 저장한다")
    void 필수_필드를_모두_갖춘_참여를_등록하면_ACTIVE_상태로_저장한다() {
        // given
        StudyParticipationCreateRequest request = createRequest("P-001", PSEUDONYMIZED_AT);
        given(studyParticipationRepository.existsByParticipationId("P-001")).willReturn(false);
        given(studyParticipationRepository.existsByMemberIdAndStatus(1L, StudyParticipationStatus.ACTIVE))
                .willReturn(false);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior()));
        given(studyParticipationRepository.save(any(StudyParticipation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        StudyParticipationResponse response = studyParticipationService.register(request);

        // then
        assertThat(response.participationId()).isEqualTo("P-001");
        assertThat(response.status()).isEqualTo(StudyParticipationStatus.ACTIVE);
        assertThat(response.dataPolicy()).isEqualTo(DataPolicy.KR_IRB);
        assertThat(response.researchUntil()).isEqualTo(RESEARCH_UNTIL);
    }

    @Test
    @DisplayName("같은 participationId로 등록하면 예외가 발생한다")
    void 같은_participationId로_등록하면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.existsByParticipationId("P-001")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(createRequest("P-001", PSEUDONYMIZED_AT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 등록된 연구 참여");
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("회원에게 ACTIVE 참여가 이미 있으면 예외가 발생한다")
    void 회원에게_ACTIVE_참여가_이미_있으면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.existsByParticipationId("P-002")).willReturn(false);
        given(studyParticipationRepository.existsByMemberIdAndStatus(1L, StudyParticipationStatus.ACTIVE))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(createRequest("P-002", PSEUDONYMIZED_AT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 등록된 연구 참여");
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("가명화 시점이 식별 보존 종료일보다 앞서면 예외가 발생한다")
    void 가명화_시점이_식별_보존_종료일보다_앞서면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.existsByParticipationId("P-003")).willReturn(false);
        given(studyParticipationRepository.existsByMemberIdAndStatus(anyLong(), any())).willReturn(false);
        given(memberRepository.findById(1L)).willReturn(Optional.of(senior()));
        StudyParticipationCreateRequest request = createRequest("P-003", IDENTIFIED_UNTIL.minusDays(1));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("보존 기간 순서");
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보존 기간을 변경하면 새 기간을 반영하고 변경 전후를 감사 로그에 남긴다")
    void 보존_기간을_변경하면_새_기간을_반영하고_변경_전후를_감사_로그에_남긴다() {
        // given
        StudyParticipation participation = activeParticipation("P-001");
        given(studyParticipationRepository.findByParticipationId("P-001")).willReturn(Optional.of(participation));
        LocalDate newResearchUntil = RESEARCH_UNTIL.plusYears(1);
        StudyRetentionChangeRequest request = new StudyRetentionChangeRequest(
                IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, newResearchUntil, "v2", "IRB-2026-01-A1");

        // when
        StudyParticipationResponse response = studyParticipationService.changeRetention("P-001", request);

        // then
        assertThat(response.researchUntil()).isEqualTo(newResearchUntil);
        assertThat(response.consentVersion()).isEqualTo("v2");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        then(adminAuditLogService).should().log(
                eq(AdminAction.STUDY_PARTICIPATION_PERIOD_CHANGE), eq("STUDY_PARTICIPATION"), any(), detail.capture());
        assertThat(detail.getValue())
                .contains("IRB-2026-01-A1")
                .contains("researchUntil=" + RESEARCH_UNTIL)
                .contains("researchUntil=" + newResearchUntil)
                .contains("consentVersion=v1")
                .contains("consentVersion=v2");
    }

    @Test
    @DisplayName("보존 기간 순서를 어기는 변경 요청은 예외가 발생하고 이력을 남기지 않는다")
    void 보존_기간_순서를_어기는_변경_요청은_예외가_발생하고_이력을_남기지_않는다() {
        // given
        given(studyParticipationRepository.findByParticipationId("P-001"))
                .willReturn(Optional.of(activeParticipation("P-001")));
        StudyRetentionChangeRequest request = new StudyRetentionChangeRequest(
                IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, PSEUDONYMIZED_AT.minusDays(1), "v2", "IRB-2026-01-A1");

        // when & then
        assertThatThrownBy(() -> studyParticipationService.changeRetention("P-001", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("보존 기간 순서");
        then(adminAuditLogService).should(never()).log(any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("없는 participationId를 조회하면 예외가 발생한다")
    void 없는_participationId를_조회하면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.findByParticipationId("NOPE")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> studyParticipationService.get("NOPE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("연구 참여 정보를 찾을 수 없습니다");
    }

    private static Member senior() {
        return Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
    }

    private static StudyParticipationCreateRequest createRequest(String participationId, LocalDate pseudonymizedAt) {
        return new StudyParticipationCreateRequest(
                "STUDY-2026", participationId, 1L, DataPolicy.KR_IRB,
                IDENTIFIED_UNTIL, pseudonymizedAt, RESEARCH_UNTIL, "v1");
    }

    private static StudyParticipation activeParticipation(String participationId) {
        return StudyParticipation.of(
                "STUDY-2026", participationId, senior(), DataPolicy.KR_IRB,
                IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, RESEARCH_UNTIL, "v1");
    }
}
