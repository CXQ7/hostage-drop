package com.hostagedrop.gateway.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PayRequest(
        @NotBlank String txId,
        @NotBlank String payerId,
        @NotBlank String payeeId,
        @Size(max = 2000) String textContent,
        String ransomFileName,
        String ransomFileContentBase64
) {
}

