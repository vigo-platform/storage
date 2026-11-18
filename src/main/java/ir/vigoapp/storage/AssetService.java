package ir.vigoapp.storage;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Generic asset storage for public and private documents.
 *
 * <p>Authorization is decided here, before any object key or presigned URL is
 * produced. Visibility is never taken from the client: the upload route decides
 * it, and a private asset can never be served by the public route.
 *
 * <p>Presigning is delegated to the backend. The filesystem backend cannot issue
 * capability URLs, so it returns a null URL and the caller streams the bytes
 * through an authenticated endpoint instead.
 */
@Service
class AssetService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BlobStore blobs;
    private final FileValidator validator;
    private final Duration presignTtl;

    AssetService(JdbcTemplate db, TransactionTemplate tx, BlobStore blobs, FileValidator validator,
                 @Value("${storage.presign-ttl:PT10M}") Duration presignTtl) {
        if (presignTtl.isZero() || presignTtl.isNegative() || presignTtl.compareTo(Duration.ofMinutes(60)) > 0)
            throw new IllegalArgumentException("Presign TTL must be between 1 second and 60 minutes");
        this.db = db; this.tx = tx; this.blobs = blobs; this.validator = validator; this.presignTtl = presignTtl;
    }

    Map<String, Object> upload(Policy.Actor actor, BlobStore.Visibility visibility, MultipartFile input) throws IOException {
        BlobStore.Staged staged;
        try (InputStream stream = input.getInputStream()) { staged = blobs.put(stream); }
        boolean promoted = false;
        try {
            var valid = validator.validate(staged, input.getOriginalFilename());
            blobs.promote(staged, visibility);
            promoted = true;
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            tx.executeWithoutResult(s -> db.update("""
                    insert into assets(asset_id,owner_user_id,object_key,storage_backend,original_filename,content_type,
                        size_bytes,checksum_sha256,visibility,status,created_at,updated_at)
                    values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)
                    """, id, actor.id(), staged.key(), blobs.backend(), valid.name(), valid.mime(),
                    staged.size(), staged.sha256(), visibility.name(), Timestamp.from(now), Timestamp.from(now)));
            return metadata(id);
        } catch (IOException | RuntimeException e) {
            compensate(staged, promoted);
            throw e;
        }
    }

    private void compensate(BlobStore.Staged staged, boolean promoted) {
        try {
            if (promoted) {
                Boolean referenced = tx.execute(s -> db.queryForObject(
                        "select exists(select 1 from assets where object_key=?)", Boolean.class, staged.key()));
                if (Boolean.FALSE.equals(referenced)) blobs.discardUnreferenced(staged);
            } else blobs.discardStage(staged);
        } catch (Exception cleanup) {
            org.slf4j.LoggerFactory.getLogger(AssetService.class)
                    .warn("Asset compensation deferred; reconciliation required ({})", cleanup.getClass().getSimpleName());
        }
    }

    Map<String, Object> metadata(Policy.Actor actor, UUID id) {
        requireVisible(actor, id, false);
        return metadata(id);
    }

    private Map<String, Object> metadata(UUID id) {
        var rows = db.queryForList("""
                select asset_id,owner_user_id,original_filename,content_type,size_bytes,visibility,status,created_at,updated_at
                from assets where asset_id=?
                """, id);
        if (rows.isEmpty()) throw Api.fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("asset_id", row.get("asset_id").toString());
        data.put("owner_user_id", row.get("owner_user_id").toString());
        data.put("original_filename", row.get("original_filename"));
        data.put("content_type", row.get("content_type"));
        data.put("size_bytes", row.get("size_bytes"));
        data.put("visibility", row.get("visibility"));
        data.put("status", row.get("status"));
        data.put("created_at", ((Timestamp) row.get("created_at")).toInstant().toString());
        data.put("updated_at", ((Timestamp) row.get("updated_at")).toInstant().toString());
        // Public assets advertise a stable, authenticated-free path; the binary is
        // still fetched from this service, never straight from the bucket.
        if ("PUBLIC".equals(row.get("visibility")) && "ACTIVE".equals(row.get("status")))
            data.put("public_url", "/api/v1/assets/" + row.get("asset_id") + "/content");
        return data;
    }

    /**
     * Issues a temporary download URL for a private asset after authorization.
     * A private object never receives a permanent URL.
     */
    Map<String, Object> presignedDownload(Policy.Actor actor, UUID id) throws IOException {
        var row = requireVisible(actor, id, false);
        String key = (String) row.get("object_key");
        var visibility = BlobStore.Visibility.valueOf((String) row.get("visibility"));
        String url = blobs.presignedGet(key, visibility, presignTtl);
        Map<String, Object> data = new LinkedHashMap<>();
        if (url == null) {
            // Streaming backend: expose an authenticated path that performs the same check.
            data.put("url", "/api/v1/assets/" + id + "/content");
            data.put("mode", "STREAM");
            data.put("expires_at", Instant.now().plus(presignTtl).toString());
        } else {
            data.put("url", url);
            data.put("mode", "PRESIGNED");
            data.put("expires_at", Instant.now().plus(presignTtl).toString());
        }
        return data;
    }

    /** Binary content for authorized callers; used by the filesystem backend and by public assets. */
    AssetContent content(Policy.Actor actor, UUID id) throws IOException {
        var row = requireVisible(actor, id, false);
        String key = (String) row.get("object_key");
        var visibility = BlobStore.Visibility.valueOf((String) row.get("visibility"));
        if (!blobs.exists(key, visibility)) throw new IllegalStateException("Committed object missing");
        return new AssetContent(blobs.open(key, visibility), (String) row.get("content_type"),
                ((Number) row.get("size_bytes")).longValue(), (String) row.get("original_filename"));
    }

    /** Anonymous content for PUBLIC assets only; private assets never reach this path. */
    AssetContent publicContent(UUID id) throws IOException {
        var rows = db.queryForList("select * from assets where asset_id=? and status='ACTIVE' and visibility='PUBLIC'", id);
        if (rows.isEmpty()) throw Api.fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        String key = (String) row.get("object_key");
        if (!blobs.exists(key, BlobStore.Visibility.PUBLIC)) throw new IllegalStateException("Committed object missing");
        return new AssetContent(blobs.open(key, BlobStore.Visibility.PUBLIC), (String) row.get("content_type"),
                ((Number) row.get("size_bytes")).longValue(), (String) row.get("original_filename"));
    }

    List<Map<String, Object>> access(Policy.Actor actor, UUID id) {
        requireOwnerOrAdmin(actor, id);
        return db.query("select user_id,permission,created_at from asset_access where asset_id=? order by created_at",
                (rs, n) -> Map.<String, Object>of("user_id", rs.getObject(1).toString(),
                        "permission", rs.getString(2), "created_at", rs.getTimestamp(3).toInstant().toString()), id);
    }

    Map<String, Object> grant(Policy.Actor actor, UUID id, UUID userId, String permission) {
        requireOwnerOrAdmin(actor, id);
        if (permission == null || !Set.of("READ", "READ_WRITE").contains(permission)) throw Api.fail(400, "VALIDATION_FAILED");
        if (userId.equals(actor.id())) throw Api.fail(400, "VALIDATION_FAILED");
        try {
            db.update("insert into asset_access(asset_id,user_id,permission,granted_by,created_at) values(?,?,?,?,?)",
                    id, userId, permission, actor.id(), Timestamp.from(Instant.now()));
        } catch (DataIntegrityViolationException duplicate) {
            db.update("update asset_access set permission=?,granted_by=? where asset_id=? and user_id=?",
                    permission, actor.id(), id, userId);
        }
        return Map.of("asset_id", id.toString(), "granted", userId.toString(), "permission", permission);
    }

    void revoke(Policy.Actor actor, UUID id, UUID userId) {
        requireOwnerOrAdmin(actor, id);
        int removed = db.update("delete from asset_access where asset_id=? and user_id=?", id, userId);
        if (removed == 0) throw Api.fail(404, "NOT_FOUND");
    }

    void delete(Policy.Actor actor, UUID id) throws IOException {
        var row = requireOwnerOrAdmin(actor, id);
        tx.executeWithoutResult(s -> db.update("update assets set status='DELETED',updated_at=? where asset_id=?",
                Timestamp.from(Instant.now()), id));
        // Metadata is soft-deleted; the object is removed so a revoked document does
        // not linger in the bucket. Program files keep their bytes for audit; assets
        // are user-supplied documents where erasure is the safer default.
        blobs.delete((String) row.get("object_key"), BlobStore.Visibility.valueOf((String) row.get("visibility")));
    }

    private Map<String, Object> requireVisible(Policy.Actor actor, UUID id, boolean write) {
        var rows = db.queryForList("select * from assets where asset_id=? and status='ACTIVE'", id);
        if (rows.isEmpty()) throw Api.fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        boolean owner = actor.id().equals(row.get("owner_user_id"));
        boolean admin = actor.role() == Policy.Role.ADMIN;
        boolean publicAsset = "PUBLIC".equals(row.get("visibility"));
        if (owner || admin || (!write && publicAsset)) return row;
        Integer grants = db.queryForObject(
                "select count(*) from asset_access where asset_id=? and user_id=?", Integer.class, id, actor.id());
        if (grants != null && grants > 0 && !write) return row;
        throw Api.fail(403, "FORBIDDEN");
    }

    private Map<String, Object> requireOwnerOrAdmin(Policy.Actor actor, UUID id) {
        var rows = db.queryForList("select * from assets where asset_id=? and status='ACTIVE'", id);
        if (rows.isEmpty()) throw Api.fail(404, "NOT_FOUND");
        var row = rows.getFirst();
        if (actor.id().equals(row.get("owner_user_id")) || actor.role() == Policy.Role.ADMIN) return row;
        throw Api.fail(403, "FORBIDDEN");
    }

    record AssetContent(InputStream stream, String contentType, long size, String filename) {}
}
