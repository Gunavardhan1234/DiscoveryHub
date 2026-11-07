package com.smarsh.discoveryhub.export.application;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.common.ConflictException;
import com.smarsh.discoveryhub.common.NotFoundException;
import com.smarsh.discoveryhub.export.domain.model.ExportJob;
import com.smarsh.discoveryhub.export.domain.model.ExportJobStatus;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;

import java.util.UUID;

/**
 * {@code POST /exports/{id}/verify} (execution-plan M5 Day 3): re-downloads
 * the stored package and proves integrity twice over — the zip's SHA-256 must
 * equal the one recorded at completion, and every item must re-hash to its
 * manifest entry ({@link PackageVerifier}). Flip one byte in S3 → FAIL.
 *
 * <p><b>SOLID — SRP:</b> verification only — this use case never mutates the
 * job. The zip-walking algorithm is delegated to {@link PackageVerifier}
 * (composition), keeping "decide valid/invalid" separate from "how to walk a
 * zip and re-hash its entries".
 */
public class VerifyExportUseCase {

    public record Result(String jobId, boolean valid, String message) {}

    private final ExportJobPort jobPort;
    private final BlobStore blobStore;
    private final PackageVerifier verifier;
    private final String exportsBucket;

    public VerifyExportUseCase(ExportJobPort jobPort, BlobStore blobStore,
                               PackageVerifier verifier, String exportsBucket) {
        this.jobPort = jobPort;
        this.blobStore = blobStore;
        this.verifier = verifier;
        this.exportsBucket = exportsBucket;
    }

    public Result verify(UUID jobId) {
        ExportJob job = jobPort.findById(jobId)
                .orElseThrow(() -> new NotFoundException("ExportJob", jobId.toString()));
        if (job.status() != ExportJobStatus.COMPLETED || job.packageS3Key() == null) {
            throw new ConflictException("EXPORT_NOT_READY",
                    "Job " + jobId + " is " + job.status() + " — only COMPLETED packages can be verified");
        }
        byte[] packageBytes;
        try {
            packageBytes = blobStore.get(exportsBucket, job.packageS3Key());
        } catch (Exception e) {
            return new Result(jobId.toString(), false,
                    "Package could not be fetched from s3://" + exportsBucket + "/" + job.packageS3Key());
        }
        if (packageBytes == null) {
            return new Result(jobId.toString(), false, "Package object is missing from the exports bucket");
        }
        if (job.packageSha256() != null
                && !ManifestBuilder.sha256Hex(packageBytes).equals(job.packageSha256())) {
            return new Result(jobId.toString(), false,
                    "Package SHA-256 differs from the value recorded at completion — package was altered");
        }
        PackageVerifier.VerificationResult result = verifier.verify(packageBytes);
        return new Result(jobId.toString(), result.valid(), result.message());
    }
}
