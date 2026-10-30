package com.widyu.goal.addressbookmark.client;

import com.widyu.global.properties.JusoProperties;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

public class JusoApiClientConfig {

    @Bean
    Logger.Level jusoFeignLoggerLevel() {
        return Logger.Level.BASIC;
    }

    // 지정하지 않으면 OpenFeign 기본값(연결 10초, 읽기 60초)으로 사용자 요청이 오래 묶인다(#856, LLD-0100과 같은 값).
    @Bean
    Request.Options jusoRequestOptions(JusoProperties jusoProperties) {
        return new Request.Options(jusoProperties.connectTimeout(), jusoProperties.readTimeout(), true);
    }

    @Bean
    public Decoder jusoFeignDecoder() {
        ObjectFactory<HttpMessageConverters> messageConverters = () ->
                new HttpMessageConverters(new MappingJackson2HttpMessageConverter());
        return new SpringDecoder(messageConverters);
    }

    @Bean
    public RequestInterceptor jusoRequestInterceptor() {
        return requestTemplate -> requestTemplate.header("Accept", "application/json");
    }
}
