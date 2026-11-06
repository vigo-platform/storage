package ir.vigoapp.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;

@Component
class UserIdentity {
    private final URI profileUri;
    private final RestClient client;

    UserIdentity(@Value("${storage.user.base-url}") String baseUrl,
                 @Value("${storage.user.allow-http}") boolean allowHttp) {
        URI base = URI.create(baseUrl);
        boolean secure = "https".equals(base.getScheme());
        if ((!secure && !(allowHttp && "http".equals(base.getScheme()))) || base.getHost() == null
            || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) {
            throw new IllegalStateException("Storage requires a trusted User service origin (HTTPS by default)");
        }
        profileUri = base.resolve("/api/v1/users/me");
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().requestFactory(requests).build();
    }

    Policy.Actor actor(Jwt jwt) {
        // User validates the same bearer token and looks up its subject in the account database.
        try {
            var response = client.get().uri(profileUri).headers(headers -> headers.setBearerAuth(jwt.getTokenValue()))
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode() == HttpStatus.OK && body != null
                && BooleanNode.TRUE.equals(body.path("success"))) {
                JsonNode profile = body.path("data");
                if ("ACTIVE".equals(profile.path("status").stringValue(""))) {
                    String publicId = profile.path("user_id").stringValue("");
                    UUID id = UUID.fromString(publicId);
                    var role = Policy.Role.valueOf(profile.path("role").stringValue(""));
                    if (id.toString().equalsIgnoreCase(publicId) && jwt.getClaimAsStringList("roles").contains(role.name())) {
                        return new Policy.Actor(id, role);
                    }
                }
            }
        } catch (RestClientException | IllegalArgumentException e) {
            // An unavailable or malformed lookup cannot establish identity.
        }
        throw Api.fail(403,"FORBIDDEN");
    }
}
