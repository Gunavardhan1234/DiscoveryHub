package com.smarsh.discoveryhub.ingest.infrastructure.storage;

import com.smarsh.discoveryhub.common.BlobStore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/**
 * {@link BlobStore} adapter over LocalStack S3 for the ingest-service claim-check
 * path (Day-2 hardening). Mirrors the export-service adapter — both services own a
 * thin adapter rather than depending on a shared one, per the hexagonal rule that
 * each service owns its infrastructure adapters. No AWS SDK types leak past this
 * class: callers ({@link S3ClaimCheckAdapter}) use the {@link BlobStore} interface.
 */
@Component
public class IngestS3StorageAdapter implements BlobStore {

    private final S3Client s3;
    private final S3Presigner presigner;

    public IngestS3StorageAdapter(
            @Value("${spring.cloud.aws.s3.endpoint:http://localhost:4566}") String endpoint,
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    @Override
    public String put(String bucket, String key, byte[] content, String contentType) {
        s3.putObject(builder -> builder.bucket(bucket).key(key).contentType(contentType),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(content));
        return key;
    }

    @Override
    public byte[] get(String bucket, String key) {
        return s3.getObjectAsBytes(builder -> builder.bucket(bucket).key(key)).asByteArray();
    }

    @Override
    public String presignedUrl(String bucket, String key, long ttlSeconds) {
        return presigner.presignGetObject(builder -> builder
                .signatureDuration(Duration.ofSeconds(ttlSeconds))
                .getObjectRequest(req -> req.bucket(bucket).key(key)))
                .url().toString();
    }

    @Override
    public void delete(String bucket, String key) {
        s3.deleteObject(builder -> builder.bucket(bucket).key(key));
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
