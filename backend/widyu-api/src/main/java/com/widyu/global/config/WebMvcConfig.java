package com.widyu.global.config;

import com.widyu.global.interceptor.AdminAccessLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAccessLogInterceptor adminAccessLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAccessLogInterceptor)
                .addPathPatterns("/api/v1/admin/**", "/api/v1/auth/admin/**");
    }
}
