package com.widyu.pay.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaymentLoggingInterceptor implements RequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(PaymentLoggingInterceptor.class);

    @Override
    public void apply(RequestTemplate template) {
        logger.info("Payment Request: {} {}", template.method(), template.url());
        logger.info("Payment Request Body: {}", new String(template.body()));
    }
}
