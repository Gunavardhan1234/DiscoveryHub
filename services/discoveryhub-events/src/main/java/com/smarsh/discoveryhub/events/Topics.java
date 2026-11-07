package com.smarsh.discoveryhub.events;

public final class Topics {
    public static final String COMM_INGEST_V1 = "comm.ingest.v1";
    /** Dead-letter topic for {@link #COMM_INGEST_V1}: records that exhausted
     *  retries (with exponential backoff) land here for inspection/replay. */
    public static final String COMM_INGEST_V1_DLT = "comm.ingest.v1.DLT";
    public static final String COMM_STORED_V1 = "comm.stored.v1";
    public static final String HOLD_COMMANDS_V1 = "hold.commands.v1";
    public static final String HOLD_APPLIED_V1 = "hold.applied.v1";
    public static final String EXPORT_JOBS_V1 = "export.jobs.v1";
    /** Dead-letter topic for {@link #EXPORT_JOBS_V1}: jobs that exhausted the
     *  worker's retries (3 attempts with backoff) land here for inspection/replay. */
    public static final String EXPORT_JOBS_V1_DLT = "export.jobs.v1.DLT";
    public static final String AUDIT_EVENTS_V1 = "audit.events.v1";
    public static final String CASE_CLOSED_V1 = "case.closed.v1";

    private Topics() {
    }
}
