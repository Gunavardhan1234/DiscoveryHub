package com.smarsh.discoveryhub.archive.domain.model;

import java.util.Set;

public class HeldItemException extends RuntimeException {
    private final Set<String> holds;
    public HeldItemException(Set<String> holds) {
        super("Message is protected by legal hold(s): " + holds);
        this.holds = Set.copyOf(holds);
    }
    public Set<String> holds() { return holds; }
}
