package com.smarsh.discoveryhub.caseapp.domain.model;

import com.smarsh.discoveryhub.common.ConflictException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

// State Pattern: case behavior (which transitions are legal) is driven entirely by the case's
// current CaseStatus, looked up from a table rather than an if/else chain — FR-2.2's lifecycle.
// final + private constructor: a non-instantiable static utility class — CaseStateMachine has no
// instance state, so instances add nothing but confusion; `final` also blocks subclassing it.
public final class CaseStateMachine {
    // static final + Map.copyOf: the transition table is computed once at class-load and frozen
    // immutable, so it is trivially thread-safe to read from concurrent request-handling threads
    // with no synchronization — nothing can ever mutate it after construction.
    private static final Map<CaseStatus, EnumSet<CaseStatus>> LEGAL_TRANSITIONS = transitions();

    private CaseStateMachine() {
    }

    // EnumMap/EnumSet: both are backed by the enum's ordinal array rather than hashing, so
    // lookups here are O(1) array indexing — the right collection choice for a small, fixed key
    // domain like CaseStatus, and more memory-efficient than a general HashMap<K,EnumSet<V>>.
    private static Map<CaseStatus, EnumSet<CaseStatus>> transitions() {
        EnumMap<CaseStatus, EnumSet<CaseStatus>> map = new EnumMap<>(CaseStatus.class);
        map.put(CaseStatus.DRAFT, EnumSet.of(CaseStatus.ACTIVE));
        map.put(CaseStatus.ACTIVE, EnumSet.of(CaseStatus.UNDER_REVIEW));
        map.put(CaseStatus.UNDER_REVIEW, EnumSet.of(CaseStatus.CLOSED));
        map.put(CaseStatus.CLOSED, EnumSet.noneOf(CaseStatus.class));
        return Map.copyOf(map);
    }

    public static Map<CaseStatus, EnumSet<CaseStatus>> transitionsView() {
        return LEGAL_TRANSITIONS;
    }

    // Fail fast: validates the transition before any entity field is mutated by the caller, so an
    // illegal request is rejected atomically with no partial state change to roll back.
    public static void assertTransition(CaseStatus from, CaseStatus to) {
        if (!LEGAL_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(CaseStatus.class)).contains(to)) {
            throw new InvalidCaseTransitionException(from, to, LEGAL_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(CaseStatus.class)));
        }
    }

    /**
     * Maps to HTTP 409 {@code INVALID_TRANSITION} (docs/api-contracts.md
     * §case-service) via the {@code ConflictException} → {@code ApiError}
     * handler already wired in {@code CaseExceptionHandler}.
     */
    // Custom unchecked exception + inheritance: extends the shared ConflictException (which itself
    // extends RuntimeException) rather than throwing a generic RuntimeException, so this specific
    // business rule violation carries a stable machine-readable code ("INVALID_TRANSITION") and a
    // fixed HTTP 409 mapping that CaseExceptionHandler applies without any per-exception-type logic.
    // Unchecked, not checked: an illegal transition is a caller programming error the caller can't
    // meaningfully recover from mid-call, so callers aren't forced to declare/catch it everywhere.
    public static final class InvalidCaseTransitionException extends ConflictException {
        // Encapsulation: fields are private with only read accessors below, so once constructed
        // this exception's from/to/allowed facts cannot be altered by whoever catches it.
        private final CaseStatus from;
        private final CaseStatus to;
        private final EnumSet<CaseStatus> allowed;

        public InvalidCaseTransitionException(CaseStatus from, CaseStatus to, EnumSet<CaseStatus> allowed) {
            super("INVALID_TRANSITION", "Invalid case transition from " + from + " to " + to
                    + " (allowed: " + allowed + ")");
            this.from = from;
            this.to = to;
            // Defensive copy: EnumSet.copyOf snapshots the caller's set so a later mutation of the
            // original LEGAL_TRANSITIONS entry (impossible here, but true in general) can't leak
            // into this already-thrown exception's state.
            this.allowed = EnumSet.copyOf(allowed);
        }

        public CaseStatus from() { return from; }
        public CaseStatus to() { return to; }
        public EnumSet<CaseStatus> allowed() { return EnumSet.copyOf(allowed); }
    }
}
