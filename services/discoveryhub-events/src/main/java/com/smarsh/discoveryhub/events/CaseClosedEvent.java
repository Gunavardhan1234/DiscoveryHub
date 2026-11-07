package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.UUID;

public record CaseClosedEvent(UUID caseId, String name, String owner, Instant closedAt) {}
