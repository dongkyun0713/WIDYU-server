package com.widyu.goal.medicineschedule.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.application.ExternalMedicineService;
import com.widyu.goal.medicineschedule.application.MedicationAlarmSyncService;
import com.widyu.goal.medicineschedule.application.MedicationProofService;
import com.widyu.goal.medicineschedule.application.MedicineScheduleService;
import com.widyu.goal.medicineschedule.dto.request.CreateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.dto.request.UpdateMedicineScheduleRequest;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleChangeResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineScheduleIdResponse;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.application.FamilyAccessService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicineScheduleController 방장 쓰기 권한 테스트")
class MedicineScheduleControllerTest {
    private static final String BASE_PATH = "/api/v1/goals/medicine-schedules";
    private static final String REQUEST_BODY = """
            {"alarmTime":"08:00","categories":[{"name":"아침 약",
             "medicines":[{"itemName":"등록된 약품명","dose":1}]}]}
            """;

    @Mock private MedicineScheduleService medicineScheduleService;
    @Mock private MedicationAlarmSyncService medicationAlarmSyncService;
    @Mock private MedicationProofService medicationProofService;
    @Mock private ExternalMedicineService externalMedicineService;
    @Mock private FamilyAccessService familyAccessService;
    @Mock private MemberUtil memberUtil;
    @InjectMocks private MedicineScheduleController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("비방장 보호자가 복약 일정 등록·수정·삭제를 요청하면 403을 반환한다")
    void 비방장_보호자가_복약_일정_등록_수정_삭제를_요청하면_403을_반환한다() throws Exception {
        // given
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(guardian.getId()).willReturn(20L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        willThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .given(familyAccessService).verifyLeaderAccess(20L, 10L);
        MockMvc mvc = mockMvc();

        // when / then
        mvc.perform(post(BASE_PATH).param("memberId", "10").contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        mvc.perform(put(BASE_PATH + "/100").param("memberId", "10").contentType(APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        mvc.perform(delete(BASE_PATH + "/100").param("memberId", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        then(medicineScheduleService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("시니어가 본인의 복약 일정 등록·수정·삭제를 요청하면 403을 반환한다")
    void 시니어가_본인의_복약_일정_등록_수정_삭제를_요청하면_403을_반환한다() throws Exception {
        // given
        Member senior = org.mockito.Mockito.mock(Member.class);
        given(senior.getType()).willReturn(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        MockMvc mvc = mockMvc();

        // when / then
        mvc.perform(post(BASE_PATH).contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        mvc.perform(put(BASE_PATH + "/100").param("memberId", "10").contentType(APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        mvc.perform(delete(BASE_PATH + "/100").param("memberId", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_4030"));
        then(familyAccessService).shouldHaveNoInteractions();
        then(medicineScheduleService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("방장이 복약 일정 등록·수정·삭제를 요청하면 다음 날 적용 응답을 반환한다")
    void 방장이_복약_일정_등록_수정_삭제를_요청하면_다음_날_적용_응답을_반환한다() throws Exception {
        // given
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(guardian.getId()).willReturn(20L);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        LocalDate effectiveFromDate = LocalDate.of(2026, 10, 2);
        given(medicineScheduleService.createSchedule(any(CreateMedicineScheduleRequest.class), eq(10L)))
                .willReturn(MedicineScheduleIdResponse.of(100L, 7, effectiveFromDate));
        given(medicineScheduleService.updateSchedule(eq(100L), any(UpdateMedicineScheduleRequest.class), eq(10L)))
                .willReturn(MedicineScheduleIdResponse.of(101L, 8, effectiveFromDate));
        given(medicineScheduleService.deleteSchedule(101L, 10L))
                .willReturn(MedicineScheduleChangeResponse.of(9, effectiveFromDate));
        MockMvc mvc = mockMvc();

        // when / then
        mvc.perform(post(BASE_PATH).param("memberId", "10").contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineScheduleId").value(100))
                .andExpect(jsonPath("$.data.scheduleRevision").value(7))
                .andExpect(jsonPath("$.data.effectiveFromDate").value("2026-10-02"));
        mvc.perform(put(BASE_PATH + "/100").param("memberId", "10").contentType(APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineScheduleId").value(101))
                .andExpect(jsonPath("$.data.scheduleRevision").value(8))
                .andExpect(jsonPath("$.data.effectiveFromDate").value("2026-10-02"));
        mvc.perform(delete(BASE_PATH + "/101").param("memberId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleRevision").value(9))
                .andExpect(jsonPath("$.data.effectiveFromDate").value("2026-10-02"));
    }

    @Test
    @DisplayName("방장이 대상 시니어 없이 복약 일정 변경을 요청하면 400을 반환한다")
    void 방장이_대상_시니어_없이_복약_일정_변경을_요청하면_400을_반환한다() throws Exception {
        // given
        Member guardian = org.mockito.Mockito.mock(Member.class);
        given(guardian.getType()).willReturn(MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(guardian);

        // when / then
        mockMvc().perform(delete(BASE_PATH + "/100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
        then(familyAccessService).shouldHaveNoInteractions();
        then(medicineScheduleService).shouldHaveNoInteractions();
    }
}
