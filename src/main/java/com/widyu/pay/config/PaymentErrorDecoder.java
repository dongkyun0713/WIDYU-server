package com.widyu.pay.config;

import feign.Response;
import feign.codec.ErrorDecoder;

public class PaymentErrorDecoder implements ErrorDecoder {

    private final ErrorDecoder defaultDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        // 여기서 status code나 body에 따라 커스텀 예외 처리 가능
        if (response.status() == 400) {
            return new IllegalArgumentException("결제 요청이 잘못되었습니다.");
        } else if (response.status() == 401) {
            return new SecurityException("인증에 실패했습니다.");
        }

        return defaultDecoder.decode(methodKey, response);
    }
}
