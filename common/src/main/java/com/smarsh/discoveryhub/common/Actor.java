package com.smarsh.discoveryhub.common;

/**
 * Thread-local actor holder. Carries the identity of the user or system process
 * responsible for the current operation, propagated onto every published event
 * for audit attribution. Seeded from the {@code X-Actor} request header.
 */
public final class Actor {

    public static final String HEADER = "X-Actor";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Actor() {
    }

    public static String current() {
        String a = CURRENT.get();
        return a != null ? a : "system";
    }

    public static void set(String actor) {
        if (actor == null || actor.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(actor);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
