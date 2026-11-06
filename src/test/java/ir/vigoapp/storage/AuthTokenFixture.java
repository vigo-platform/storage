package ir.vigoapp.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.context.DynamicPropertyRegistry;

final class AuthTokenFixture implements AutoCloseable {
    static final String ISSUER = "auth.vigoapp.ir";
    static final String AUDIENCE = "vigo-api";

    private final RSAKey key;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String,String> profiles = new ConcurrentHashMap<>();
    private final Map<UUID,Long> subjects = new ConcurrentHashMap<>();
    private final AtomicLong nextSubject = new AtomicLong();

    AuthTokenFixture() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-only").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> reply(exchange, new JWKSet(key.toPublicJWK()).toString()));
            server.createContext("/api/v1/users/me", exchange -> {
                var headers = exchange.getRequestHeaders();
                String authorization = headers.getFirst("Authorization");
                boolean selfLookup = "GET".equals(exchange.getRequestMethod()) && authorization != null
                    && !headers.containsKey("X-User-Id") && !headers.containsKey("X-User-Role");
                reply(exchange, selfLookup ? profiles.get(authorization) : null);
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start HTTP identity fixtures", e);
        }
    }

    void configure(DynamicPropertyRegistry properties) {
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.add("storage.auth.jwks-uri", () -> origin + "/jwks");
        properties.add("storage.auth.issuer", () -> ISSUER);
        properties.add("storage.auth.audience", () -> AUDIENCE);
        properties.add("storage.user.base-url", () -> origin);
        properties.add("storage.user.allow-http", () -> true);
    }

    String token(UUID publicId, String role) throws Exception {
        return token(publicId, role, role);
    }

    String token(UUID publicId, String currentRole, String... signedRoles) throws Exception {
        long subject = subjects.computeIfAbsent(publicId, id -> nextSubject.incrementAndGet());
        String token = sign(claims(Long.toString(subject), signedRoles).build(), "at+jwt");
        profile(token, json.writeValueAsString(Map.of("success", true,
            "data", Map.of("user_id", publicId, "role", currentRole, "status", "ACTIVE"))));
        return token;
    }

    String sign(JWTClaimsSet claims, String type) throws Exception {
        return sign(claims, key, JWSAlgorithm.RS256, type);
    }

    void profile(String token, String response) {
        profiles.put("Bearer " + token, response);
    }

    private static void reply(HttpExchange exchange, String response) throws IOException {
        try (exchange) {
            if (response == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override public void close() {
        server.stop(0);
    }

    static JWTClaimsSet.Builder claims(String subject, String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(List.of(AUDIENCE)).subject(subject)
            .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString()).claim("token_use", "access").claim("sid", UUID.randomUUID().toString())
            .claim("credential_version", 1).claim("roles", List.of(roles));
    }

    static String sign(JWTClaimsSet claims, RSAKey key, JWSAlgorithm algorithm, String type) throws Exception {
        var header = new JWSHeader.Builder(algorithm).keyID(key.getKeyID());
        if (type != null) header.type(new JOSEObjectType(type));
        var jwt = new SignedJWT(header.build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
