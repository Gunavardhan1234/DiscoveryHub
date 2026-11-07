package com.smarsh.discoveryhub.common;

import java.util.UUID;

/**
 * Typed wrapper around the correlation id propagated end-to-end through
 * REST calls, Kafka event payloads, logs, and audit entries.
 *
 * <p>Use {@link #current()} inside a request/consumer thread to read the id
 * set by the inbound filter/listener; {@link #set(String)} to seed it on entry
 * and {@link #clear()} on exit. Never reads a raw {@code String} from
 * business code — go through this type so the contract is explicit.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private final String value;

    private CorrelationId(String value) {
        this.value = value;
    }

    public static CorrelationId of(String value) {
        return new CorrelationId(value == null || value.isBlank() ? UUID.randomUUID().toString() : value);
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    /** Returns the id seeded for the current thread, or a fresh one if none was set. */
    public static String current() {
        String existing = HOLDER.get();
        return existing != null ? existing : generate().value();
    }

    /** Seeds the current thread's correlation id. Pair every call with {@link #clear()}. */
    public static void set(String value) {
        HOLDER.set(value);
    }

    /** Clears the current thread's correlation id. */
    public static void clear() {
        HOLDER.remove();
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
