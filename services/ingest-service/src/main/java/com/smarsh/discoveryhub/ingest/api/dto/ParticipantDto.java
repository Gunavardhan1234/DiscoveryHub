package com.smarsh.discoveryhub.ingest.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ParticipantDto(
        String name,
        @NotBlank String address,
        @NotBlank String role) {}
