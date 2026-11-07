package com.smarsh.discoveryhub.ingest.infrastructure.persistence;

import com.smarsh.discoveryhub.ingest.domain.port.IngestDedupePort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code INSERT ... ON CONFLICT DO NOTHING} idempotency
 * logic with a mocked {@link JdbcTemplate}; the real SQL behaviour is covered
 * by {@code IngestDedupeIT} against Postgres.
 */
class IngestDedupeAdapterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IngestDedupeAdapter adapter = new IngestDedupeAdapter(jdbc);

    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID EXISTING_ID = UUID.randomUUID();

    @Test
    void freshKeyInsertsAndReturnsCreated() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq("sha256:key"), eq(MESSAGE_ID), eq("ext-1")))
                .thenReturn(List.of(MESSAGE_ID));

        IngestDedupePort.DedupeResult result = adapter.dedupe("sha256:key", MESSAGE_ID, "ext-1");

        assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.created()).isTrue();
    }

    @Test
    void conflictingKeyReturnsExistingMessageIdAsDuplicate() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(), any(), any()))
                .thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq("sha256:key")))
                .thenReturn(EXISTING_ID);

        IngestDedupePort.DedupeResult result = adapter.dedupe("sha256:key", MESSAGE_ID, "ext-1");

        assertThat(result.messageId()).isEqualTo(EXISTING_ID);
        assertThat(result.created()).isFalse();
        verify(jdbc).queryForObject(anyString(), eq(UUID.class), eq("sha256:key"));
    }
}
