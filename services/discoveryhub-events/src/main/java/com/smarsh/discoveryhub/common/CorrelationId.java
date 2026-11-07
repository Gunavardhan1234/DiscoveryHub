package com.smarsh.discoveryhub.common;

/**
 * Thread-local correlation-id holder. Seeded from the {@code X-Correlation-Id}
 * request header (or a generated UUID) by an inbound filter, propagated onto
 * every published event so a single request can be traced across services.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {
    }

    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }

    public static void set(String id) {
        if (id == null || id.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(id);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
