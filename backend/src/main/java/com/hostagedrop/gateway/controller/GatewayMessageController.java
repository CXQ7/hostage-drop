package com.hostagedrop.gateway.controller;

import com.hostagedrop.gateway.model.InitiateRequest;
import com.hostagedrop.gateway.model.PayRequest;
import com.hostagedrop.gateway.service.ChatHistoryService;
import com.hostagedrop.gateway.service.OnlineUserQueueBridge;
import com.hostagedrop.gateway.service.TransactionService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

@Controller
public class GatewayMessageController {

    private static final Logger log = LoggerFactory.getLogger(GatewayMessageController.class);

    private final TransactionService transactionService;
        private final OnlineUserQueueBridge onlineUserQueueBridge;
        private final ChatHistoryService chatHistoryService;
    private final SimpMessagingTemplate messagingTemplate;

        @Value("${app.history.recent-limit:120}")
        private int recentHistoryLimit;

        public GatewayMessageController(
            TransactionService transactionService,
            OnlineUserQueueBridge onlineUserQueueBridge,
            ChatHistoryService chatHistoryService,
            SimpMessagingTemplate messagingTemplate
        ) {
        this.transactionService = transactionService;
        this.onlineUserQueueBridge = onlineUserQueueBridge;
        this.chatHistoryService = chatHistoryService;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/initiate")
    public void initiate(@Valid @Payload InitiateRequest request, Principal principal) {
        String sender = resolveUser(principal, request.senderId());
        InitiateRequest effective = new InitiateRequest(
            sender,
            request.receiverId(),
            request.textContent(),
            request.fileName(),
            request.fileContentBase64(),
            request.ttlSeconds()
        );
        Map<String, Object> result = transactionService.initiate(effective);
        log.info("INITIATE accepted: sender={}, receiver={}, txId={}, principal={}",
            sender, effective.receiverId(), result.get("txId"), sender);
        // Online receiver gets an immediate event push; offline users still rely on JMS queue replay.
        messagingTemplate.convertAndSendToUser(effective.receiverId(), "/queue/events", Map.of(
            "type", "BAIT_NOTIFICATION",
            "txId", result.get("txId"),
            "senderId", sender,
            "textContent", defaultString(effective.textContent()),
            "expireAt", result.get("expireAt")
        ));

        messagingTemplate.convertAndSendToUser(sender, "/queue/acks", Map.of(
                "type", "INITIATE_ACK",
                "payload", result
        ));

        chatHistoryService.appendEntry(sender, Map.of(
            "side", "right",
            "type", "file",
            "title", "你发起了一个加锁盲盒",
            "txId", result.get("txId"),
            "expireAt", result.get("expireAt"),
            "fileName", defaultString(effective.fileName()),
            "imageUrl", defaultString(effective.fileContentBase64()),
            "text", joinTexts(
                "目标 " + effective.receiverId() + " · TTL " + result.get("ttlSeconds") + "s",
                effective.textContent()
            )
        ));
        chatHistoryService.appendEntry(sender, Map.of(
            "side", "center",
            "type", "system",
            "title", "担保已写入中间件",
            "txId", result.get("txId"),
            "expireAt", result.get("expireAt"),
            "text", "交易号 " + result.get("txId") + "，绝对过期时间 " + result.get("expireAt")
        ));
        chatHistoryService.appendEntry(effective.receiverId(), Map.of(
            "side", "left",
            "type", "bait",
            "title", sender + " 发来一个上锁盲盒",
            "senderId", sender,
            "txId", result.get("txId"),
            "expireAt", result.get("expireAt"),
            "text", joinTexts("交易号 " + result.get("txId"), effective.textContent())
        ));
    }

    @MessageMapping("/pay")
    public void pay(@Valid @Payload PayRequest request, Principal principal) {
        String payer = resolveUser(principal, request.payerId());
        PayRequest effective = new PayRequest(
            request.txId(),
            payer,
            request.payeeId(),
            request.textContent(),
            request.ransomFileName(),
            request.ransomFileContentBase64()
        );
        log.info("PAY accepted: payer={}, payee={}, txId={}, principal={}",
            payer, effective.payeeId(), effective.txId(), payer);
        messagingTemplate.convertAndSendToUser(effective.payeeId(), "/queue/events", Map.of(
            "type", "RANSOM_FILE",
            "txId", effective.txId(),
            "fromUser", payer,
            "textContent", defaultString(effective.textContent()),
            "fileName", defaultString(effective.ransomFileName()),
            "fileContentBase64", defaultString(effective.ransomFileContentBase64()),
            "eventAt", System.currentTimeMillis()
        ));

        Map<String, Object> result = transactionService.pay(effective);
        messagingTemplate.convertAndSendToUser(payer, "/queue/acks", Map.of(
                "type", "PAY_ACK",
                "payload", result
        ));

        chatHistoryService.appendEntry(payer, Map.of(
            "side", "right",
            "type", "file",
            "title", "你提交了赎金文件",
            "txId", effective.txId(),
            "fileName", defaultString(effective.ransomFileName()),
            "imageUrl", defaultString(effective.ransomFileContentBase64()),
            "text", joinTexts("交易号 " + effective.txId(), effective.textContent())
        ));
        chatHistoryService.appendEntry(effective.payeeId(), Map.of(
            "side", "left",
            "type", "file",
            "title", payer + " 发送了赎金文件",
            "txId", effective.txId(),
            "fileName", defaultString(effective.ransomFileName()),
            "imageUrl", defaultString(effective.ransomFileContentBase64()),
            "text", joinTexts("交易号 " + effective.txId(), effective.textContent())
        ));
        chatHistoryService.appendEntry(payer, Map.of(
            "side", "center",
            "type", "system",
            "txId", effective.txId(),
            "released", Boolean.TRUE.equals(result.get("released")),
            "title", Boolean.TRUE.equals(result.get("released")) ? "仲裁放行" : "仲裁拒绝",
            "text", Boolean.TRUE.equals(result.get("released"))
                ? "交易 " + result.get("txId") + " 已释放真实文件"
                : String.valueOf(result.get("reason"))
        ));
        if (Boolean.TRUE.equals(result.get("released"))) {
            chatHistoryService.appendEntry(payer, Map.of(
                "side", "left",
                "type", "file",
                "title", "真实文件已释放到你的会话",
                "txId", effective.txId(),
                "fileName", defaultString(result.get("releaseFileName")),
                "imageUrl", defaultString(result.get("releaseFileContentBase64")),
                "text", joinTexts("来自 " + defaultString(result.get("releaseSenderId")), defaultString(result.get("releaseTextContent")))
            ));
        }
        }

        @MessageMapping("/ready")
        public void ready(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return;
        }
        String userId = principal.getName();
        onlineUserQueueBridge.activateForUser(userId);
        messagingTemplate.convertAndSendToUser(userId, "/queue/acks", Map.of("type", "READY_ACK"));
        }

