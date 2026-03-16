package com.hostagedrop.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // 10MB 原图经 Base64 + STOMP 包装后会放大，给出更安全的帧预算。
    private static final int WS_FRAME_LIMIT_BYTES = 16 * 1024 * 1024;

    private final StompPrincipalInterceptor stompPrincipalInterceptor;

    public WebSocketConfig(StompPrincipalInterceptor stompPrincipalInterceptor) {
        this.stompPrincipalInterceptor = stompPrincipalInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompPrincipalInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(WS_FRAME_LIMIT_BYTES);
        registration.setSendBufferSizeLimit(WS_FRAME_LIMIT_BYTES);
        registration.setSendTimeLimit(20000); // 放宽超时时间
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(WS_FRAME_LIMIT_BYTES);
        container.setMaxBinaryMessageBufferSize(WS_FRAME_LIMIT_BYTES);
        return container;
    }
}

