package com.widyu.study.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.admin.AdminAction;
import com.widyu.admin.application.AdminAuditLogService;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.study.StudyParticipation;
import com.widyu.study.StudyParticipationHistory;
import com.widyu.study.StudyParticipationHistoryType;
import com.widyu.study.StudyParticipationStatus;
import com.widyu.study.WithdrawalScope;
import com.widyu.study.dto.request.StudyParticipationCreateRequest;
import com.widyu.study.dto.request.StudyRetentionChangeRequest;
import com.widyu.study.dto.request.StudyWithdrawalRequest;
import com.widyu.study.dto.response.StudyParticipationResponse;
import com.widyu.study.repository.StudyParticipationHistoryRepository;
import com.widyu.study.repository.StudyParticipationRepository;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("StudyParticipationService 단위 테스트")
class StudyParticipationServiceTest {

    private static final String STUDY_ID = "STUDY-2026";
    private static final Long MEMBER_ID = 1023L;
    private static final String PARTICIPATION_ID = "part-0f3a";
    private static final LocalDate CONSENTED_AT = LocalDate.of(2026, 9, 20);
    private static final LocalDate IDENTIFIED_UNTIL = LocalDate.of(2027, 3, 31);
    private static final LocalDate PSEUDONYMIZED_AT = LocalDate.of(2027, 3, 31);
    private static final LocalDate RESEARCH_UNTIL = LocalDate.of(2029, 3, 31);

    @Mock private StudyParticipationRepository studyParticipationRepository;
    @Mock private StudyParticipationHistoryRepository studyParticipationHistoryRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private AdminAuditLogService adminAuditLogService;

    @InjectMocks private StudyParticipationService studyParticipationService;

    @Test
    @DisplayName("참여를 등록하면 서버가 발급한 식별자와 서면 동의·선택 동의를 ACTIVE로 저장한다")
    void 참여를_등록하면_서버가_발급한_식별자와_서면_동의_선택_동의를_ACTIVE로_저장한다() {
        // given
        givenRegisterable();
        StudyParticipationCreateRequest request = createRequest(
                Map.of("guardian_location_access", true, "ecg", false),
                "KR_IRB", IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, RESEARCH_UNTIL);

        // when
        StudyParticipationResponse response = studyParticipationService.register(request);

        // then
        assertThat(response.participationId()).startsWith("part-").hasSize(37);
        assertThat(response.status()).isEqualTo(StudyParticipationStatus.ACTIVE);
        assertThat(response.memberId()).isEqualTo(MEMBER_ID);
        assertThat(response.consentVersion()).isEqualTo("IRB-v1");
        assertThat(response.consentedAt()).isEqualTo(CONSENTED_AT);
        assertThat(response.consents())
                .containsEntry("guardian_location_access", true)
                .containsEntry("ecg", false);
        assertThat(response.researchUntil()).isEqualTo(RESEARCH_UNTIL);
        assertThat(savedHistoryTypes()).containsExactly(StudyParticipationHistoryType.REGISTERED);
        thenAudited(AdminAction.STUDY_PARTICIPATION_REGISTER);
    }

    @Test
    @DisplayName("보관 계획이 아직 비어 있어도 참여를 등록한다")
    void 보관_계획이_아직_비어_있어도_참여를_등록한다() {
        // given
        givenRegisterable();
        StudyParticipationCreateRequest request = createRequest(null, null, null, null, null);

        // when
        StudyParticipationResponse response = studyParticipationService.register(request);

        // then
        assertThat(response.status()).isEqualTo(StudyParticipationStatus.ACTIVE);
        assertThat(response.dataPolicy()).isNull();
        assertThat(response.identifiedUntil()).isNull();
        assertThat(response.pseudonymizedAt()).isNull();
        assertThat(response.researchUntil()).isNull();
        assertThat(response.consents()).isEmpty();
    }

