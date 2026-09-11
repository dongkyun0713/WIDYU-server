package com.widyu.global.config;

import com.widyu.global.filter.JwtAuthenticationFilter;
import com.widyu.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    @Bean
    @Profile("!pilot")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF 비활성화, 기본 인증 및 폼 로그인 비활성화, 세션 STATELESS 설정
        http.httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // SockJS iframe fallback 경로에만 SAMEORIGIN, 그 외 전체 DENY
        AntPathRequestMatcher sockJsIframe = new AntPathRequestMatcher("/ws/location/iframe.html");
        http.headers(headers -> headers
                .frameOptions(frame -> frame.disable())
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        sockJsIframe,
                        new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN)
                ))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(sockJsIframe),
                        new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY)
                ))
        );

        // 요청 경로에 대한 인가 설정
        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        // SockJS 핸드쉐이크 및 통신 경로 허용
                                        .requestMatchers("/ws/location/**").permitAll()
                                        // 인증/인가 관련 API 경로 허용
                                        .requestMatchers("/api/v1/auth/**").permitAll()
                                        // Swagger UI 및 API 문서 경로 허용
                                        .requestMatchers(
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**",
                                                "/swagger-resources/**"
                                        ).permitAll()
                                        // Actuator 엔드포인트 허용 (Prometheus 메트릭 수집)
                                        .requestMatchers("/actuator/**").permitAll()
                                        // 관리자 API
                                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                                        // 그 외 모든 요청은 인증 필요
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exception ->
                                exception.authenticationEntryPoint(
                                        (request, response, authException) ->
                                                response.setStatus(401)));

        // JWT 인증 필터 추가
        http.addFilterBefore(
                jwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    @Profile("pilot")
    public SecurityFilterChain pilotSecurityFilterChain(HttpSecurity http) throws Exception {
        // 실증 전용 체인: 허용한 실증 기능만 인증 후 통과시키고 그 외 모든 요청은 차단한다.
        http.httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        AntPathRequestMatcher sockJsIframe = new AntPathRequestMatcher("/ws/location/iframe.html");
        http.headers(headers -> headers
                .frameOptions(frame -> frame.disable())
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        sockJsIframe,
                        new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN)
                ))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        new NegatedRequestMatcher(sockJsIframe),
                        new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY)
                ))
        );

        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        // 실시간 위치 WebSocket 핸드쉐이크
                                        .requestMatchers("/ws/location/**").permitAll()
                                        // 컨테이너 헬스 체크만 허용 (그 외 Actuator·Swagger는 차단)
                                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                                        // 관리자 API는 지정 운영 범위로 제한
                                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                                        // 실증 허용 기능 (인증 필요). 가상 복약 조회는 정확한 조회 경로를 앱팀과 확정해 5단계에서 추가한다.
                                        .requestMatchers(
                                                "/api/v1/heart-rate/**",
                                                "/api/v1/location/realtime/**",
                                                "/api/v1/goals/parent-locations/**",
                                                "/api/v1/home/**",
                                                "/api/v1/goals/home/**",
                                                "/api/v1/fcm/token/**",
                                                // SockJS 연결용 일회용 토큰 발급 (LLD-0001)
                                                "/api/v1/ws/token"
                                        ).authenticated()
                                        // 그 외 모든 요청 차단 (가입·소셜·SMS·가족 초대·앨범/업로드·결제·외부 검색 등)
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        exception ->
                                exception.authenticationEntryPoint(
                                        (request, response, authException) ->
                                                response.setStatus(401)));

        http.addFilterBefore(
                jwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider) {
        return new JwtAuthenticationFilter(jwtTokenProvider);
    }
}