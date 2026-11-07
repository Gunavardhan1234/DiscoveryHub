package com.smarsh.discoveryhub.events;

public final class Topics {
    public static final String COMM_INGEST_V1 = "comm.ingest.v1";
    /** Dead-letter topic for {@link #COMM_INGEST_V1}: records that exhausted
     *  retries (with exponential backoff) land here for inspection/replay. */
    public static final String COMM_INGEST_V1_DLT = "comm.ingest.v1.DLT";
    public static final String COMM_STORED_V1 = "comm.stored.v1";
    /** Dead-letter topic for {@link #COMM_STORED_V1}: search-service records
     *  that exhaust the indexer's retries (poison-pill parse/index failures)
     *  land here for inspection/replay. */
    public static final String COMM_STORED_V1_DLT = "comm.stored.v1.DLT";
    public static final String HOLD_COMMANDS_V1 = "hold.commands.v1";
    public static final String HOLD_APPLIED_V1 = "hold.applied.v1";
    public static final String CASE_CLOSED_V1 = "case.closed.v1";
    public static final String EXPORT_JOBS_V1 = "export.jobs.v1";
    /** Dead-letter topic for {@link #EXPORT_JOBS_V1}: jobs that exhausted the
     *  worker's retries (3 attempts with backoff) land here for inspection/replay. */
    public static final String EXPORT_JOBS_V1_DLT = "export.jobs.v1.DLT";
    public static final String AUDIT_EVENTS_V1 = "audit.events.v1";
    /** Emitted by archive-service on every successful message delete — both the
     *  manual hold-guarded {@code DELETE /api/messages/{id}} and the disposition
     *  executor's retention sweep — so search-service can retract the document
     *  from its Elasticsearch index. Without this, a deleted message stays
     *  visible ("ghost") in search results until the whole index is rebuilt
     *  from {@code comm.stored.v1}, which would resurrect it (FR-5/FR-1.7). */
    public static final String MESSAGE_DELETED_V1 = "message.deleted.v1";

    private Topics() {
    }
}
