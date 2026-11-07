package com.smarsh.discoveryhub.caseapp.api.dto;

import java.util.List;

public record EvidenceBulkAddResponse(int added, int skipped, List<String> addedMessageIds) {
}
