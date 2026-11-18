package ir.vigoapp.storage;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.*;

/**
 * S3-compatible object storage (ParsPack) selected with {@code storage.backend=s3}.
 *
 * <p>Credentials come only from the environment; nothing is hardcoded and no key
 * is logged. Uploads are staged on local disk first so {@link FileValidator} can
 * inspect the actual bytes, then promoted by a single {@code PutObject}. That
 * costs one extra disk write in exchange for validating before any object becomes
 * visible in the bucket.
 *
 * <p>Public and private objects are separated by key prefix, and private objects
 * are only ever delivered through short-lived presigned GET URLs issued after an
 * authorization check. Nothing here makes an object public at the bucket level.
 */
@Component
@ConditionalOnProperty(name = "storage.backend", havingValue = "s3")
final class S3BlobStore implements BlobStore {
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final String PUBLIC_PREFIX = "public/";
    private static final String PRIVATE_PREFIX = "private/";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean pathStyle;
    private final Path stageDir;

    S3BlobStore(@Value("${storage.s3.endpoint}") String endpoint,
                @Value("${storage.s3.region:us-east-1}") String region,
                @Value("${storage.s3.bucket}") String bucket,
                @Value("${storage.s3.access-key}") String accessKey,
                @Value("${storage.s3.secret-key}") String secretKey,
                @Value("${storage.s3.path-style:true}") boolean pathStyle,
                @Value("${storage.root}") String stageRoot) throws IOException {
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank()) throw new IllegalStateException("S3 endpoint, region and bucket are required");
        if (accessKey.isBlank() || secretKey.isBlank()) throw new IllegalStateException("S3 credentials must be supplied through the environment");
        URI uri = URI.create(endpoint);
        boolean allowed = "https".equals(uri.getScheme()) || Boolean.getBoolean("storage.s3.allow-http");
        if (!allowed) throw new IllegalStateException("S3 endpoint must use HTTPS");
        if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalStateException("Invalid S3 endpoint");
        this.bucket = bucket;
        this.pathStyle = pathStyle;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();
        var regionRef = Region.of(region);
        s3 = S3Client.builder()
                .endpointOverride(uri)
                .region(regionRef)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(uri)
                .region(regionRef)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
        // Staging stays local: validator needs a real file, and failed uploads must not touch the bucket.
        stageDir = Path.of(stageRoot).toAbsolutePath().normalize().resolve("staged");
        Files.createDirectories(stageDir);
    }

    private static String keyOf(Staged staged) { return staged.key(); }

    private String objectKey(String key, Visibility visibility) {
        if (key == null || !key.matches("[a-f0-9]{32}")) throw Api.fail(400, "VALIDATION_FAILED");
        return (visibility == Visibility.PUBLIC ? PUBLIC_PREFIX : PRIVATE_PREFIX) + key;
    }

    public Staged put(InputStream input) throws IOException {
        String key = UUID.randomUUID().toString().replace("-", "");
        Path path = stageDir.resolve(key);
        long size = 0; MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) {
                size += count; if (size > MAX_BYTES) throw Api.fail(413, "FILE_TOO_LARGE");
                digest.update(buffer, 0, count); out.write(buffer, 0, count);
            }
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(path); throw e; }
        if (size == 0) { Files.deleteIfExists(path); throw Api.fail(400, "FILE_CONTENT_MISMATCH"); }
        return new Staged(key, path, size, HexFormat.of().formatHex(digest.digest()));
    }

    public void promote(Staged staged, Visibility visibility) throws IOException {
        if (!Files.isRegularFile(staged.path())) throw new IOException("Missing staged object");
        String key = objectKey(keyOf(staged), visibility);
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                            .contentLength(staged.size()).contentType("application/octet-stream")
                            .metadata(Map.of("sha256", staged.sha256())).build(),
                    RequestBody.fromFile(staged.path()));
        } catch (S3Exception e) {
            throw new IOException("Object storage rejected the upload", e);
        }
        Files.deleteIfExists(staged.path());
    }

    public void discardStage(Staged staged) throws IOException { Files.deleteIfExists(staged.path()); }

    public void discardUnreferenced(Staged staged) throws IOException {
        deleteQuietly(objectKey(keyOf(staged), Visibility.PRIVATE));
        Files.deleteIfExists(staged.path());
    }

    public InputStream open(String key, Visibility visibility) throws IOException {
        try { return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey(key, visibility)).build()); }
        catch (NoSuchKeyException e) { throw new FileNotFoundException(key); }
        catch (S3Exception e) { throw new IOException("Object storage read failed", e); }
    }

    public boolean exists(String key, Visibility visibility) throws IOException {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key, visibility)).build());
            return true;
        } catch (NoSuchKeyException e) { return false; }
        catch (S3Exception e) { throw new IOException("Object storage head failed", e); }
    }

    public long size(String key, Visibility visibility) throws IOException {
        try { return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(key, visibility)).build()).contentLength(); }
        catch (NoSuchKeyException e) { throw new FileNotFoundException(key); }
        catch (S3Exception e) { throw new IOException("Object storage head failed", e); }
    }

    public String presignedGet(String key, Visibility visibility, Duration ttl) throws IOException {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(60)) > 0)
            throw new IllegalArgumentException("Presign TTL must be between 1 second and 60 minutes");
        try {
            var request = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(b -> b.bucket(bucket).key(objectKey(key, visibility)))
                    .build();
            return presigner.presignGetObject(request).url().toString();
        } catch (S3Exception e) { throw new IOException("Object storage presign failed", e); }
    }

    public void delete(String key, Visibility visibility) throws IOException {
        try { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key, visibility)).build()); }
        catch (S3Exception e) { throw new IOException("Object storage delete failed", e); }
    }

    private void deleteQuietly(String objectKey) {
        try { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build()); }
        catch (RuntimeException ignored) { /* Compensation is best-effort; reconciliation covers the rest. */ }
    }

    public String backend() { return "s3"; }

    /** Diagnostics only: identifies the configured bucket without exposing credentials. */
    String describe() { return "s3:" + bucket + "@" + (pathStyle ? "path-style " : "virtual-host ") + Instant.now(); }
}
