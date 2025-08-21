package com.widyu.pay.config;

import com.widyu.pay.api.dto.PaymentConfirmRequest;
import com.widyu.pay.api.dto.PaymentConfirmResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "paymentClient", url = "${spring.payment.base-url}", configuration = PaymentFeignConfig.class)
public interface PaymentClient {

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentConfirmResponse confirmPayment(@RequestBody PaymentConfirmRequest paymentConfirmRequest);

}
