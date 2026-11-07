package com.smarsh.discoveryhub.generator.factory;

import com.smarsh.discoveryhub.generator.Hashes;
import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.IngestRequest;
import com.smarsh.discoveryhub.generator.model.IngestRequest.Participant;

import java.time.Instant;
import java.util.List;

/** Produces CHAT messages — short body, no subject, no attachments. */
public class ChatFactory extends CommunicationFactory {

    private static final List<String> BODIES = List.of(
            "you around?", "ship it", "looks good to me", "can you review the PR?",
            "the deploy is green", "rolling back", "standup in 5", "btw the dashboard is broken",
            "on it", "fyi the hold overlap test is red again", "merging now", "blocked on review",
            "will do after lunch", "thx!", "cc'ing the team");

    @Override
    public String type() { return "CHAT"; }

    @Override
    public IngestRequest create(int index, Custodian sender, Custodian recipient, Instant sentAt,
                                String threadId, String inReplyTo, int attachmentPct) {
        String body = BODIES.get(index % BODIES.size());
        String externalId = Hashes.externalId(sender.id(), threadId, sentAt.toString(), body);
        List<Participant> participants = List.of(
                new Participant(sender.name(), sender.email(), "FROM"),
                new Participant(recipient.name(), recipient.email(), "TO"));
        return new IngestRequest(externalId, "CHAT", null, body, threadId, inReplyTo,
                sender.id(), participants, sentAt, List.of());
    }
}
