package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.export.domain.model.ExportEntry;
import com.smarsh.discoveryhub.export.domain.model.ExportManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * <b>Template Method</b> (execution-plan M5 Day 2): {@link #buildPackage} fixes
 * the packaging algorithm — deterministic entry order, per-item SHA-256 via a
 * {@link DigestOutputStream} <b>Decorator</b> wrapped around the container
 * stream (hashing happens while writing, no second pass), manifest last —
 * while subclasses supply the container format through the {@code start} /
 * {@code beginEntry} / {@code endEntry} / {@code finish} steps.
 *
 * <p><b>OOP — Inheritance + Polymorphism:</b> callers depend only on this
 * abstraction; the four abstract "primitive" steps are what each concrete
 * format overrides ({@link ZipExportPackager} today, tar/gz tomorrow) — the
 * base class never knows which container is actually running.
 *
 * <p><b>SOLID — OCP:</b> a new package format is a new subclass; the frozen
 * algorithm above is closed for modification but the family is open for
 * extension. Making {@link #buildPackage} {@code final} is the other half of
 * OCP — subclasses can vary the format but cannot break the digest/manifest
 * contract.
 */
public abstract class AbstractExportPackager {

    /** The manifest's path inside the package; never listed in its own items. */
    public static final String MANIFEST_PATH = "manifest.json";

    private final ManifestBuilder manifestBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    protected AbstractExportPackager(ManifestBuilder manifestBuilder) {
        this.manifestBuilder = manifestBuilder;
    }

    /** The finished package plus the manifest that describes it. */
    public record PackageResult(byte[] bytes, ExportManifest manifest) {}

    /**
     * The template method — final so the packaging contract (order, digests,
     * manifest) cannot be broken by a format subclass.
     *
     * @param onEntryPackaged optional Observer of packaging progress, called
     *                        with the running count of packaged entries.
     */
    public final PackageResult buildPackage(String packageId, String caseId,
                                            List<ExportEntry> entries, IntConsumer onEntryPackaged) {
        List<ExportEntry> ordered = entries.stream()
                .sorted(Comparator.comparing(ExportEntry::path))
                .toList();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            start(sink);
            List<ExportManifest.Item> items = new ArrayList<>(ordered.size());
            int packaged = 0;
            for (ExportEntry entry : ordered) {
                items.add(writeEntry(entry.path(), entry.content()));
                packaged++;
                if (onEntryPackaged != null) onEntryPackaged.accept(packaged);
            }
            ExportManifest manifest = manifestBuilder.buildFromItems(packageId, caseId, items);
            writeEntry(MANIFEST_PATH, objectMapper.writeValueAsBytes(manifest));
            finish();
            return new PackageResult(sink.toByteArray(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build export package " + packageId, e);
        }
    }

    /** Streams one entry through the SHA-256 {@link DigestOutputStream} decorator. */
    private ExportManifest.Item writeEntry(String path, byte[] content) throws IOException {
        OutputStream entryStream = beginEntry(path);
        DigestOutputStream digestStream = new DigestOutputStream(entryStream, sha256());
        digestStream.write(content);
        digestStream.flush();
        endEntry();
        return new ExportManifest.Item(path, content.length,
                ManifestBuilder.hex(digestStream.getMessageDigest().digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the container around the destination stream. */
    protected abstract void start(OutputStream sink) throws IOException;

    /** Positions the container at a new entry and returns the stream to write it to. */
    protected abstract OutputStream beginEntry(String path) throws IOException;

    /** Seals the current entry. */
    protected abstract void endEntry() throws IOException;

    /** Finalises the container (central directory, trailers, …). */
    protected abstract void finish() throws IOException;
}
