package com.smarsh.discoveryhub.caseapp.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutating method that must be rejected if the referenced case is
 * {@code CLOSED} (FR-2.5: a closed case is read-only — no new evidence,
 * custodians, holds, or exports).
 *
 * <p>The aspect reads the {@code UUID caseId} from the parameter at
 * {@link #value()} (default index 0), loads the case status, and throws
 * {@code ConflictException("CASE_CLOSED", ...)} → HTTP 409 if the case is
 * closed. Put this on every service method that mutates case-owned data
 * <em>except</em> {@code CaseService.transition} — the state machine already
 * makes CLOSED terminal.
 */
// Custom annotation: a compile-time-checked, declarative marker (rather than a magic string or a
// naming convention) that ClosedCaseGuardAspect looks for at runtime via reflection.
// @Retention(RUNTIME): required so the annotation is still present on the method object at
// runtime for Spring AOP to detect via @Around("@annotation(guard)") — SOURCE/CLASS retention
// would make the aspect unable to see it at all.
// @Target(METHOD): restricts where this annotation is legal to apply (only methods), so misuse on
// a field or class is a compile error instead of a silently-ignored annotation.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClosedCaseGuard {
    /** Index of the {@code UUID caseId} parameter in the annotated method. */
    // default 0: a sensible default so the common case (caseId is the first parameter) needs just
    // `@ClosedCaseGuard` with no argument, while methods where it isn't first can override it.
    int value() default 0;
}
