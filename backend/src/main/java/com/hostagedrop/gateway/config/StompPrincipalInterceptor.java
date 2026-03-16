package com.hostagedrop.gateway.config;

import java.security.Principal;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StompPrincipalInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompPrincipalInterceptor.class);

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String userId = accessor.getFirstNativeHeader("userId");
            if (userId != null && !userId.isBlank()) {
                accessor.setUser(new StompUserPrincipal(userId));
                log.info("STOMP CONNECT accepted: userId={}, sessionId={}", userId, accessor.getSessionId());
                // 核心修复：必须使用 MessageBuilder 重新打包不可变的消息体
                return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
            }
            log.warn("STOMP CONNECT missing userId, sessionId={}", accessor.getSessionId());
        }
        return message;
    }

    private record StompUserPrincipal(String value) implements Principal {

        @Override
        public String getName() {
            return value;
        }
    }
}
