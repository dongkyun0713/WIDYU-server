package com.widyu.pay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.pay.PaymentOrderStatus;
import com.widyu.pay.PaymentStatus;
import com.widyu.pay.application.PaymentService;
import com.widyu.pay.dto.response.PaymentConfirmResponse;
import com.widyu.pay.dto.response.PaymentOrderResponse;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentController API 테스트")
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("주문 생성 요청은 표준 응답 형태로 반환된다")
    void 주문_생성_API() throws Exception {
        PaymentOrderResponse response = new PaymentOrderResponse(
                "order_123456",
                "POINT_10000",
                "포인트 충전 10,000원",
                10000,
                10000,
                PaymentOrderStatus.CREATED,
                ZonedDateTime.parse("2026-05-28T12:00:00+09:00")
        );
        given(paymentService.createOrder(any())).willReturn(response);

        mockMvc.perform(post("/api/v1/payment/orders")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": "POINT_10000"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAY_2000"))
                .andExpect(jsonPath("$.message").value("주문 생성 성공"))
                .andExpect(jsonPath("$.data.orderId").value("order_123456"))
                .andExpect(jsonPath("$.data.packageId").value("POINT_10000"))
                .andExpect(jsonPath("$.data.amount").value(10000));
    }

    @Test
    @DisplayName("결제 승인 요청의 필수값이 비어 있으면 400을 반환한다")
    void 결제_승인_API_검증() throws Exception {
        mockMvc.perform(post("/api/v1/payment")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "order_123456",
                                  "paymentKey": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()))
                .andExpect(jsonPath("$.message").value("결제 키는 필수입니다 (paymentKey)"))
                .andExpect(jsonPath("$.errors[0].field").value("paymentKey"))
                .andExpect(jsonPath("$.errors[0].reason").value("결제 키는 필수입니다."));

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("결제 취소는 요청 본문 없이도 호출할 수 있다")
    void 결제_취소_API_본문_없음() throws Exception {
        PaymentConfirmResponse response = new PaymentConfirmResponse();
        ReflectionTestUtils.setField(response, "paymentKey", "pay_123456");
        ReflectionTestUtils.setField(response, "orderId", "order_123456");
        ReflectionTestUtils.setField(response, "status", PaymentStatus.CANCELED);
        given(paymentService.cancelPayment("pay_123456", null)).willReturn(response);

        mockMvc.perform(post("/api/v1/payment/pay_123456/cancel")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAY_2002"))
                .andExpect(jsonPath("$.data.paymentKey").value("pay_123456"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        verify(paymentService).cancelPayment(eq("pay_123456"), isNull());
    }
}
