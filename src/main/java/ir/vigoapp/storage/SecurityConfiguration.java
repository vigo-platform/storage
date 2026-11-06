package ir.vigoapp.storage;

import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
class SecurityConfiguration {
    private static final Set<String> STORAGE_ROLES = Arrays.stream(Policy.Role.values())
        .map(Policy.Role::name).collect(Collectors.toUnmodifiableSet());

    @Bean JwtDecoder jwtDecoder(@Value("${storage.auth.jwks-uri}") String jwks,
            @Value("${storage.auth.issuer}") String issuer, @Value("${storage.auth.audience}") String audience) {
        if (jwks.isBlank() || issuer.isBlank() || audience.isBlank()) throw new IllegalStateException("JWKS, issuer and audience are required");
        var uri=java.net.URI.create(jwks);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null) throw new IllegalStateException("Invalid JWKS URI");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256)
            .validateType(false).build();
        decoder.setJwtValidator(validators(issuer,audience));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> validators(String issuer,String audience) {
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),new JwtIssuerValidator(issuer),jwt -> {
            try {
                // Nimbus normalizes numeric subjects to strings. Check the original signed JSON type.
                Object rawSubject = SignedJWT.parse(jwt.getTokenValue()).getPayload().toJSONObject().get("sub");
                if ("at+jwt".equals(jwt.getHeaders().get("typ")) && "access".equals(jwt.getClaim("token_use"))
                    && rawSubject instanceof String subject && positiveUserId(subject)
                    && jwt.getClaim("roles") instanceof List<?> roles && !roles.isEmpty()
                    && roles.stream().allMatch(role -> role instanceof String && STORAGE_ROLES.contains(role))
                    && jwt.getExpiresAt()!=null && jwt.getAudience()!=null && jwt.getAudience().contains(audience)) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (IllegalArgumentException | ParseException e) {
                // Malformed claims are authentication failures.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid access token claims",null));
        });
    }
    private static boolean positiveUserId(String subject) {
        long id = Long.parseLong(subject);
        return id > 0 && Long.toString(id).equals(subject);
    }
    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain managementSecurity(HttpSecurity http,@Value("${management.server.port:9082}") int managementPort) throws Exception {
        http.securityMatcher(request->request.getLocalPort()==managementPort)
            .csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,
                "/actuator/health","/actuator/health/liveness","/actuator/health/readiness","/actuator/prometheus").permitAll().anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->writeError(res,401,"UNAUTHORIZED"))
                .accessDeniedHandler((req,res,x)->writeError(res,403,"FORBIDDEN")));
        return http.build();
    }
    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/prometheus").denyAll()
                .requestMatchers("/api/v1/files","/api/v1/files/**").authenticated().anyRequest().denyAll())
            .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())
                .authenticationEntryPoint((req,res,e)->writeError(res,401,"UNAUTHORIZED")))
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,x)->writeError(res,401,"UNAUTHORIZED"))
                .accessDeniedHandler((req,res,x)->writeError(res,403,"FORBIDDEN")));
        return http.build();
    }
    static void writeError(HttpServletResponse res,int status,String code) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        if(status==401)res.setHeader("WWW-Authenticate","Bearer");
        new ObjectMapper().writeValue(res.getOutputStream(),Api.envelope(false,code,null));
    }
}

final class Policy {
    enum Role { ATHLETE,COACH,ADMIN }
    enum Category { WORKOUT,DIET,SUPPLEMENT,OTHER }
    record Actor(UUID id,Role role) {}
    static void upload(Actor actor,UUID coach,UUID athlete) {
        if(actor.role()!=Role.COACH || !actor.id().equals(coach) || coach.equals(athlete)) throw Api.fail(403,"FORBIDDEN");
    }
    static void edit(Actor actor,UUID coach) {
        if(actor.role()!=Role.ADMIN && (actor.role()!=Role.COACH || !actor.id().equals(coach))) throw Api.fail(403,"FORBIDDEN");
    }
    static void filter(Actor actor,UUID coach,UUID athlete) {
        if(actor.role()==Role.COACH && coach!=null&&!coach.equals(actor.id()) || actor.role()==Role.ATHLETE&&athlete!=null&&!athlete.equals(actor.id())) throw Api.fail(403,"FORBIDDEN");
    }
}
