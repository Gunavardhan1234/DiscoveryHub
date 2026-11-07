package com.smarsh.discoveryhub.events;

import java.util.List;

public record HoldApplied(String holdId, List<String> messageIds, boolean onHold) {
}
