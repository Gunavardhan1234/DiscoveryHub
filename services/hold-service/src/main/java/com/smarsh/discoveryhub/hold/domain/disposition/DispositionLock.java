package com.smarsh.discoveryhub.hold.domain.disposition;

import java.util.Optional;

/**
 * Single-runner guard for disposition (execution-plan Day 3 M4: "lock so only
 * one replica runs it"). The orchestrator lives in hold-service, so the lock is
 * a Postgres advisory lock on the service's own database rather than the Mongo
 * lock the plan sketched for an archive-resident job — same guarantee: two
 * replicas' schedulers can never run a pass concurrently.
 */
public interface DispositionLock {

    /**
     * Attempts to take the cluster-wide disposition lock without blocking.
     *
     * @return a handle to release, or empty if another runner holds it
     */
    Optional<LockHandle> tryAcquire();

    /** Held lock; {@link #close()} releases it. Never blocks. */
    interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }
}
