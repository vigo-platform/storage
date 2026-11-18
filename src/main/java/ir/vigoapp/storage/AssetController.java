package ir.vigoapp.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Public and private documents. Uploads are split into two routes so the client
 * cannot choose visibility; private assets never receive a permanent URL.
 */
@RestController
@RequestMapping("/api/v1/assets")
class AssetController {
    private final AssetService service;
    private final UserIdentity identities;

    AssetController(AssetService service, UserIdentity identities) { this.service = service; this.identities = identities; }

    @PostMapping(value = "/public", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> uploadPublic(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) throws java.io.IOException {
        var data = service.upload(identities.actor(jwt), BlobStore.Visibility.PUBLIC, file);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + data.get("asset_id")))
                .body(Api.envelope(true, "CREATED", data));
    }

    @PostMapping(value = "/private", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> uploadPrivate(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) throws java.io.IOException {
        var data = service.upload(identities.actor(jwt), BlobStore.Visibility.PRIVATE, file);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + data.get("asset_id")))
                .body(Api.envelope(true, "CREATED", data));
    }

    @GetMapping("/{asset_id}")
    Object metadata(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id) {
        return Api.envelope(true, "OK", service.metadata(identities.actor(jwt), id));
    }

    /** Temporary URL for a private asset, issued only after authorization. */
    @GetMapping("/{asset_id}/download-url")
    Object downloadUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id) throws java.io.IOException {
        return Api.envelope(true, "OK", service.presignedDownload(identities.actor(jwt), id));
    }

    /** Streamed fallback used by the filesystem backend and for private assets in local runs. */
    @GetMapping("/{asset_id}/content")
    ResponseEntity<StreamingResponseBody> content(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id) throws java.io.IOException {
        var content = jwt == null ? service.publicContent(id) : service.content(identities.actor(jwt), id);
        return stream(content);
    }

    @GetMapping("/{asset_id}/access")
    Object access(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id) {
        return Api.envelope(true, "OK", service.access(identities.actor(jwt), id));
    }

    @PostMapping(value = "/{asset_id}/access", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object grant(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id, @RequestBody Map<String, Object> body) {
        Object user = body.get("user_id");
        Object permission = body.getOrDefault("permission", "READ");
        if (!(user instanceof String userId) || !(permission instanceof String p))
            throw Api.fail(400, "VALIDATION_FAILED");
        return Api.envelope(true, "OK", service.grant(identities.actor(jwt), id, UUID.fromString(userId), p));
    }

    @DeleteMapping("/{asset_id}/access/{user_id}")
    Object revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id, @PathVariable("user_id") UUID userId) {
        service.revoke(identities.actor(jwt), id, userId);
        return Api.envelope(true, "DELETED", null);
    }

    @DeleteMapping("/{asset_id}")
    Object delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("asset_id") UUID id) throws java.io.IOException {
        service.delete(identities.actor(jwt), id);
        return Api.envelope(true, "DELETED", null);
    }

    private static ResponseEntity<StreamingResponseBody> stream(AssetService.AssetContent content) {
        StreamingResponseBody body = output -> { try (var in = content.stream()) { in.transferTo(output); } };
        boolean attachment = !content.contentType().startsWith("image/");
        var disposition = attachment
                ? ContentDisposition.attachment().filename(content.filename(), StandardCharsets.UTF_8).build().toString()
                : ContentDisposition.inline().filename(content.filename(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType())).contentLength(content.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(attachment ? CacheControl.noStore() : CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
                .body(body);
    }
}
