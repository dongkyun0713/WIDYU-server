package com.widyu.infrastructure.external.pay;

import com.widyu.pay.api.dto.request.CancelRequest;
import com.widyu.pay.api.dto.request.PaymentConfirmRequest;
import com.widyu.pay.api.dto.response.PaymentConfirmResponse;
import com.widyu.pay.config.PaymentFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "paymentClient", url = "${spring.payment.base-url}", configuration = PaymentFeignConfig.class)
public interface PaymentClient {

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentConfirmResponse confirmPayment(@RequestBody PaymentConfirmRequest paymentConfirmRequest);

    @PostMapping(value = "/{paymentKey}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentConfirmResponse cancelPayment(@PathVariable("paymentKey") String paymentKey,
                                         @RequestBody CancelRequest cancelRequest);
}

