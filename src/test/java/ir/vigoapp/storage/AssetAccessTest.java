package ir.vigoapp.storage;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Real PostgreSQL and a real filesystem store. Verifies that public/private
 * separation and access grants are enforced before any URL or byte is produced.
 */
class AssetAccessTest {
    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    @TempDir static Path storageRoot;
    AssetService service;
    BlobStore blobs;
    static final UUID OWNER = UUID.randomUUID();
    static final UUID STRANGER = UUID.randomUUID();
    static final UUID GRANTED = UUID.randomUUID();
    static final Policy.Actor ownerActor = new Policy.Actor(OWNER, Policy.Role.COACH);
    static final Policy.Actor strangerActor = new Policy.Actor(STRANGER, Policy.Role.ATHLETE);
    static final Policy.Actor grantedActor = new Policy.Actor(GRANTED, Policy.Role.ADMIN);

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception { if (postgres != null) postgres.close(); }

    @BeforeEach
    void wire() throws Exception {
        new JdbcTemplate(dataSource).update("delete from asset_access; delete from assets");
        blobs = new FileSystemBlobStore(storageRoot.resolve("root-" + UUID.randomUUID()).toString());
        service = new AssetService(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), blobs, new FileValidator(), Duration.ofMinutes(5));
    }

    private static MockMultipartFile file(String name, String body) {
        return new MockMultipartFile("file", name, "text/plain", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void publicAssetIsReadableAnonymouslyAndPrivateIsNot() throws Exception {
        var pub = service.upload(ownerActor, BlobStore.Visibility.PUBLIC, file("avatar.txt", "public image"));
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private certificate"));

        assertThat(service.publicContent(UUID.fromString((String) pub.get("asset_id"))).size()).isEqualTo(12);
        assertThatThrownBy(() -> service.publicContent(UUID.fromString((String) priv.get("asset_id"))))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(404));
    }

    @Test
    void privateAssetIsInvisibleToStrangersBeforeAndAfterGrant() throws Exception {
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private"));

        assertThatThrownBy(() -> service.metadata(strangerActor, UUID.fromString((String) priv.get("asset_id"))))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> service.presignedDownload(strangerActor, UUID.fromString((String) priv.get("asset_id"))))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));

        service.grant(ownerActor, UUID.fromString((String) priv.get("asset_id")), GRANTED, "READ");

        // An ADMIN already sees everything, so verify the grant with a non-admin identity.
        var grantedReader = new Policy.Actor(GRANTED, Policy.Role.ATHLETE);
        assertThat(service.metadata(grantedReader, UUID.fromString((String) priv.get("asset_id"))))
                .containsEntry("visibility", "PRIVATE");
        assertThat(service.content(grantedReader, UUID.fromString((String) priv.get("asset_id"))).size()).isEqualTo(7);

        service.revoke(ownerActor, UUID.fromString((String) priv.get("asset_id")), GRANTED);
        assertThatThrownBy(() -> service.metadata(grantedReader, UUID.fromString((String) priv.get("asset_id"))))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void onlyOwnerOrAdminCanGrantRevokeOrDelete() throws Exception {
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private"));
        UUID id = UUID.fromString((String) priv.get("asset_id"));

        assertThatThrownBy(() -> service.grant(strangerActor, id, STRANGER, "READ"))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> service.revoke(strangerActor, id, GRANTED))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> service.delete(strangerActor, id))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void deletedAssetDisappearsFromEveryPath() throws Exception {
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private"));
        UUID id = UUID.fromString((String) priv.get("asset_id"));
        service.delete(ownerActor, id);

        assertThatThrownBy(() -> service.metadata(ownerActor, id))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(404));
        assertThatThrownBy(() -> service.publicContent(id))
                .isInstanceOfSatisfying(Api.Fault.class, e -> assertThat(e.status).isEqualTo(404));
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from assets where asset_id=? and status='DELETED'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void filesystemBackendIssuesStreamingFallbackInsteadOfPresignedUrl() throws Exception {
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private"));
        var url = service.presignedDownload(ownerActor, UUID.fromString((String) priv.get("asset_id")));
        assertThat(url).containsEntry("mode", "STREAM");
        assertThat((String) url.get("url")).startsWith("/api/v1/assets/").endsWith("/content");
    }

    @Test
    void metadataNeverExposesObjectKeyOrChecksum() throws Exception {
        var priv = service.upload(ownerActor, BlobStore.Visibility.PRIVATE, file("certificate.txt", "private"));
        assertThat(service.metadata(ownerActor, UUID.fromString((String) priv.get("asset_id"))))
                .doesNotContainKeys("object_key", "checksum_sha256", "storage_backend");
    }
}
