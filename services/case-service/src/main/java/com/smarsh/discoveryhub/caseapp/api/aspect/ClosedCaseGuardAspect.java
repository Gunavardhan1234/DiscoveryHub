package com.smarsh.discoveryhub.caseapp.api.aspect;

import com.smarsh.discoveryhub.caseapp.api.annotation.ClosedCaseGuard;
import com.smarsh.discoveryhub.caseapp.domain.model.CaseStatus;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseEntity;
import com.smarsh.discoveryhub.caseapp.infrastructure.persistence.CaseRepository;
import com.smarsh.discoveryhub.common.ConflictException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AOP aspect that enforces FR-2.5 (closed case is read-only) on every method
 * annotated {@link ClosedCaseGuard}. This is the Chain-of-Responsibility guard
 * referenced in the architecture — a single cross-cutting check rather than
 * scattering {@code if (status == CLOSED)} across every service method.
 */
// Proxy Pattern (via Spring AOP): Spring wraps every @ClosedCaseGuard-annotated method's bean in a
// dynamic proxy at startup; calling e.g. evidenceService.addEvidence(...) actually calls the
// proxy first, which runs this aspect's `enforce` logic and only then delegates (`pjp.proceed()`)
// to the real method — the guarded classes (CustodianService, EvidenceService, SavedSearchService)
// never call this class directly or even import it by name at the call site.
// Chain of Responsibility / cross-cutting concern: one aspect enforces FR-2.5 for every annotated
// method rather than the same `if (status == CLOSED) throw` check being duplicated per method.
@Aspect
@Component
public class ClosedCaseGuardAspect {

    private final CaseRepository cases;

    public ClosedCaseGuardAspect(CaseRepository cases) {
        this.cases = cases;
    }

    // @Around advice: runs code both BEFORE and (conditionally) around the original method call —
    // `pjp.proceed()` is what actually invokes the guarded method; if this method returns/throws
    // before calling proceed(), the original method body never executes at all.
    @Around("@annotation(guard)")
    public Object enforce(ProceedingJoinPoint pjp, ClosedCaseGuard guard) throws Throwable {
        UUID caseId = extractCaseId(pjp.getArgs(), guard.value());
        CaseEntity entity = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Case '" + caseId + "' not found"));
        // Fail fast: the guard rejects a mutation against a closed case before pjp.proceed() ever
        // runs the real method body, so no partial work happens on a case that should be read-only.
        if (entity.status == CaseStatus.CLOSED) {
            throw new ConflictException("CASE_CLOSED",
                    "Case '" + caseId + "' is closed and read-only — no new evidence, custodians, or exports allowed");
        }
        return pjp.proceed();
    }

    private UUID extractCaseId(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) {
            throw new IllegalArgumentException("@ClosedCaseGuard index " + index + " out of bounds");
        }
        Object arg = args[index];
        // Pattern-matching instanceof (Java 16+): casts and binds `caseId` in one step instead of
        // a separate `(UUID) arg` cast, eliminating a whole class of ClassCastException typos.
        if (!(arg instanceof UUID caseId)) {
            throw new IllegalArgumentException("@ClosedCaseGuard expected UUID at index " + index
                    + " but got " + (arg == null ? "null" : arg.getClass().getName()));
        }
        return caseId;
    }
}
