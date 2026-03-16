package com.hostagedrop.gateway.service;

import com.hostagedrop.gateway.model.InitiateRequest;
import com.hostagedrop.gateway.model.PayRequest;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final JmsTemplate jmsTemplate;

    @Value("${app.jms.escrow-queue}")
    private String escrowQueue;

    @Value("${app.jms.user-queue-prefix}")
    private String userQueuePrefix;

    @Value("${app.jms.default-ttl-seconds}")
    private long defaultTtlSeconds;

    @Value("${app.jms.bait-notification-ttl-seconds:86400}")
    private long baitNotificationTtlSeconds;

    public TransactionService(JmsTemplate jmsTemplate) {
        this.jmsTemplate = jmsTemplate;
    }

    public Map<String, Object> initiate(InitiateRequest request) {
        ensureContentPresent(request.textContent(), request.fileContentBase64());

        String txId = "TX-" + UUID.randomUUID();
        long ttlSeconds = request.ttlSeconds() > 0 ? request.ttlSeconds() : defaultTtlSeconds;
        long ttlMillis = ttlSeconds * 1000;
        long expireAt = Instant.now().toEpochMilli() + ttlMillis;

        sendEscrowMessage(txId, request, expireAt);

        String receiverQueue = queueOf(request.receiverId());
        sendBaitMessage(txId, request, expireAt, receiverQueue);

        return Map.of(
                "txId", txId,
                "expireAt", expireAt,
                "ttlSeconds", ttlSeconds,
                "receiverQueue", receiverQueue,
                "escrowQueue", escrowQueue
        );
    }

    public Map<String, Object> pay(PayRequest request) {
        ensureContentPresent(request.textContent(), request.ransomFileContentBase64());

        long paidAt = Instant.now().toEpochMilli();

        sendUserQueueMessage(
                queueOf(request.payeeId()),
                request.payeeId(),
                request.txId(),
                "RANSOM_FILE",
                request.ransomFileName(),
                request.payerId(),
                request.textContent(),
                request.ransomFileContentBase64(),
                paidAt,
                0L
        );

        Message escrow = jmsTemplate.receiveSelected(escrowQueue, "JMSCorrelationID='" + request.txId() + "'");
        if (escrow == null) {
            return Map.of(
                    "released", false,
                    "reason", "Escrow not found or expired"
            );
        }

        try {
            String fileName = escrow.getStringProperty("fileName");
            String senderId = escrow.getStringProperty("senderId");
            String textContent = escrow.getStringProperty("textContent");
            String filePayload = ((jakarta.jms.TextMessage) escrow).getText();
            sendUserQueueMessage(
                queueOf(request.payerId()),
                request.payerId(),
                request.txId(),
                "REAL_FILE_RELEASED",
                fileName,
                senderId,
                textContent,
                filePayload,
                Instant.now().toEpochMilli(),
                0L
            );

            return Map.of(
                    "released", true,
                    "txId", request.txId(),
                    "releasedTo", request.payerId(),
                    "releaseFileName", fileName == null ? "" : fileName,
                    "releaseFileContentBase64", filePayload == null ? "" : filePayload,
                    "releaseTextContent", textContent == null ? "" : textContent,
                    "releaseSenderId", senderId == null ? "" : senderId
            );
        } catch (JMSException ex) {
            throw new IllegalStateException("Failed to parse escrow message", ex);
        }
    }

    private void sendEscrowMessage(String txId, InitiateRequest request, long expireAt) {
        long ttlMillis = Math.max(expireAt - Instant.now().toEpochMilli(), 1L);
        jmsTemplate.execute(session -> {
            Message msg = session.createTextMessage(nullToEmpty(request.fileContentBase64()));
            msg.setJMSCorrelationID(txId);
            msg.setStringProperty("type", "REAL_FILE");
            msg.setStringProperty("senderId", request.senderId());
            msg.setStringProperty("receiverId", request.receiverId());
            setOptionalStringProperty(msg, "fileName", request.fileName());
            setOptionalStringProperty(msg, "textContent", request.textContent());
            msg.setLongProperty("expireAt", expireAt);

            MessageProducer producer = session.createProducer(session.createQueue(escrowQueue));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(msg, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, ttlMillis);
            producer.close();
            return null;
        }, true);
    }

    private void sendBaitMessage(String txId, InitiateRequest request, long expireAt, String receiverQueue) {
        // 盲盒通知与担保消息解耦：通知保留更久，晚登录用户也能看到交易信息。
        long ttlMillis = baitNotificationTtlSeconds > 0 ? baitNotificationTtlSeconds * 1000 : 0L;
        jmsTemplate.execute(session -> {
            Message msg = session.createTextMessage("bait");
            msg.setJMSCorrelationID(txId);
            msg.setStringProperty("type", "BAIT_NOTIFICATION");
            msg.setStringProperty("toUser", request.receiverId());
            msg.setStringProperty("senderId", request.senderId());
            setOptionalStringProperty(msg, "textContent", request.textContent());
            msg.setLongProperty("expireAt", expireAt);
            msg.setJMSReplyTo(session.createQueue(queueOf(request.senderId())));

            MessageProducer producer = session.createProducer(session.createQueue(receiverQueue));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(msg, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, ttlMillis);
            producer.close();
            return null;
        }, true);
    }

    private void sendUserQueueMessage(
            String destinationQueue,
            String toUser,
            String txId,
            String type,
            String fileName,
            String fromUser,
            String textContent,
            String filePayload,
            long eventAt,
            long ttlMillis
    ) {
        jmsTemplate.execute(session -> {
            Message msg = session.createTextMessage(nullToEmpty(filePayload));
            msg.setJMSCorrelationID(txId);
            msg.setStringProperty("type", type);
            msg.setStringProperty("toUser", toUser);
            msg.setStringProperty("fromUser", fromUser);
            setOptionalStringProperty(msg, "fileName", fileName);
            setOptionalStringProperty(msg, "textContent", textContent);
            msg.setLongProperty("eventAt", eventAt);

            MessageProducer producer = session.createProducer(session.createQueue(destinationQueue));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(msg, DeliveryMode.PERSISTENT, Message.DEFAULT_PRIORITY, ttlMillis);
            producer.close();
            return null;
        }, true);
    }

    private String queueOf(String userId) {
        return userQueuePrefix + userId;
    }

    private void ensureContentPresent(String textContent, String fileContentBase64) {
        if (isBlank(textContent) && isBlank(fileContentBase64)) {
            throw new IllegalArgumentException("Text and attachment cannot both be empty");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void setOptionalStringProperty(Message msg, String key, String value) throws JMSException {
        if (!isBlank(value)) {
            msg.setStringProperty(key, value);
        }
    }
}

