package com.smarsh.discoveryhub.export.config;

import com.smarsh.discoveryhub.common.BlobStore;
import com.smarsh.discoveryhub.export.application.AbstractExportPackager;
import com.smarsh.discoveryhub.export.application.ExportWorkerUseCase;
import com.smarsh.discoveryhub.export.application.ManifestBuilder;
import com.smarsh.discoveryhub.export.application.PackageVerifier;
import com.smarsh.discoveryhub.export.application.RequestExportUseCase;
import com.smarsh.discoveryhub.export.application.RetryExportUseCase;
import com.smarsh.discoveryhub.export.application.VerifyExportUseCase;
import com.smarsh.discoveryhub.export.application.ZipExportPackager;
import com.smarsh.discoveryhub.export.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.CaseStatusPort;
import com.smarsh.discoveryhub.export.domain.port.EvidenceSourcePort;
import com.smarsh.discoveryhub.export.domain.port.ExportEventPublisher;
import com.smarsh.discoveryhub.export.domain.port.ExportJobPort;
import com.smarsh.discoveryhub.export.domain.port.ExportProgressPublisher;
import com.smarsh.discoveryhub.export.domain.port.HoldScopeSourcePort;
import com.smarsh.discoveryhub.export.domain.port.MessageSourcePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

/**
 * The service's <b>composition root</b>: the single place where concrete
 * implementations are chosen and wired to the port interfaces the application
 * layer depends on.
 *
 * <p><b>OOP — Abstraction + SOLID — DIP/LSP:</b> every {@code @Bean} method
 * here is the "inject the detail into the abstraction" step of Dependency
 * Inversion — the rest of the codebase sees only ports. Swapping
 * {@link ZipExportPackager} for another {@link AbstractExportPackager}
 * subclass is a one-line change here (LSP guarantees the substitute works),
 * and nothing else in the service knows or cares.
 */
@Configuration
public class ExportConfig {

    @Bean
    RequestExportUseCase requestExportUseCase(ExportJobPort jobPort, ExportEventPublisher publisher,
                                              AuditEventPublisher auditPublisher, CaseStatusPort caseStatus) {
        return new RequestExportUseCase(jobPort, publisher, auditPublisher, caseStatus);
    }

    @Bean
    RetryExportUseCase retryExportUseCase(ExportJobPort jobPort, ExportEventPublisher publisher,
                                          ExportProgressPublisher progressPublisher,
                                          AuditEventPublisher auditPublisher) {
        return new RetryExportUseCase(jobPort, publisher, progressPublisher, auditPublisher);
    }

    @Bean
    ManifestBuilder manifestBuilder() {
        return new ManifestBuilder();
    }

    /** A fresh packager per job — the zip container is per-package state. */
    @Bean
    Supplier<AbstractExportPackager> packagerFactory(ManifestBuilder manifestBuilder) {
        return () -> new ZipExportPackager(manifestBuilder);
    }

    @Bean
    ExportWorkerUseCase exportWorkerUseCase(ExportJobPort jobPort,
                                            EvidenceSourcePort evidenceSource,
                                            HoldScopeSourcePort holdScopeSource,
                                            MessageSourcePort messageSource,
                                            BlobStore blobStore,
                                            Supplier<AbstractExportPackager> packagerFactory,
                                            ExportProgressPublisher progressPublisher,
                                            AuditEventPublisher auditPublisher,
                                            @Value("${discoveryhub.export.attachments-bucket:discoveryhub-attachments}") String attachmentsBucket,
                                            @Value("${discoveryhub.export.exports-bucket:discoveryhub-exports}") String exportsBucket) {
        return new ExportWorkerUseCase(jobPort, evidenceSource, holdScopeSource, messageSource, blobStore,
                packagerFactory, progressPublisher, auditPublisher, attachmentsBucket, exportsBucket);
    }

    @Bean
    PackageVerifier packageVerifier() {
        return new PackageVerifier();
    }

    @Bean
    VerifyExportUseCase verifyExportUseCase(ExportJobPort jobPort, BlobStore blobStore, PackageVerifier verifier,
                                            @Value("${discoveryhub.export.exports-bucket:discoveryhub-exports}") String exportsBucket) {
        return new VerifyExportUseCase(jobPort, blobStore, verifier, exportsBucket);
    }
}
