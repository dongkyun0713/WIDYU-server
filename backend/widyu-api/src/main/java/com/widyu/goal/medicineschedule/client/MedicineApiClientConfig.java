package com.widyu.goal.medicineschedule.client;

import com.widyu.global.properties.MedicineProperties;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

public class MedicineApiClientConfig {

    @Bean
    Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    // 검색 경로 타임아웃. 배치는 호출마다 Request.Options를 넘겨 읽기 타임아웃을 늘린다(LLD-0100).
    @Bean
    Request.Options medicineRequestOptions(MedicineProperties medicineProperties) {
        MedicineProperties.Api api = medicineProperties.api();
        return new Request.Options(api.connectTimeout(), api.readTimeout(), true);
    }

    @Bean
    public Decoder feignDecoder() {
        ObjectFactory<HttpMessageConverters> messageConverters = () -> new HttpMessageConverters(new MappingJackson2HttpMessageConverter());
        return new SpringDecoder(messageConverters);
    }

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            // 공공데이터포털 API 호환성을 위한 설정
            requestTemplate.header("Accept", "application/json");
        };
    }
}
