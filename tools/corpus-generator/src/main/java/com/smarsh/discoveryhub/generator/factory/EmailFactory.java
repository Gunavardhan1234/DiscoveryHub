package com.smarsh.discoveryhub.generator.factory;

import com.smarsh.discoveryhub.generator.Hashes;
import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.IngestRequest;
import com.smarsh.discoveryhub.generator.model.IngestRequest.Attachment;
import com.smarsh.discoveryhub.generator.model.IngestRequest.Participant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Produces EMAIL messages with subject + FROM/TO participants and occasional attachments. */
public class EmailFactory extends CommunicationFactory {

    private static final List<String> SUBJECTS = List.of(
            "Q2 wire instructions", "Project Phoenix update", "Re: quarterly review",
            "Incident post-mortem", "Friday demo notes", "Vendor contract draft",
            "Onboarding checklist", "Budget approval needed", "Thread: outage retro",
            "Welcome to the team", "Re: compliance hold", "Migration runbook",
            "Sprint plan", "Re: sprint plan", "Quarterly metrics", "Re: quarterly metrics",
            "Travel approval", "Re: travel approval", "Security patch", "Re: security patch");

    private static final List<String> BODIES = List.of(
            "Please process the attached instructions at your earliest convenience.",
            "Updating you on Phoenix — the migration is on track for Friday.",
            "The quarterly review deck is attached; comments welcome.",
            "Root cause was a stale cache; mitigation is in place and verified.",
            "Demo notes: ingest path green, search returning hits, hold overlap test passing.",
            "Please review the attached draft and reply with redlines.",
            "Onboarding steps are complete for the new hire starting Monday.",
            "Need budget approval for the infrastructure line item by EOW.",
            "Retro: detection lagged by 4m; adding a health probe to close the gap.",
            "Welcome aboard — your first ticket is assigned in the board.");

    private static final String[][] ATTACHMENT_TYPES = {
            {"instructions.pdf", "application/pdf"}, {"report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {"screenshot.png", "image/png"}, {"export.csv", "text/csv"}, {"bundle.zip", "application/zip"}};

    @Override
    public String type() { return "EMAIL"; }

    @Override
    public IngestRequest create(int index, Custodian sender, Custodian recipient, Instant sentAt,
                                String threadId, String inReplyTo, int attachmentPct) {
        Random rng = new Random(index * 1000003L);
        String subject = SUBJECTS.get(index % SUBJECTS.size());
        String body = BODIES.get(index % BODIES.size());
        String externalId = Hashes.externalId(sender.id(), threadId, sentAt.toString(), body);
        List<Attachment> attachments = attachmentsFor(rng, attachmentPct, externalId);
        List<Participant> participants = List.of(
                new Participant(sender.name(), sender.email(), "FROM"),
                new Participant(recipient.name(), recipient.email(), "TO"));
        return new IngestRequest(externalId, "EMAIL", subject, body, threadId, inReplyTo,
                sender.id(), participants, sentAt, attachments);
    }

    private List<Attachment> attachmentsFor(Random rng, int attachmentPct, String externalId) {
        if (attachmentPct <= 0 || rng.nextInt(100) >= attachmentPct) {
            return List.of();
        }
        int count = 1 + rng.nextInt(2);
        List<Attachment> out = new ArrayList<>(count);
        for (int a = 0; a < count; a++) {
            String[] type = ATTACHMENT_TYPES[rng.nextInt(ATTACHMENT_TYPES.length)];
            long size = 1024L + rng.nextInt(2_000_000);
            out.add(new Attachment(externalId + "-a" + a, type[0], type[1], size));
        }
        return out;
    }
}
