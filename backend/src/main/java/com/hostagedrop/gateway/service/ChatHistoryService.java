package com.hostagedrop.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatHistoryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> userLocks = new ConcurrentHashMap<>();

    @Value("${app.history.base-dir:data/history}")
    private String baseDir;

    public ChatHistoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(baseDir));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize history directory", ex);
        }
    }

    public void appendEntry(String userId, Map<String, Object> entry) {
        if (userId == null || userId.isBlank() || entry == null || entry.isEmpty()) {
            return;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(entry);
        snapshot.putIfAbsent("time", LocalTime.now().format(TIME_FORMATTER));

        Object lock = userLocks.computeIfAbsent(userId, key -> new Object());
        synchronized (lock) {
            try {
                String line = objectMapper.writeValueAsString(snapshot) + System.lineSeparator();
                Files.writeString(
                        fileOf(userId),
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to append history entry", ex);
            }
        }
    }

    public List<Map<String, Object>> readRecent(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }

        Path file = fileOf(userId);
        if (!Files.exists(file)) {
            return List.of();
        }

        Object lock = userLocks.computeIfAbsent(userId, key -> new Object());
        synchronized (lock) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int from = Math.max(0, lines.size() - limit);
                List<Map<String, Object>> result = new ArrayList<>();
                for (int i = from; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    result.add(objectMapper.readValue(line, MAP_TYPE));
                }
                return result;
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read history", ex);
            }
        }
    }

    private Path fileOf(String userId) {
        String safeUser = userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return Paths.get(baseDir, safeUser + ".jsonl");
    }
}
