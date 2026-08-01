package com.distributedfs.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CredentialsRequest(
    @NotBlank String email,
    @NotBlank String password
) {
}