        @MessageMapping("/history")
        public void history(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return;
        }
        String userId = principal.getName();
        List<Map<String, Object>> rawItems = chatHistoryService.readRecent(userId, recentHistoryLimit);
        messagingTemplate.convertAndSendToUser(userId, "/queue/history", Map.of(
            "type", "HISTORY_SNAPSHOT",
            "items", annotateHistoryWithStatus(rawItems)
        ));
    }

    private List<Map<String, Object>> annotateHistoryWithStatus(List<Map<String, Object>> rawItems) {
        Map<String, String> latestStatusByTx = new HashMap<>();
        long now = System.currentTimeMillis();

        for (Map<String, Object> item : rawItems) {
            String txId = asString(item.get("txId"));
            if (txId == null || txId.isBlank()) {
                continue;
            }

            if (asBoolean(item.get("released"))) {
                latestStatusByTx.put(txId, "已放行");
                continue;
            }

            Long expireAt = asLong(item.get("expireAt"));
            if (expireAt != null && expireAt > 0 && expireAt < now && !"已放行".equals(latestStatusByTx.get(txId))) {
                latestStatusByTx.put(txId, "已过期");
            }
        }

        List<Map<String, Object>> annotated = new ArrayList<>(rawItems.size());
        for (Map<String, Object> item : rawItems) {
            Map<String, Object> copy = new LinkedHashMap<>(item);
            String txId = asString(item.get("txId"));
            if (txId != null && !txId.isBlank() && latestStatusByTx.containsKey(txId)) {
                copy.put("txStatus", latestStatusByTx.get(txId));
            }
            annotated.add(copy);
        }
        return annotated;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private String defaultString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String joinTexts(String line1, String line2) {
        String first = defaultString(line1).trim();
        String second = defaultString(line2).trim();
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + "\n" + second;
    }

    @MessageExceptionHandler
    public void handleException(Throwable ex, Principal principal) {
        ex.printStackTrace();
        if (principal == null) {
            return;
        }
        messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", Map.of(
                "type", "ERROR",
                "message", ex.getMessage()
        ));
    }

    private String resolveUser(Principal principal, String fallbackUserId) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return fallbackUserId;
    }
}
