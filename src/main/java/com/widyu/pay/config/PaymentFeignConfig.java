package com.widyu.pay.config;

import com.widyu.pay.api.dto.request.PaymentProperties;
import feign.Request;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;

public class PaymentFeignConfig {

    private final PaymentProperties paymentProperties;

    public PaymentFeignConfig(PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, true);
    }

    @Bean
    public PaymentErrorDecoder paymentErrorDecoder() {
        return new PaymentErrorDecoder();
    }

    @Bean
    PaymentAuthInterceptor paymentAuthInterceptor() {
        return new PaymentAuthInterceptor(paymentProperties);
    }

    @Bean
    PaymentLoggingInterceptor paymentLoggingInterceptor() {
        return new PaymentLoggingInterceptor();
    }
}
