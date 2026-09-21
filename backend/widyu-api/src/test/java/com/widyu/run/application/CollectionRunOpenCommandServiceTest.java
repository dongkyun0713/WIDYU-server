package com.widyu.run.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.study.StudyParticipation;
import com.widyu.study.application.StudyParticipationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionRunOpenCommandService 단위 테스트")
class CollectionRunOpenCommandServiceTest {

    private static final Long MEMBER_ID = 1023L;
    private static final String STUDY_ID = "STUDY-2026";
    private static final String PARTICIPATION_ID = "part-0f3a";
    private static final LocalDate RESEARCH_UNTIL = LocalDate.of(2029, 3, 31);
    private static final long STARTED_AT_MS = 1_760_000_000_000L;

    @Mock private CollectionRunRepository collectionRunRepository;
    @Mock private RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    @Mock private StudyParticipationService studyParticipationService;

    @InjectMocks private CollectionRunOpenCommandService collectionRunOpenCommandService;

    @Test
    @DisplayName("연구 회차를 저장하면 잠금 재검증한 참여 기록이 회차에 붙는다")
    void 연구_회차를_저장하면_잠금_재검증한_참여_기록이_회차에_붙는다() {
        // given
        CollectionRun run = newRun();
        given(studyParticipationService.requireActiveForRun(PARTICIPATION_ID, MEMBER_ID))
                .willReturn(participation());
        given(collectionRunRepository.saveAndFlush(run)).willReturn(run);

        // when
        CollectionRun saved = collectionRunOpenCommandService.open(run, List.of(), PARTICIPATION_ID);

        // then
        assertThat(saved.getParticipation()).isNotNull();
        // 회차의 연구 메타데이터는 붙은 참여 기록에서 읽힌다.
        assertThat(saved.getStudyId()).isEqualTo(STUDY_ID);
        assertThat(saved.getParticipationId()).isEqualTo(PARTICIPATION_ID);
        assertThat(saved.getConsentVersion()).isEqualTo("IRB-v1");
        assertThat(saved.getResearchUntil()).isEqualTo(RESEARCH_UNTIL);
    }

    @Test
    @DisplayName("저장 직전에 참여가 철회됐으면 회차를 저장하지 않는다")
    void 저장_직전에_참여가_철회됐으면_회차를_저장하지_않는다() {
        // given
        CollectionRun run = newRun();
        willThrow(new BusinessException(ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE))
                .given(studyParticipationService).requireActiveForRun(PARTICIPATION_ID, MEMBER_ID);

        // when & then
        assertThatThrownBy(() -> collectionRunOpenCommandService.open(run, List.of(), PARTICIPATION_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STUDY_PARTICIPATION_NOT_ACTIVE);
        assertThat(run.getParticipation()).isNull();
        then(collectionRunRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("일반 수집 회차는 참여 기록을 조회하지 않고 저장한다")
    void 일반_수집_회차는_참여_기록을_조회하지_않고_저장한다() {
        // given
        CollectionRun run = newRun();
        given(collectionRunRepository.saveAndFlush(run)).willReturn(run);

        // when
        CollectionRun saved = collectionRunOpenCommandService.open(run, List.of(), null);

        // then
        assertThat(saved.getParticipation()).isNull();
        then(studyParticipationService).shouldHaveNoInteractions();
    }

    private CollectionRun newRun() {
        return CollectionRun.builder()
                .runId("run-0f3a")
                .member(member())
                .collectionMode("research")
                .startedAtMs(STARTED_AT_MS)
                .status(CollectionRunStatus.OPEN)
                .build();
    }

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private StudyParticipation participation() {
        return StudyParticipation.of(
                STUDY_ID, PARTICIPATION_ID, member(), "IRB-v1", LocalDate.of(2026, 9, 20), Map.of(),
                "KR_IRB", LocalDate.of(2027, 3, 31), LocalDate.of(2027, 3, 31), RESEARCH_UNTIL);
    }
}
