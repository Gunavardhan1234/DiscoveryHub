package com.smarsh.discoveryhub.archive.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Document("messages")
public class MessageDocument {
    @Id private String id;
    private String messageKey;
    private String type;
    private String subject;
    private String body;
    @Field("sentAt") private Instant timestamp;
    private String threadId;
    private String inReplyTo;
    private String custodianId;
    private List<Participant> participants = new ArrayList<>();
    private List<Attachment> attachments = new ArrayList<>();
    private boolean hasAttachment;
    private Set<String> holds = new HashSet<>();
    private boolean onHold;
    private Instant ingestedAt;
    private Instant retentionDueAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Set<String> getHolds() { return holds; }
    public void setHolds(Set<String> holds) { this.holds = new HashSet<>(holds == null ? Set.of() : holds); this.onHold = !this.holds.isEmpty(); }
    public boolean isOnHold() { return onHold; }
    public void addHold(String holdId) { holds.add(holdId); onHold = !holds.isEmpty(); }
    public void removeHold(String holdId) { holds.remove(holdId); onHold = !holds.isEmpty(); }
    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getInReplyTo() { return inReplyTo; }
    public void setInReplyTo(String inReplyTo) { this.inReplyTo = inReplyTo; }
    public String getCustodianId() { return custodianId; }
    public void setCustodianId(String custodianId) { this.custodianId = custodianId; }
    public List<Participant> getParticipants() { return participants; }
    public void setParticipants(List<Participant> participants) {
        this.participants = participants == null ? new ArrayList<>() : new ArrayList<>(participants);
    }
    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments == null ? new ArrayList<>() : new ArrayList<>(attachments);
    }
    public boolean isHasAttachment() { return hasAttachment; }
    public void setHasAttachment(boolean hasAttachment) { this.hasAttachment = hasAttachment; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
    public Instant getRetentionDueAt() { return retentionDueAt; }
    public void setRetentionDueAt(Instant retentionDueAt) { this.retentionDueAt = retentionDueAt; }

    public record Participant(String name, String address, String role) {}
    public record Attachment(String id, String filename, String contentType, long size, String sha256, String s3Key) {}
}
