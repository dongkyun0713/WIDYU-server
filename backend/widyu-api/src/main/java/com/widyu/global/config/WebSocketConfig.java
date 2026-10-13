package com.widyu.global.config;

import com.widyu.global.websocket.FamilyTopicOutboundInterceptor;
import com.widyu.global.websocket.JwtChannelInterceptor;
import com.widyu.global.websocket.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
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

    /** 서버·클라이언트 하트비트 간격(ms). 모바일 망에서 빈 시간에도 연결을 유지한다. */
    private static final long[] HEARTBEAT_MS = {10_000L, 10_000L};

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final FamilyTopicOutboundInterceptor familyTopicOutboundInterceptor;
    private final ThreadPoolTaskExecutor websocketInboundExecutor;

    private TaskScheduler messageBrokerTaskScheduler;

    /**
     * @EnableWebSocketMessageBroker가 등록한 브로커 스케줄러를 하트비트에 쓴다. 이 설정 클래스가 그 빈보다
     * 먼저 만들어지므로 지연 주입한다. 새 스케줄러 빈을 만들면 TaskScheduler 타입 주입이 모호해진다.
     */
    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy TaskScheduler messageBrokerTaskScheduler) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

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
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(HEARTBEAT_MS)
                .setTaskScheduler(messageBrokerTaskScheduler);

        // 클라이언트가 메시지 전송할 때 사용할 prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(websocketInboundExecutor);
        registration.interceptors(jwtChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(familyTopicOutboundInterceptor);
    }
}
