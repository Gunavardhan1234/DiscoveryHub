package com.smarsh.discoveryhub.ingest.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record IngestBatchRequest(@NotEmpty List<@Valid IngestMessageRequest> messages) {}
