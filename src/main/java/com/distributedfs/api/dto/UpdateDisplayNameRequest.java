package com.distributedfs.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDisplayNameRequest(
    @NotBlank String displayName
) {
}
