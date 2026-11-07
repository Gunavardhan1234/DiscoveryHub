package com.smarsh.discoveryhub.export.application;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip {@link AbstractExportPackager Template Method} subclass: entries are
 * zip entries, the container trailer is the central directory. The returned
 * entry stream is the {@link ZipOutputStream} itself, which the base class
 * decorates with a {@code DigestOutputStream} — write-through hashing, format
 * unaware.
 *
 * <p><b>SOLID — LSP:</b> honours the base contract exactly — every
 * {@code beginEntry} is paired with an {@code endEntry}, every package ends
 * with {@code finish} — so any code holding an {@code AbstractExportPackager}
 * works with this subclass and would work with a tar/gz sibling without
 * modification (the substitutability LSP demands).
 */
public class ZipExportPackager extends AbstractExportPackager {

    private ZipOutputStream zip;

    public ZipExportPackager(ManifestBuilder manifestBuilder) {
        super(manifestBuilder);
    }

    @Override
    protected void start(OutputStream sink) {
        zip = new ZipOutputStream(sink);
    }

    @Override
    protected OutputStream beginEntry(String path) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        return zip;
    }

    @Override
    protected void endEntry() throws IOException {
        zip.closeEntry();
    }

    @Override
    protected void finish() throws IOException {
        zip.finish();
        zip.close();
    }
}
