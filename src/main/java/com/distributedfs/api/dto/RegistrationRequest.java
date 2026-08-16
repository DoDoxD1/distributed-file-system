package com.distributedfs.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RegistrationRequest(
    @NotBlank String email,
    @NotBlank String password,
    String displayName
) {
}
