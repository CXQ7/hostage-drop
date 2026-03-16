package com.hostagedrop.gateway.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InitiateRequest(
        @NotBlank String senderId,
        @NotBlank String receiverId,
        @Size(max = 2000) String textContent,
        String fileName,
        String fileContentBase64,
        @Min(10) long ttlSeconds
) {
}

