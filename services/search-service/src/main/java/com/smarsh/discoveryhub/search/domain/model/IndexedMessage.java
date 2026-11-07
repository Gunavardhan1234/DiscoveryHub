package com.smarsh.discoveryhub.search.domain.model;

import java.util.List;

/**
 * Projection of a communication into the Elasticsearch {@code messages} index.
 * <p>
 * ES is a <em>projection, never the source of truth</em>; this document can be
 * dropped and rebuilt by replaying the event log (see {@code POST /admin/reindex}).
 * The {@code timestamp} is carried as an ISO-8601 string so serialization is
 * independent of the Elasticsearch client's Jackson module configuration.
 *
 * <p><b>OOP — immutable value object + encapsulation:</b> a {@code record}, and
 * the compact constructor null-guards and defensively copies all three lists
 * with {@code List.copyOf}. A caller therefore cannot mutate a projection after
 * construction, which matters because the same instance is handed to a batch
 * indexer and may be retried.
 *
 * <p><b>SOLID — SRP + the read/write model split (CQRS):</b> this is a
 * <em>separate</em> model from the {@code CommunicationEvent} that arrives on
 * Kafka and from archive-service's Mongo document. That separation is the
 * point: the index carries search-shaped fields that exist nowhere else —
 * {@code participantsText} (participants flattened into one analyzable string
 * so a name match can be highlighted) and {@code hasAttachment} (a derived
 * boolean so filtering never needs to inspect the array). Reusing the source
 * document as the index document would drag storage concerns into the query
 * model and make either side painful to evolve.
 *
 * <p><b>SOLID — DIP:</b> it lives in {@code domain.model} and carries no
 * Elasticsearch annotation or type, so the domain defines the projection's
 * shape and the adapter maps it — not the reverse.
 *
 * @param messageId      ES _id and the immutable UUIDv5 message id
 * @param type           EMAIL / CHAT (keyword, filterable)
 * @param subject        analyzed full-text field
 * @param body           analyzed full-text field
 * @param timestamp      ISO-8601 sent timestamp (date field)
 * @param custodianId    keyword, filterable
 * @param threadId       keyword, group-by for threading
 * @param participantsText joined participant names/addresses for full-text over participants
 * @param participants   nested objects (name/address/role); populated from the rich
 *                       {@code comm.stored.v1} payload, empty on the slim ingest event
 * @param attachments    attachment metadata (object); populated from the stored payload
 * @param hasAttachment  derived boolean filter
 * @param onHold         searchable hold flag (kept in sync via {@code hold.applied.v1})
 * @param holds          set of hold ids covering the message (reference-counted)
 */
public record IndexedMessage(
        String messageId,
        String type,
        String subject,
        String body,
        String timestamp,
        String custodianId,
        String threadId,
        String participantsText,
        List<Participant> participants,
        List<Attachment> attachments,
        boolean hasAttachment,
        boolean onHold,
        List<String> holds) {

    public IndexedMessage {
        participants = participants == null ? List.of() : List.copyOf(participants);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        holds = holds == null ? List.of() : List.copyOf(holds);
    }

    public record Participant(String name, String address, String role) {}

    public record Attachment(String filename, String contentType, long size) {}
}
