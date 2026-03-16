package com.hostagedrop.gateway.service;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Service
public class OnlineUserQueueBridge {

    private static final Logger log = LoggerFactory.getLogger(OnlineUserQueueBridge.class);

    private final DefaultJmsListenerContainerFactory listenerContainerFactory;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final ConcurrentMap<String, MessageListenerContainer> userContainers = new ConcurrentHashMap<>();

    @Value("${app.jms.user-queue-prefix}")
    private String userQueuePrefix;

    public OnlineUserQueueBridge(
            DefaultJmsListenerContainerFactory listenerContainerFactory,
            SimpMessagingTemplate messagingTemplate,
            PresenceService presenceService
    ) {
        this.listenerContainerFactory = listenerContainerFactory;
        this.messagingTemplate = messagingTemplate;
        this.presenceService = presenceService;
    }

    public void activateForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        log.info("Activate JMS bridge listener by ready signal: userId={}", userId);
        ensureListener(userId);
    }

    @Order(1)
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (accessor.getUser() == null) {
            return;
        }
        String userId = accessor.getUser().getName();
        if (!presenceService.isOnline(userId)) {
            stopListener(userId);
        }
    }

    private void ensureListener(String userId) {
        userContainers.computeIfAbsent(userId, key -> {
            SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
            endpoint.setId("user-queue-" + key);
            endpoint.setDestination(queueOf(key));
            endpoint.setMessageListener(message -> dispatchToUser(key, message));
            MessageListenerContainer container = listenerContainerFactory.createListenerContainer(endpoint);
            container.start();
            return container;
        });
    }

    private void stopListener(String userId) {
        MessageListenerContainer container = userContainers.remove(userId);
        if (container != null) {
            container.stop();
            if (container instanceof DisposableBean disposableBean) {
                try {
                    disposableBean.destroy();
                } catch (Exception ex) {
                    throw new IllegalStateException("Failed to destroy JMS listener container", ex);
                }
            }
        }
    }

    private void dispatchToUser(String userId, Message message) {
        try {
            log.info("Bridge JMS -> STOMP: userId={}, type={}, txId={}",
                    userId, message.getStringProperty("type"), message.getJMSCorrelationID());
            messagingTemplate.convertAndSendToUser(userId, "/queue/events", toPayload(message));
        } catch (JMSException ex) {
            throw new IllegalStateException("Failed to bridge JMS message to STOMP", ex);
        }
    }

    private Map<String, Object> toPayload(Message message) throws JMSException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", message.getStringProperty("type"));
        payload.put("txId", message.getJMSCorrelationID());
        payload.put("fromUser", message.getStringProperty("fromUser"));
        payload.put("senderId", message.getStringProperty("senderId"));
        payload.put("fileName", message.getStringProperty("fileName"));
        payload.put("textContent", message.getStringProperty("textContent"));

        long expireAt = message.getLongProperty("expireAt");
        if (expireAt > 0) {
            payload.put("expireAt", expireAt);
        }
        long eventAt = message.getLongProperty("eventAt");
        if (eventAt > 0) {
            payload.put("eventAt", eventAt);
        }
        if (message instanceof TextMessage textMessage) {
            String content = textMessage.getText();
            if (content != null && !content.isBlank()) {
                payload.put("fileContentBase64", content);
            }
        }
        return payload;
    }

    private String queueOf(String userId) {
        return userQueuePrefix + userId;
    }
}
