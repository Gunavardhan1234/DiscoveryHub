package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

public record HoldCommand(
        String holdId,
        String caseId,
        String action,
        List<String> custodians,
        Instant from,
        Instant to,
        String terms) {
}