    @Test
    @DisplayName("보관 날짜를 일부만 주면 예외가 발생한다")
    void 보관_날짜를_일부만_주면_예외가_발생한다() {
        // given
        givenExistingMember();
        StudyParticipationCreateRequest request =
                createRequest(null, "KR_IRB", IDENTIFIED_UNTIL, null, RESEARCH_UNTIL);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        then(studyParticipationRepository).should(never()).save(any());
        then(studyParticipationHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보관 정책만 있고 날짜가 없으면 예외가 발생한다")
    void 보관_정책만_있고_날짜가_없으면_예외가_발생한다() {
        // given
        givenExistingMember();
        StudyParticipationCreateRequest request = createRequest(null, "KR_IRB", null, null, null);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보관 날짜만 있고 정책이 없으면 예외가 발생한다")
    void 보관_날짜만_있고_정책이_없으면_예외가_발생한다() {
        // given
        givenExistingMember();
        StudyParticipationCreateRequest request =
                createRequest(null, null, IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, RESEARCH_UNTIL);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보관 날짜 순서가 역전되면 예외가 발생한다")
    void 보관_날짜_순서가_역전되면_예외가_발생한다() {
        // given
        givenExistingMember();
        StudyParticipationCreateRequest request = createRequest(
                null, "KR_IRB", IDENTIFIED_UNTIL, IDENTIFIED_UNTIL.minusDays(1), RESEARCH_UNTIL);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_RETENTION_PERIOD_INVALID);
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("같은 연구에 ACTIVE 참여가 이미 있으면 예외가 발생한다")
    void 같은_연구에_ACTIVE_참여가_이미_있으면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.existsByStudyIdAndMemberIdAndStatus(
                STUDY_ID, MEMBER_ID, StudyParticipationStatus.ACTIVE)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(createRequest(null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        then(studyParticipationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보관 계획을 수정하면 새 계획을 반영하고 변경 이력을 남긴다")
    void 보관_계획을_수정하면_새_계획을_반영하고_변경_이력을_남긴다() {
        // given
        StudyParticipation participation = activeParticipation();
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));
        LocalDate newResearchUntil = RESEARCH_UNTIL.plusYears(1);

        // when
        StudyParticipationResponse response = studyParticipationService.changeRetention(
                PARTICIPATION_ID,
                new StudyRetentionChangeRequest("KR_IRB", IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, newResearchUntil));

        // then
        assertThat(response.researchUntil()).isEqualTo(newResearchUntil);
        assertThat(participation.getResearchUntil()).isEqualTo(newResearchUntil);
        assertThat(savedHistoryTypes()).containsExactly(StudyParticipationHistoryType.RETENTION_CHANGED);
        thenAudited(AdminAction.STUDY_PARTICIPATION_PERIOD_CHANGE);
    }

    @Test
    @DisplayName("전체 철회하면 WITHDRAWN으로 바뀌고 철회 이력을 남긴다")
    void 전체_철회하면_WITHDRAWN으로_바뀌고_철회_이력을_남긴다() {
        // given
        StudyParticipation participation = activeParticipation();
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));

        // when
        StudyParticipationResponse response = studyParticipationService.withdraw(
                PARTICIPATION_ID, new StudyWithdrawalRequest(WithdrawalScope.ALL, null));

        // then
        assertThat(response.status()).isEqualTo(StudyParticipationStatus.WITHDRAWN);
        assertThat(response.withdrawalScope()).isEqualTo(WithdrawalScope.ALL);
        assertThat(response.withdrawnAt()).isNotNull();
        assertThat(response.deletionProcessedAt()).isNull();
        assertThat(savedHistoryTypes()).containsExactly(StudyParticipationHistoryType.WITHDRAWN);
        thenAudited(AdminAction.STUDY_PARTICIPATION_WITHDRAW);
    }

    @Test
    @DisplayName("일부 철회에 동의 항목이 없으면 예외가 발생하고 이력을 남기지 않는다")
    void 일부_철회에_동의_항목이_없으면_예외가_발생하고_이력을_남기지_않는다() {
        // given
        StudyParticipation participation = activeParticipation();
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.withdraw(
                PARTICIPATION_ID, new StudyWithdrawalRequest(WithdrawalScope.SELECTED_CONSENTS, Set.of())))
                .isInstanceOf(BusinessException.class);
        assertThat(participation.getStatus()).isEqualTo(StudyParticipationStatus.ACTIVE);
        then(studyParticipationHistoryRepository).should(never()).save(any());
        then(adminAuditLogService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("철회한 참여의 삭제 처리를 기록하면 처리 시각과 처리 완료 이력을 남긴다")
    void 철회한_참여의_삭제_처리를_기록하면_처리_시각과_처리_완료_이력을_남긴다() {
        // given
        StudyParticipation participation = activeParticipation();
        participation.withdraw(WithdrawalScope.ALL, null, java.time.LocalDateTime.now());
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));

        // when
        StudyParticipationResponse response = studyParticipationService.markDeletionProcessed(PARTICIPATION_ID);

        // then
        assertThat(response.deletionProcessedAt()).isNotNull();
        assertThat(savedHistoryTypes()).containsExactly(StudyParticipationHistoryType.DELETION_PROCESSED);
        thenAudited(AdminAction.STUDY_PARTICIPATION_DELETION_PROCESSED);
    }

    @Test
    @DisplayName("다른 회원의 참여 기록으로 연구 회차를 열려고 하면 예외가 발생한다")
    void 다른_회원의_참여_기록으로_연구_회차를_열려고_하면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.findByParticipationIdForUpdate(PARTICIPATION_ID))
                .willReturn(Optional.of(activeParticipation()));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.requireActiveForRun(PARTICIPATION_ID, 9999L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_RESEARCH_PARTICIPATION_MISMATCH);
    }

