package com.smarsh.discoveryhub.ingest.application;

import com.smarsh.discoveryhub.common.Actor;
import com.smarsh.discoveryhub.common.CorrelationId;
import com.smarsh.discoveryhub.ingest.domain.model.Participant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backs {@code POST /admin/generate?count=N} (api-contracts &sect;ingest). Runs
 * the Factory-pattern generation on a background thread and feeds each produced
 * message through {@link IngestMessageUseCase} so the same dedupe + publish
 * path is exercised as the {@code tools/corpus-generator} CLI.
 *
 * <p>This is the compact, in-process variant for quick runs / tests; the
 * full-scale Faker-style corpus is produced by {@code tools/corpus-generator}
 * (the {@code generator} compose service).
 */
public class CorpusGenerationUseCase {

    private static final List<String[]> CUSTODIANS = List.of(
            new String[]{"c-0001", "Ada Byron", "ada@fict.example"},
            new String[]{"c-0002", "Charlie Babbage", "charlie@fict.example"},
            new String[]{"c-0003", "Grace Hopper", "grace@fict.example"},
            new String[]{"c-0004", "Alan Turing", "alan@fict.example"},
            new String[]{"c-0005", "Margaret Hamilton", "margaret@fict.example"},
            new String[]{"c-0006", "Linus Torvalds", "linus@fict.example"},
            new String[]{"c-0007", "Katherine Johnson", "katherine@fict.example"},
            new String[]{"c-0008", "Dennis Ritchie", "dennis@fict.example"},
            new String[]{"c-0009", "Barbara Liskov", "barbara@fict.example"},
            new String[]{"c-0010", "Tim Berners-Lee", "tim@fict.example"},
            new String[]{"c-0011", "Radia Perlman", "radia@fict.example"},
            new String[]{"c-0012", "Donald Knuth", "donald@fict.example"},
            new String[]{"c-0013", "Edsger Dijkstra", "edsger@fict.example"},
            new String[]{"c-0014", "Anita Borg", "anita@fict.example"},
            new String[]{"c-0015", "John Carmack", "john@fict.example"},
            new String[]{"c-0016", "Radia Joy", "radia.j@fict.example"},
            new String[]{"c-0017", "Niklaus Wirth", "niklaus@fict.example"},
            new String[]{"c-0018", "Claude Shannon", "claude@fict.example"},
            new String[]{"c-0019", "Hedy Lamarr", "hedy@fict.example"},
            new String[]{"c-0020", "Brian Kernighan", "brian@fict.example"});

    private static final List<String> SUBJECTS = List.of(
            "Q2 wire instructions", "Project Phoenix update", "Re: quarterly review",
            "Incident post-mortem", "Friday demo notes", "Vendor contract draft",
            "Onboarding checklist", "Budget approval needed", "Thread: outage retro",
            "Welcome to the team");

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

    /** Varied attachment types/sizes (FR-1.3); metadata-only, same shape as {@code tools/corpus-generator}'s EmailFactory. */
    private static final String[][] ATTACHMENT_TYPES = {
            {"instructions.pdf", "application/pdf"},
            {"report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            {"screenshot.png", "image/png"}, {"export.csv", "text/csv"}, {"bundle.zip", "application/zip"}};

    /** Default share of messages carrying an attachment; FR-1.3 requires at least 5%. */
    private static final int DEFAULT_ATTACHMENT_PCT = 8;

    private final IngestMessageUseCase ingestUseCase;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "corpus-generator");
        t.setDaemon(true);
        return t;
    });

    public CorpusGenerationUseCase(IngestMessageUseCase ingestUseCase) {
        this.ingestUseCase = ingestUseCase;
    }

    public GenerationStarted start(int count) {
        return start(count, DEFAULT_ATTACHMENT_PCT);
    }

    /** @param attachmentPct percent chance (0-100) a message carries 1-2 attachments; FR-1.3 wants at least 5. */
    public GenerationStarted start(int count, int attachmentPct) {
        String jobId = "gen-" + LocalDate.now(ZoneOffset.UTC) + "-" + UUID.randomUUID().toString().substring(0, 8);
        executor.submit(() -> runGeneration(jobId, count, attachmentPct));
        return new GenerationStarted(jobId, count, "RUNNING");
    }

    private void runGeneration(String jobId, int count, int attachmentPct) {
        CorrelationId.set(jobId);
        Actor.set("corpus-generator");
        try {
            for (int i = 0; i < count; i++) {
                String[] sender = CUSTODIANS.get(i % CUSTODIANS.size());
                String[] recipient = CUSTODIANS.get((i + 1) % CUSTODIANS.size());
                boolean isEmail = (i % 4) != 3; // 75% email, 25% chat
                String threadId = "th-" + (i % 50);
                Instant sentAt = Instant.now().minusSeconds(60L * (count - i));
                String body = BODIES.get(i % BODIES.size());
                String externalId = deterministicExternalId(sender[0], threadId, sentAt, body);
                var command = new IngestMessageCommand(
                        externalId,
                        isEmail ? "EMAIL" : "CHAT",
                        isEmail ? SUBJECTS.get(i % SUBJECTS.size()) : "(chat)",
                        body,
                        threadId,
                        i % 10 == 0 ? null : externalId,
                        sender[0],
                        List.of(
                                new Participant(sender[1], sender[2], "FROM"),
                                new Participant(recipient[1], recipient[2], "TO")),
                        sentAt,
                        attachmentsFor(i, attachmentPct, externalId));
                try {
                    ingestUseCase.execute(command);
                } catch (RuntimeException ignored) {
                    // a failed publish surfaces through the broker error path; keep generating
                }
            }
        } finally {
            CorrelationId.clear();
            Actor.clear();
        }
    }

    /**
     * Synthetic attachments with real random byte content (FR-1.3: at least
     * 5% of the corpus carries attachments of varied type/size): a
     * deterministic per-message roll decides whether it carries 1-2
     * attachments. The generator is the only source of demo data in this
     * system, so it must produce real bytes — not just metadata — for the
     * ingest claim-check ({@link com.smarsh.discoveryhub.ingest.domain.port.ClaimCheckPort})
     * to actually externalize the larger ones to S3, the same path a real
     * attachment upload would take.
     */
    private static List<IngestMessageCommand.AttachmentRef> attachmentsFor(int index, int attachmentPct, String externalId) {
        Random rng = new Random(index * 1000003L);
        if (attachmentPct <= 0 || rng.nextInt(100) >= attachmentPct) {
            return List.of();
        }
        int count = 1 + rng.nextInt(2);
        List<IngestMessageCommand.AttachmentRef> out = new ArrayList<>(count);
        for (int a = 0; a < count; a++) {
            String[] type = ATTACHMENT_TYPES[rng.nextInt(ATTACHMENT_TYPES.length)];
            int size = 1024 + rng.nextInt(2_000_000);
            byte[] bytes = new byte[size];
            rng.nextBytes(bytes);
            String content = Base64.getEncoder().encodeToString(bytes);
            out.add(new IngestMessageCommand.AttachmentRef(externalId + "-a" + a, type[0], type[1], size, content));
        }
        return out;
    }

    /** Deterministic externalMessageId = sha256(custodian|thread|sentAt|body), per the playbook. */
    static String deterministicExternalId(String custodian, String threadId, Instant sentAt, String body) {
        String input = custodian + "|" + threadId + "|" + sentAt + "|" + body;
        return "ext-" + sha256Hex(input).substring(0, 16);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append("%02x".formatted(b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record GenerationStarted(String jobId, int count, String status) {}
}
