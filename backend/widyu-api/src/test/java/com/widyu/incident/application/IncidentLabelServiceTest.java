package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.SecurityUtil;
import com.widyu.incident.AnnotatorType;
import com.widyu.incident.EventOccurrence;
import com.widyu.incident.HelpNeed;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentLabel;
import com.widyu.incident.IncidentLabelSource;
import com.widyu.incident.LabelAnnotation;
import com.widyu.incident.repository.IncidentLabelRepository;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.incident.repository.LabelAnnotationRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, IncidentLabelService.class})
class IncidentLabelServiceTest {
    @Autowired private IncidentLabelService service;
    @Autowired private IncidentRepository incidents;
    @Autowired private IncidentLabelRepository labels;
    @Autowired private LabelAnnotationRepository annotations;
    @Autowired private MemberRepository members;
    @MockBean private JPAQueryFactory queryFactory;
    @MockBean private SecurityUtil securityUtil;

    @Test
    @DisplayName("사람 판독을 기록하면 두 축과 판독 근거를 원형 보존한다")
    void 사람_판독을_기록하면_두_축과_판독_근거를_보존한다() {
        // given
        incident("inc-label-human");
        Long reviewerId = authenticatedReviewer();
        AnnotationCommand command = AnnotationCommand.of("inc-label-human", AnnotatorType.HUMAN,
                reviewerId, null, "rubric-v1", EventOccurrence.PRESENT, HelpNeed.NOT_NEEDED,
                "혼자 일어남", "bundle-1", null);

        // when
        LabelAnnotation annotation = service.recordAnnotation(command);
        IncidentLabel beforeSelection = labels.findByIncidentRefForUpdate("inc-label-human").orElseThrow();
        service.setCurrentLabel("inc-label-human", annotation.getId(), null);

        // then
        assertThat(beforeSelection.getId()).isNotNull();
        IncidentLabel selected = labels.findByIncidentRefForUpdate("inc-label-human").orElseThrow();
        assertThat(selected.getEventOccurrence()).isEqualTo(EventOccurrence.PRESENT);
        assertThat(selected.getHelpNeed()).isEqualTo(HelpNeed.NOT_NEEDED);
        assertThat(selected.getSource()).isEqualTo(IncidentLabelSource.HUMAN);
        assertThat(selected.getCurrentAnnotationId()).isEqualTo(annotation.getId());
        assertThat(annotation.getReviewerId()).isEqualTo(reviewerId);
        assertThat(annotation.getRubricVersion()).isEqualTo("rubric-v1");
        assertThat(annotation.getNote()).isEqualTo("혼자 일어남");
        assertThat(annotation.getRevision()).isEqualTo(1);
        assertThat(annotations.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("LLM 판독을 기록하면 사람 ID 없이 별도 이력을 남긴다")
    void LLM_판독을_기록하면_사람_ID_없이_이력을_남긴다() {
        // given
        incident("inc-label-llm");
        Long reviewerId = authenticatedReviewer();

        // when
        LabelAnnotation first = service.recordAnnotation(AnnotationCommand.of("inc-label-llm",
                AnnotatorType.LLM_JUDGE, null, "service:judge-run-1", "rubric-v1",
                EventOccurrence.ABSENT, HelpNeed.NEEDED, "검토 후보", "bundle-2", null));
        LabelAnnotation second = service.recordAnnotation(AnnotationCommand.of("inc-label-llm",
                AnnotatorType.HUMAN, reviewerId, null, "rubric-v1",
                EventOccurrence.UNDETERMINED, HelpNeed.NOT_ASSESSED, "재판독", "bundle-2", first.getId()));

        // then
        assertThat(first.getReviewerId()).isNull();
        assertThat(first.getAnnotatorRef()).isEqualTo("service:judge-run-1");
        assertThat(first.getEventOccurrence()).isEqualTo(EventOccurrence.ABSENT);
        assertThat(first.getHelpNeed()).isEqualTo(HelpNeed.NEEDED);
        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(second.getSupersedesAnnotationId()).isEqualTo(first.getId());
        assertThat(labels.findByIncidentRefForUpdate("inc-label-llm").orElseThrow().getSource())
                .isEqualTo(IncidentLabelSource.UNREVIEWED);
        assertThat(annotations.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("오래된 현재 판독으로 채택하면 기존 선택을 덮지 않는다")
    void 오래된_현재_판독으로_채택하면_기존_선택을_덮지_않는다() {
        // given
        incident("inc-label-conflict");
        Long reviewerId = authenticatedReviewer();
        LabelAnnotation annotation = service.recordAnnotation(AnnotationCommand.of("inc-label-conflict",
                AnnotatorType.HUMAN, reviewerId, null, "rubric-v1", EventOccurrence.PRESENT,
                HelpNeed.NEEDED, null, null, null));
        service.setCurrentLabel("inc-label-conflict", annotation.getId(), null);

        // when / then
        assertThatThrownBy(() -> service.setCurrentLabel("inc-label-conflict", annotation.getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_LABEL_CONFLICT);
        assertThat(labels.findByIncidentRefForUpdate("inc-label-conflict").orElseThrow()
                .getCurrentAnnotationId()).isEqualTo(annotation.getId());
    }

    @Test
    @DisplayName("다른 사건의 판독을 채택하면 현재 라벨을 바꾸지 않는다")
    void 다른_사건의_판독을_채택하면_현재_라벨을_바꾸지_않는다() {
        // given
        incident("inc-label-left");
        incident("inc-label-right");
        Long reviewerId = authenticatedReviewer();
        LabelAnnotation left = service.recordAnnotation(AnnotationCommand.of("inc-label-left",
                AnnotatorType.HUMAN, reviewerId, null, "rubric-v1", EventOccurrence.PRESENT,
                HelpNeed.NEEDED, null, null, null));
        service.recordAnnotation(AnnotationCommand.of("inc-label-right",
                AnnotatorType.HUMAN, reviewerId, null, "rubric-v1", EventOccurrence.ABSENT,
                HelpNeed.NOT_NEEDED, null, null, null));

        // when / then
        assertThatThrownBy(() -> service.setCurrentLabel("inc-label-right", left.getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INCIDENT_LABEL_INVALID);
        IncidentLabel right = labels.findByIncidentRefForUpdate("inc-label-right").orElseThrow();
        assertThat(right.getCurrentAnnotationId()).isNull();
        assertThat(right.getEventOccurrence()).isEqualTo(EventOccurrence.NOT_ASSESSED);
    }

    @Test
    @DisplayName("다른 관리자 ID로 사람 판독을 기록하면 접근을 거부한다")
    void 다른_관리자_ID로_사람_판독을_기록하면_접근을_거부한다() {
        // given
        incident("inc-label-forged");
        Long reviewerId = authenticatedReviewer();

        // when / then
        assertThatThrownBy(() -> service.recordAnnotation(AnnotationCommand.of("inc-label-forged",
                AnnotatorType.HUMAN, reviewerId + 1, null, "rubric-v1",
                EventOccurrence.PRESENT, HelpNeed.NEEDED, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(annotations.count()).isZero();
    }

    private Long authenticatedReviewer() {
        Member reviewer = members.save(Member.createAdminMember("판독자", "01099999999"));
        given(securityUtil.getCurrentMemberId()).willReturn(reviewer.getId());
        return reviewer.getId();
    }

    private void incident(String ref) {
        Member senior = members.save(Member.createMember(MemberType.SENIOR, "시니어", "010" + ref.hashCode()));
        incidents.save(Incident.builder().incidentRef(ref).memberId(senior.getId())
                .kind(IncidentKind.HR_ANOMALY).openedAtMs(1L).respondByMs(60_001L).build());
    }
}
