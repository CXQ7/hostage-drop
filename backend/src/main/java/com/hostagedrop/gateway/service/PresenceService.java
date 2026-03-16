package com.hostagedrop.gateway.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Service
public class PresenceService {

    private final Map<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();

    @Order(0)
    @EventListener
    public void onConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (accessor.getUser() == null || accessor.getSessionId() == null) {
            return;
        }
        String userId = accessor.getUser().getName();
        sessionToUser.put(accessor.getSessionId(), userId);
        onlineUsers.add(userId);
    }

    @Order(0)
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String userId = sessionToUser.remove(event.getSessionId());
        if (userId == null) {
            return;
        }
        boolean stillOnline = sessionToUser.values().stream().anyMatch(userId::equals);
        if (!stillOnline) {
            onlineUsers.remove(userId);
        }
    }

    public boolean isOnline(String userId) {
        return onlineUsers.contains(userId);
    }
}
