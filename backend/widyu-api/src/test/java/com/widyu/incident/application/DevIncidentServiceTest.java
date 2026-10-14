package com.widyu.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.dto.request.DevHeartEmergencyRequest;
import com.widyu.incident.dto.response.IncidentResponse;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("개발용 심박 위급 발생 서비스 테스트")
class DevIncidentServiceTest {

    private static final Long SENIOR_ID = 10L;
    private static final Long GUARDIAN_ID = 20L;
    private static final long OPENED_AT_MS = 1_760_000_000_000L;

    @Mock private IncidentService incidentService;
    @Mock private IncidentRepository incidentRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private FamilyAccessService familyAccessService;
    @InjectMocks private DevIncidentService devIncidentService;

    @Test
    @DisplayName("시니어가 호출하면 열린 심박 사건을 끝내고 본인 사건을 새로 연다")
    void 시니어가_호출하면_열린_심박_사건을_끝내고_본인_사건을_새로_연다() {
        // given
        given(memberRepository.findById(SENIOR_ID)).willReturn(Optional.of(member(SENIOR_ID, MemberType.SENIOR)));
        Incident previous = incident("inc-old");
        given(incidentRepository.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(SENIOR_ID), eq(IncidentKind.HR_ANOMALY), anyList())).willReturn(Optional.of(previous));
        given(incidentService.openForAlert(SENIOR_ID, IncidentKind.HR_ANOMALY)).willReturn(incident("inc-new"));

        // when
        IncidentResponse response = devIncidentService.triggerHeartEmergency(SENIOR_ID, null);

        // then
        assertThat(previous.getSituationEndedAtMs()).isNotNull();
        assertThat(response.incidentId()).isEqualTo("inc-new");
    }

    @Test
    @DisplayName("보호자가 시니어를 지정하면 가족을 확인한 뒤 그 시니어 사건을 연다")
    void 보호자가_시니어를_지정하면_가족을_확인한_뒤_그_시니어_사건을_연다() {
        // given
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(member(GUARDIAN_ID, MemberType.GUARDIAN)));
        given(incidentRepository.findFirstByMemberIdAndKindAndStateInAndSituationEndedAtMsIsNullOrderByOpenedAtMsDesc(
                eq(SENIOR_ID), eq(IncidentKind.HR_ANOMALY), anyList())).willReturn(Optional.empty());
        given(incidentService.openForAlert(SENIOR_ID, IncidentKind.HR_ANOMALY)).willReturn(incident("inc-new"));

        // when
        IncidentResponse response = devIncidentService.triggerHeartEmergency(
                GUARDIAN_ID, new DevHeartEmergencyRequest(SENIOR_ID));

        // then
        then(familyAccessService).should().verifyActiveFamilyAccess(GUARDIAN_ID, SENIOR_ID);
        assertThat(response.incidentId()).isEqualTo("inc-new");
    }

    @Test
    @DisplayName("보호자가 시니어를 지정하지 않으면 잘못된 요청 예외가 발생한다")
    void 보호자가_시니어를_지정하지_않으면_잘못된_요청_예외가_발생한다() {
        // given
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(member(GUARDIAN_ID, MemberType.GUARDIAN)));

        // when & then
        assertThatThrownBy(() -> devIncidentService.triggerHeartEmergency(GUARDIAN_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST);
        then(incidentService).should(never()).openForAlert(anyLong(), any());
    }

    @Test
    @DisplayName("다른 가족 보호자가 호출하면 권한 예외가 발생하고 사건을 열지 않는다")
    void 다른_가족_보호자가_호출하면_권한_예외가_발생하고_사건을_열지_않는다() {
        // given
        given(memberRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(member(GUARDIAN_ID, MemberType.GUARDIAN)));
        willThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .given(familyAccessService).verifyActiveFamilyAccess(GUARDIAN_ID, SENIOR_ID);

        // when & then
        assertThatThrownBy(() -> devIncidentService.triggerHeartEmergency(
                GUARDIAN_ID, new DevHeartEmergencyRequest(SENIOR_ID)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(incidentService).should(never()).openForAlert(anyLong(), any());
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private Incident incident(String incidentRef) {
        return Incident.builder().incidentRef(incidentRef).memberId(SENIOR_ID).kind(IncidentKind.HR_ANOMALY)
                .openedAtMs(OPENED_AT_MS).respondByMs(OPENED_AT_MS + 90_000L).build();
    }
}
