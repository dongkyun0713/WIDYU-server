package com.widyu.global.config;

import com.widyu.global.websocket.JwtChannelInterceptor;
import com.widyu.global.websocket.JwtHandshakeInterceptor;
import com.widyu.global.websocket.PilotStompAllowlistInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final ThreadPoolTaskExecutor websocketInboundExecutor;
    private final ObjectProvider<PilotStompAllowlistInterceptor> pilotStompAllowlistInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/location")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 클라이언트가 구독할 prefix
        registry.enableSimpleBroker("/topic", "/queue");

        // 클라이언트가 메시지 전송할 때 사용할 prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(websocketInboundExecutor);

        // 실증(pilot)에서는 허용 목록 인터셉터를 인증 앞단에 두어 미허용 목적지를 먼저 차단한다.
        PilotStompAllowlistInterceptor pilotAllowlist = pilotStompAllowlistInterceptor.getIfAvailable();
        if (pilotAllowlist != null) {
            registration.interceptors(pilotAllowlist, jwtChannelInterceptor);
            return;
        }

        registration.interceptors(jwtChannelInterceptor);
    }
}
