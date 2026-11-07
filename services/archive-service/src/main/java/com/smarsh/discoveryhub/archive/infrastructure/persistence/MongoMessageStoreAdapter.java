package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import com.smarsh.discoveryhub.events.CommunicationEvent;
import com.smarsh.discoveryhub.archive.domain.port.MessageStorePort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Idempotent Mongo upsert (architecture §2.1, FR-1.6): {@code $setOnInsert}
 * on {@code {_id: messageId}} so a redelivered event is a no-op. The unique
 * index on {@code messageKey} is the second idempotency guard (created on
 * startup via {@link MongoIndexInitializer}).
 */
@Component
public class MongoMessageStoreAdapter implements MessageStorePort {

    private final MongoTemplate mongo;

    public MongoMessageStoreAdapter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean upsert(CommunicationEvent event) {
        Query query = Query.query(Criteria.where("_id").is(event.messageId()));
        Update update = new Update()
                .setOnInsert("messageKey", event.messageKey())
                .setOnInsert("type", event.type())
                .setOnInsert("subject", event.subject())
                .setOnInsert("body", event.body())
                .setOnInsert("threadId", event.threadId())
                .setOnInsert("inReplyTo", event.inReplyTo())
                .setOnInsert("custodianId", event.custodianId())
                .setOnInsert("participants", toParticipantDocs(event))
                .setOnInsert("sentAt", event.sentAt())
                .setOnInsert("attachments", toAttachmentDocs(event))
                .setOnInsert("hasAttachment", event.hasAttachment())
                .setOnInsert("holds", new LinkedHashSet<String>())
                .setOnInsert("onHold", false)
                .setOnInsert("ingestedAt", Instant.now());
        var result = mongo.upsert(query, update, MessageDocument.class);
        return result.wasAcknowledged() && result.getUpsertedId() != null;
    }

    private List<MessageDocument.Participant> toParticipantDocs(CommunicationEvent event) {
        List<MessageDocument.Participant> out = new ArrayList<>(event.participants().size());
        for (var p : event.participants()) {
            out.add(new MessageDocument.Participant(p.name(), p.address(), p.role()));
        }
        return out;
    }

    /**
     * Streams every stored message back as a {@link CommunicationEvent} using a
     * server-side cursor ({@code MongoTemplate.stream}), so the full corpus is
     * never materialised in memory. This is the source-of-truth read behind
     * {@code POST /admin/reindex}: it regenerates {@code comm.stored.v1} from
     * Mongo, proving ES (and Kafka) is a rebuildable projection (architecture
     * §3.4, Day-3 M1). The returned stream is closeable; the caller must close
     * it (try-with-resources) to release the cursor.
     */
    @Override
    public Stream<CommunicationEvent> streamAll() {
        return mongo.stream(new Query(), MessageDocument.class)
                .map(MongoMessageStoreAdapter::toEvent);
    }

    /** Reconstructs the stored-event payload from the durable Mongo document. */
    private static CommunicationEvent toEvent(MessageDocument doc) {
        List<CommunicationEvent.Participant> participants = new ArrayList<>();
        if (doc.getParticipants() != null) {
            for (var p : doc.getParticipants()) {
                participants.add(new CommunicationEvent.Participant(p.name(), p.address(), p.role()));
            }
        }
        List<CommunicationEvent.Attachment> attachments = new ArrayList<>();
        if (doc.getAttachments() != null) {
            for (var a : doc.getAttachments()) {
                attachments.add(new CommunicationEvent.Attachment(a.id(), a.filename(), a.contentType(),
                        a.size(), a.sha256(), a.s3Key()));
            }
        }
        // The entity's timestamp field is mapped to the Mongo "sentAt" key
        // (see @Field on MessageDocument); fall back to ingestedAt only if absent.
        Instant sentAt = doc.getTimestamp() != null ? doc.getTimestamp() : doc.getIngestedAt();
        return new CommunicationEvent(
                doc.getId(),
                doc.getMessageKey(),
                null,
                doc.getType(),
                doc.getSubject(),
                doc.getBody(),
                doc.getThreadId(),
                doc.getInReplyTo(),
                doc.getCustodianId(),
                participants,
                sentAt,
                attachments,
                doc.isHasAttachment());
    }

    private List<MessageDocument.Attachment> toAttachmentDocs(CommunicationEvent event) {
        List<MessageDocument.Attachment> out = new ArrayList<>(event.attachments().size());
        for (var a : event.attachments()) {
            out.add(new MessageDocument.Attachment(a.id(), a.filename(), a.contentType(), a.size(), a.sha256(), a.s3Key()));
        }
        return out;
    }
}