    @Test
    @DisplayName("철회한 참여 기록으로 연구 회차를 열려고 하면 예외가 발생한다")
    void 철회한_참여_기록으로_연구_회차를_열려고_하면_예외가_발생한다() {
        // given
        StudyParticipation participation = activeParticipation();
        participation.withdraw(WithdrawalScope.ALL, null, java.time.LocalDateTime.now());
        given(studyParticipationRepository.findByParticipationIdForUpdate(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.requireActiveForRun(PARTICIPATION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE);
    }

    @Test
    @DisplayName("동의하지 않은 항목을 일부 철회하면 예외가 발생한다")
    void 동의하지_않은_항목을_일부_철회하면_예외가_발생한다() {
        // given
        StudyParticipation participation = activeParticipation();
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(participation));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.withdraw(
                PARTICIPATION_ID, new StudyWithdrawalRequest(WithdrawalScope.SELECTED_CONSENTS, Set.of("ecg"))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_WITHDRAWAL_CONSENT_INVALID);
        assertThat(participation.getStatus()).isEqualTo(StudyParticipationStatus.ACTIVE);
        then(studyParticipationHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("일부 철회하면 거둔 동의 항목이 이력에도 남는다")
    void 일부_철회하면_거둔_동의_항목이_이력에도_남는다() {
        // given
        given(studyParticipationRepository.findByParticipationId(PARTICIPATION_ID))
                .willReturn(Optional.of(activeParticipation()));

        // when
        StudyParticipationResponse response = studyParticipationService.withdraw(
                PARTICIPATION_ID,
                new StudyWithdrawalRequest(WithdrawalScope.SELECTED_CONSENTS, Set.of("guardian_location_access")));

        // then
        assertThat(response.withdrawalConsentKeys()).containsExactly("guardian_location_access");
        assertThat(savedHistory().getWithdrawnConsentKeys()).isEqualTo("[\"guardian_location_access\"]");
    }

    @Test
    @DisplayName("같은 연구·회원의 ACTIVE 참여가 DB 제약에 걸리면 중복 예외로 바꾼다")
    void 같은_연구_회원의_ACTIVE_참여가_DB_제약에_걸리면_중복_예외로_바꾼다() {
        // given
        given(studyParticipationRepository.existsByStudyIdAndMemberIdAndStatus(
                STUDY_ID, MEMBER_ID, StudyParticipationStatus.ACTIVE)).willReturn(false);
        givenExistingMember();
        given(studyParticipationRepository.save(any(StudyParticipation.class)))
                .willThrow(new DataIntegrityViolationException("uk_study_participation_active"));

        // when & then
        assertThatThrownBy(() -> studyParticipationService.register(createRequest(null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_PARTICIPATION_DUPLICATED);
        then(studyParticipationHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("없는 참여 식별자를 조회하면 예외가 발생한다")
    void 없는_참여_식별자를_조회하면_예외가_발생한다() {
        // given
        given(studyParticipationRepository.findByParticipationId("part-none")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> studyParticipationService.get("part-none"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_PARTICIPATION_NOT_FOUND);
    }

    @Test
    @DisplayName("참여 기록 응답을 보면 회원 식별자만 있고 이름·전화번호·건강값·좌표가 없다")
    void 참여_기록_응답을_보면_회원_식별자만_있고_이름_전화번호_건강값_좌표가_없다() {
        // given
        List<String> components = Arrays.stream(StudyParticipationResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // when & then
        assertThat(components).contains("memberId");
        assertThat(components).noneMatch(name -> name.toLowerCase().matches(
                ".*(name|phone|bpm|heart|latitude|longitude).*"));
    }

    private void givenRegisterable() {
        given(studyParticipationRepository.existsByStudyIdAndMemberIdAndStatus(
                STUDY_ID, MEMBER_ID, StudyParticipationStatus.ACTIVE)).willReturn(false);
        givenExistingMember();
        given(studyParticipationRepository.save(any(StudyParticipation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenExistingMember() {
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(senior()));
    }

    private void thenAudited(AdminAction action) {
        then(adminAuditLogService).should()
                .logInCurrentTransaction(eq(action), eq("StudyParticipation"), any(), anyString());
    }

    private List<StudyParticipationHistoryType> savedHistoryTypes() {
        return List.of(savedHistory().getHistoryType());
    }

    private StudyParticipationHistory savedHistory() {
        ArgumentCaptor<StudyParticipationHistory> saved =
                ArgumentCaptor.forClass(StudyParticipationHistory.class);
        then(studyParticipationHistoryRepository).should().save(saved.capture());
        return saved.getValue();
    }

    private static Member senior() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private static StudyParticipationCreateRequest createRequest(
            Map<String, Boolean> consents,
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil) {
        return new StudyParticipationCreateRequest(
                STUDY_ID, MEMBER_ID, "IRB-v1", CONSENTED_AT, consents,
                dataPolicy, identifiedUntil, pseudonymizedAt, researchUntil);
    }

    private static StudyParticipation activeParticipation() {
        return StudyParticipation.of(
                STUDY_ID, PARTICIPATION_ID, senior(), "IRB-v1", CONSENTED_AT,
                Map.of("guardian_location_access", true, "ecg", false),
                "KR_IRB", IDENTIFIED_UNTIL, PSEUDONYMIZED_AT, RESEARCH_UNTIL);
    }
}
