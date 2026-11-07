package com.smarsh.discoveryhub.generator;

import com.smarsh.discoveryhub.generator.factory.ChatFactory;
import com.smarsh.discoveryhub.generator.factory.CommunicationFactory;
import com.smarsh.discoveryhub.generator.factory.EmailFactory;
import com.smarsh.discoveryhub.generator.model.Custodian;
import com.smarsh.discoveryhub.generator.model.CustodianRoster;
import com.smarsh.discoveryhub.generator.model.IngestRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Corpus generator (FR-1.1–FR-1.3): produces realistic threaded EMAIL/CHAT
 * records and submits them to ingest-service in batches via {@link IngestClient}.
 * The ingest pipeline (Kafka → archive-service) durably stores each message in
 * MongoDB {@code discoveryhub_archive} — the generator itself never touches Mongo.
 *
 * <p>Deterministic by design: a fixed custodian roster + SHA-256-derived
 * {@code externalMessageId}s mean a re-run with the same args reproduces the
 * same messages (the ingest service deduplicates by {@code externalMessageId}).
 */
public final class CorpusGenerator {

    private static final int BATCH_SIZE = 100;

    private CorpusGenerator() {}

    public static void main(String[] args) {
        int messages = option(args, "--messages", 10000);
        int custodians = option(args, "--custodians", 20);
        int attachmentPct = option(args, "--attachment-pct", 5);
        String ingestUrl = option(args, "--ingest-url",
                System.getenv().getOrDefault("INGEST_URL", "http://ingest-service:8081"));

        System.out.printf("[generator] starting: messages=%d custodians=%d attachmentPct=%d → %s%n",
                messages, custodians, attachmentPct, ingestUrl);

        List<Custodian> roster = CustodianRoster.CUSTODIANS.subList(0, Math.min(custodians, CustodianRoster.CUSTODIANS.size()));
        CommunicationFactory email = new EmailFactory();
        CommunicationFactory chat = new ChatFactory();
        IngestClient client = new IngestClient(ingestUrl);

        // Messages span the last 30 days so time-window filters have data to bite on.
        Instant now = Instant.now();
        Instant start = now.minus(30, ChronoUnit.DAYS);
        long windowSecs = start.until(now, ChronoUnit.SECONDS);
        Random rng = new Random(42);

        List<IngestRequest> batch = new ArrayList<>(BATCH_SIZE);
        int threadCounter = 0;

        for (int i = 0; i < messages; i++) {
            // 70% email, 30% chat — a realistic corpus mix.
            CommunicationFactory factory = rng.nextInt(10) < 7 ? email : chat;

            Custodian sender = roster.get(i % roster.size());
            Custodian recipient = roster.get((i + 1 + rng.nextInt(roster.size() - 1)) % roster.size());

            // Start a new thread every ~5 messages; the rest reply into the current one.
            String threadId;
            String inReplyTo;
            if (i % 5 == 0) {
                threadCounter++;
                threadId = "thread-" + threadCounter;
                inReplyTo = null;
            } else {
                threadId = "thread-" + threadCounter;
                inReplyTo = "ext-" + Hashes.sha256Hex(threadId + "|" + (i - 1)).substring(0, 16);
            }

            Instant sentAt = start.plusSeconds(rng.nextLong(windowSecs));
            IngestRequest req = factory.create(i, sender, recipient, sentAt, threadId, inReplyTo, attachmentPct);
            batch.add(req);

            if (batch.size() >= BATCH_SIZE || i == messages - 1) {
                client.postBatch(batch);
                System.out.printf("[generator] progress: %d / %d posted%n", i + 1, messages);
                batch = new ArrayList<>(BATCH_SIZE);
            }
        }

        System.out.printf("[generator] done: accepted=%d failed=%d%n", client.accepted(), client.failed());
        if (client.failed() > 0) {
            System.exit(1);
        }
    }

    private static int option(String[] args, String name, int fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) return Integer.parseInt(args[i + 1]);
        }
        return fallback;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (name.equals(args[i])) return args[i + 1];
        }
        return fallback;
    }
}
