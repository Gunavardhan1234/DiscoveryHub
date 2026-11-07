package com.smarsh.discoveryhub.export.infrastructure.storage;

import com.smarsh.discoveryhub.common.BlobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;

/**
 * {@link BlobStore} adapter over LocalStack S3 (execution-plan M5). The
 * {@link S3Client} is auto-configured by spring-cloud-aws-starter-s3 from the
 * {@code spring.cloud.aws.s3.*} properties in {@code application.yml} (endpoint
 * → LocalStack, path-style access). The {@link S3Presigner} is constructed here
 * from the same endpoint so pre-signed URLs hit LocalStack too. No AWS SDK
 * types leak past this class — callers use the {@link BlobStore} interface.
 *
 * <p>The presigner uses a <em>public</em> endpoint
 * ({@code S3_PUBLIC_ENDPOINT}, defaults to {@code S3_ENDPOINT}) so that
 * browser-issued download URLs resolve from the host, not just inside the
 * Docker network. Both the client and presigner use path-style access so
 * LocalStack gets {@code http://host:4566/bucket/key} (not the virtual-hosted
 * {@code http://bucket.host:4566/key} which fails DNS outside Docker).
 */
@Component
public class S3StorageAdapter implements BlobStore {

    private final S3Client s3;
    private final S3Presigner presigner;

    public S3StorageAdapter(
            S3Client s3,
            @Value("${spring.cloud.aws.s3.endpoint:http://localhost:4566}") String endpoint,
            @Value("${discoveryhub.export.s3-public-endpoint:${S3_PUBLIC_ENDPOINT:http://localhost:4566}}") String publicEndpoint,
            @Value("${spring.cloud.aws.region.static:us-east-1}") String region,
            @Value("${spring.cloud.aws.credentials.access-key:test}") String accessKey,
            @Value("${spring.cloud.aws.credentials.secret-key:test}") String secretKey) {
        this.s3 = s3;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
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

    @Override
    public boolean exists(String bucket, String key) {
        try {
            s3.headObject(builder -> builder.bucket(bucket).key(key));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
    }
}
