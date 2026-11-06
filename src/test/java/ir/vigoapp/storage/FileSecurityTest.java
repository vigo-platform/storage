package ir.vigoapp.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(FileSecurityTest.Web.class)
class FileSecurityTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, UserIdentity.class, FileController.class, FileService.class, ApiErrors.class})
    static class Web {}

    static AuthTokenFixture auth;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        auth = new AuthTokenFixture();
        auth.configure(properties);
    }
    @AfterAll static void stopFixture() { if (auth != null) auth.close(); }

    @Autowired WebApplicationContext context;
    @MockitoBean FileRepository files;
    @MockitoBean JdbcTemplate db;
    @MockitoBean TransactionTemplate tx;
    @MockitoBean FileSystemBlobStore blobs;
    @MockitoBean FileValidator validator;
    @MockitoBean StorageOutbox outbox;
    private final UUID coach = UUID.randomUUID();
    private final UUID athlete = UUID.randomUUID();
    private final StoredFile file = new StoredFile(coach, athlete, "WORKOUT", "Program", null);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void addressedAthleteCanReadFileAndAnotherAthleteIsForbidden() throws Exception {
        when(tx.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(files.visible(file.id, false, null, athlete)).thenReturn(Optional.of(file));
        when(files.existsByIdAndDeletedAtIsNull(file.id)).thenReturn(true);
        when(db.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Map<String,Object>>>any(), eq(file.id)))
            .thenReturn(List.of(Map.of("version", 1, "content_type", "text/plain", "size_bytes", 12)));

        read(auth.token(athlete, "ATHLETE", "ADMIN", "ATHLETE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.athlete_id").value(athlete.toString()));
        UUID other = UUID.randomUUID();
        read(auth.token(other, "ATHLETE")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(files).visible(file.id, false, null, other);
    }

    @Test void invalidTokensReturnUnauthorizedBeforeFileLookup() throws Exception {
        var claims = AuthTokenFixture.claims("42", "ATHLETE");
        read(auth.sign(claims.build(), "JWT")).andExpect(status().isUnauthorized());
        read(auth.sign(claims.claim("sub", 42L).build(), "at+jwt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(files);
    }

    @Test void unresolvedIdentityOrRoleMismatchReturnsForbiddenBeforeFileLookup() throws Exception {
        String unresolved = auth.sign(AuthTokenFixture.claims("99999", "ADMIN").build(), "at+jwt");
        read(unresolved).andExpect(status().isForbidden());
        auth.profile(unresolved, """
            {"success":true,"data":{"user_id":99999,"role":"ADMIN","status":"ACTIVE"}}
            """);
        read(unresolved).andExpect(status().isForbidden());
        read(auth.token(athlete, "ATHLETE", "ADMIN")).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }

    @Test void uploadChecksSubscriptionForMappedCoachAndRequestedAthlete() throws Exception {
        var upload = new MockMultipartFile("file", "program.txt", "text/plain", new byte[]{65});
        mvc.perform(multipart("/api/v1/files").file(upload).param("athlete_id", athlete.toString())
                .param("category", "WORKOUT").param("title", "Program")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.token(coach, "COACH")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_ACTIVE_RELATION"));
        verify(db).queryForObject(anyString(), eq(Boolean.class), eq(coach), eq(athlete));
        verifyNoInteractions(blobs);
    }

    private ResultActions read(String token) throws Exception {
        return mvc.perform(get("/api/v1/files/{id}", file.id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("X-User-Id", athlete.toString()).header("X-User-Role", "ADMIN"));
    }
}
