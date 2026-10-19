package za.co.mrhdigital.audex.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import za.co.mrhdigital.audex.auth.Tokens;
import za.co.mrhdigital.audex.scoring.TemplateCatalog;
import za.co.mrhdigital.audex.storage.PhotoStorage;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountSyncIntegrationTest.StorageConfig.class)
@EnabledIfEnvironmentVariable(named = "AUDEX_TEST_DATABASE_URL", matches = ".+")
class AccountSyncIntegrationTest {
    private static final String SCHEMA = "sync_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("AUDEX_TEST_DATABASE_URL");
    private static final String USER = System.getenv().getOrDefault("AUDEX_TEST_DATABASE_USER", "audex");
    private static final String PASSWORD = System.getenv().getOrDefault("AUDEX_TEST_DATABASE_PASSWORD", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        props.add("spring.datasource.username", () -> USER);
        props.add("spring.datasource.password", () -> PASSWORD);
        props.add("spring.flyway.schemas", () -> SCHEMA);
        props.add("spring.flyway.default-schema", () -> SCHEMA);
        props.add("audex.storage.bucket", () -> "");
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @TestConfiguration
    static class StorageConfig {
        @Bean PhotoStorage testPhotos() {
            return new PhotoStorage() {
                private final Map<String, StoredPhoto> photos = new ConcurrentHashMap<>();
                public void put(String key, byte[] bytes, String type) { photos.put(key, new StoredPhoto(bytes, type)); }
                public Optional<StoredPhoto> get(String key) { return Optional.ofNullable(photos.get(key)); }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TemplateCatalog catalog;
    private String token;
    private String otherToken;

    @BeforeEach
    void accounts() {
        // All tables are in this test's randomly named schema, never the application's public schema.
        jdbc.execute("TRUNCATE site, app_user CASCADE");
        token = account("first");
        otherToken = account("second");
    }

    private String account(String name) {
        UUID id = UUID.randomUUID();
        String token = Tokens.create();
        jdbc.update("INSERT INTO app_user (id, username, email, display_name, password_hash) VALUES (?, ?, ?, ?, ?)",
                id, name, name + "@example.test", name, "unused-in-this-test");
        jdbc.update("""
                INSERT INTO auth_session (id, user_id, token_hash, expires_at, absolute_expires_at)
                VALUES (?, ?, ?, now() + interval '1 day', now() + interval '2 days')
                """, UUID.randomUUID(), id, Tokens.hash(token));
        return token;
    }

    private ObjectNode site(UUID id, String name, long revision) {
        return mapper.createObjectNode().put("id", id.toString()).put("name", name).put("revision", revision)
                .put("archived", false).put("unitManager", "Manager").put("supervisor", "Supervisor");
    }

    private JsonNode save(String path, ObjectNode data, String bearer, int expected) throws Exception {
        var result = mvc.perform(put(path).header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(data)))
                .andExpect(status().is(expected)).andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        JsonNode response = body.length == 0 ? mapper.getNodeFactory().missingNode() : mapper.readTree(body);
        assertNotNull(response);
        if (expected == 200) {
            assertTrue(response.isObject(), "Successful saves must return a JSON object");
        }
        return response;
    }

    @Test
    void sitesAreRevisionedRetryableAndAccountScoped() throws Exception {
        UUID id = UUID.randomUUID();
        String path = "/api/sites/" + id;
        ObjectNode initial = site(id, "Original", 0);
        assertEquals(1, save(path, initial, token, 200).path("revision").asLong());
        assertEquals(1, save(path, initial, token, 200).path("revision").asLong());
        ObjectNode changed = site(id, "New name", 1);
        assertEquals(2, save(path, changed, token, 200).path("revision").asLong());
        assertEquals(2, save(path, changed, token, 200).path("revision").asLong());
        save(path, site(id, "Stale edit", 1), token, 409);
        save(path, initial, otherToken, 404);
        mvc.perform(get("/api/sites").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        changed.put("revision", 2).put("archived", true);
        save(path, changed, token, 200);
        mvc.perform(get("/api/sites").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].archived").value(true));
        mvc.perform(get("/api/sites")).andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousEditsCannotSilentlyOverwriteEachOther() throws Exception {
        UUID id = UUID.randomUUID();
        String path = "/api/sites/" + id;
        save(path, site(id, "Original", 0), token, 200);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = Stream.of("Device A", "Device B").map(name -> executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not start");
                return mvc.perform(put(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(site(id, name, 1))))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertEquals(List.of(200, 409), Stream.of(results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(2, jdbc.queryForObject("SELECT revision FROM site WHERE id = ?", Long.class, id));
    }

    @Test
    void listsAreBoundedAndStableAndValidateInput() throws Exception {
        save("/api/sites/" + new UUID(0, 1), site(new UUID(0, 1), "A", 0), token, 200);
        save("/api/sites/" + new UUID(0, 2), site(new UUID(0, 2), "B", 0), token, 200);
        mvc.perform(get("/api/sites?limit=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].name").value("A"));
        mvc.perform(get("/api/sites?limit=1&offset=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].name").value("B"));
        for (String query : new String[]{"limit=0", "limit=201", "offset=-1"}) {
            mvc.perform(get("/api/sites?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        UUID id = UUID.randomUUID();
        save("/api/sites/" + id, site(id, " ", 0), token, 400);
        save("/api/sites/" + id, site(UUID.randomUUID(), "Mismatch", 0), token, 400);
        save("/api/sites/" + id, site(id, "Negative", -1), token, 400);
    }

    @Test
    void trainingSyncValidatesSiteOwnershipAndPreservesTombstones() throws Exception {
        UUID site = UUID.randomUUID(), id = UUID.randomUUID();
        save("/api/sites/" + site, site(site, "Training site", 0), token, 200);
        ObjectNode training = mapper.createObjectNode().put("id", id.toString()).put("siteId", site.toString())
                .put("date", "2026-10-19").put("topic", "Hand hygiene").put("attendees", 6).put("revision", 0);
        String path = "/api/training-sessions/" + id;
        save(path, training, otherToken, 404);
        assertEquals(1, save(path, training, token, 200).path("revision").asLong());
        assertEquals(1, save(path, training, token, 200).path("revision").asLong());
        training.put("revision", 1).put("notes", "Completed");
        save(path, training, token, 200);
        save(path, training.deepCopy().put("topic", "Stale"), token, 409);
        training.put("revision", 2).put("archived", true);
        save(path, training, token, 200);
        mvc.perform(get("/api/training-sessions?siteId=" + site).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].archived").value(true));
        mvc.perform(get("/api/training-sessions").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        save(path, training.put("attendees", -1), token, 400);
        save(path, training.put("attendees", 0).put("date", "not-a-date"), token, 400);
    }

    private ObjectNode audit(UUID id, UUID site) {
        ObjectNode payload = mapper.createObjectNode().put("id", id.toString()).put("siteId", site.toString())
                .put("siteName", "Original site").put("date", "2026-10-19").put("ruleSetCode", "WPC_V1")
                .put("unitManager", "Manager").put("supervisor", "Supervisor")
                .put("auditorRole", "Housekeeping Trainer")
                .put("finalisedAt", "2026-10-19T06:00:00Z");
        ObjectNode answers = payload.putObject("answers");
        catalog.template().path("sections").forEach(section -> section.path("items")
                .forEach(item -> answers.put(item.path("key").asText(), "COMPLIANT")));
        return payload;
    }

    @Test
    void aSecondDeviceRecoversImmutableAuditAndPhotosWithoutOverwritingSite() throws Exception {
        UUID site = UUID.randomUUID(), id = UUID.randomUUID(), photo = UUID.randomUUID();
        save("/api/sites/" + site, site(site, "Current site", 0), token, 200);
        ObjectNode payload = audit(id, site);
        save("/api/audits/" + id, payload, token, 200);
        save("/api/audits/" + id, payload, token, 200);
        save("/api/audits/" + id, payload.deepCopy().put("siteName", "Different"), token, 409);
        save("/api/audits/" + id, payload, otherToken, 404);
        mvc.perform(get("/api/sites").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[0].name").value("Current site"))
                .andExpect(jsonPath("$.items[0].revision").value(1));
        mvc.perform(get("/api/audits").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].siteName").value("Original site"))
                .andExpect(jsonPath("$.items[0].percent").value(100.0));
        String key = payload.path("answers").fieldNames().next();
        String photoPath = "/api/audits/" + id + "/photos/" + photo + "?itemKey=" + key + "&caption=Evidence&takenAt=2026-10-19T05:00:00Z";
        byte[] bytes = {1, 2, 3, 4};
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(photoPath).header("Authorization", "Bearer " + token)
                    .contentType("image/jpeg").content(bytes)).andExpect(status().isNoContent());
        }
        mvc.perform(put(photoPath).header("Authorization", "Bearer " + token)
                .contentType("image/jpeg").content(new byte[]{5})).andExpect(status().isConflict());
        mvc.perform(put(photoPath).header("Authorization", "Bearer " + otherToken)
                .contentType("image/jpeg").content(bytes)).andExpect(status().isNotFound());
        // This is a fresh authenticated request: no local device state is needed to reconstruct the audit.
        mvc.perform(get("/api/audits/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score.percent").value(100.0))
                .andExpect(jsonPath("$.auditorRole").value("Housekeeping Trainer"))
                .andExpect(jsonPath("$.answers." + key).value("COMPLIANT"))
                .andExpect(jsonPath("$.photos[0].id").value(photo.toString()))
                .andExpect(jsonPath("$.photos[0].takenAt").value("2026-10-19T05:00:00Z"));
        mvc.perform(get("/api/photos/" + photo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(result -> assertEquals(4, result.getResponse().getContentAsByteArray().length));
        mvc.perform(get("/api/audits/" + id).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/photos/" + photo).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/audits").header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalidAuditsDoNotCreatePartialRecordsAndNaReasonsRoundTrip() throws Exception {
        UUID site = UUID.randomUUID(), id = UUID.randomUUID();
        ObjectNode payload = audit(id, site);
        String firstKey = payload.path("answers").fieldNames().next();
        ((ObjectNode) payload.path("answers")).put(firstKey, "NA");
        save("/api/audits/" + id, payload, token, 422);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM site", Integer.class));
        payload.putObject("naReasons").put(firstKey, "  Not used at this facility  ");
        save("/api/audits/" + id, payload, token, 200);
        save("/api/audits/" + id, payload, token, 200);
        mvc.perform(get("/api/audits/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.naReasons." + firstKey).value("Not used at this facility"));
        ObjectNode invalid = audit(UUID.randomUUID(), site).put("finalisedAt", "bad");
        save("/api/audits/" + invalid.path("id").asText(), invalid, token, 400);
        invalid = audit(UUID.randomUUID(), site).put("date", "bad");
        save("/api/audits/" + invalid.path("id").asText(), invalid, token, 400);
        invalid = audit(UUID.randomUUID(), site);
        ((ObjectNode) invalid.path("answers")).remove(firstKey);
        save("/api/audits/" + invalid.path("id").asText(), invalid, token, 422);
        invalid = audit(UUID.randomUUID(), site);
        ((ObjectNode) invalid.path("answers")).put("unknown", "COMPLIANT");
        save("/api/audits/" + invalid.path("id").asText(), invalid, token, 400);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit", Integer.class));
    }

    @Test
    void auditHistoryUsesTheExactServerScoreAndPreservesFinalisationTimeOnRetry() throws Exception {
        UUID site = UUID.randomUUID(), id = UUID.randomUUID();
        ObjectNode payload = audit(id, site).put("finalisedAt", "2026-10-19T06:00:00.123456789Z");
        String key = payload.path("answers").fieldNames().next();
        ((ObjectNode) payload.path("answers")).put(key, "PARTIALLY_COMPLIANT");
        JsonNode saved = save("/api/audits/" + id, payload, token, 200);
        save("/api/audits/" + id, payload, token, 200);
        mvc.perform(get("/api/audits").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.items[0].percent").value(saved.path("percent").asDouble()));
        mvc.perform(get("/api/audits/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.score.percent").value(saved.path("percent").asDouble()))
                .andExpect(jsonPath("$.finalisedAt").value("2026-10-19T06:00:00.123456Z"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit WHERE score_snapshot IS NOT NULL", Integer.class));
    }

    @Test
    void migrationBackfillsOnlyUnambiguousOwnershipWithoutClaimingLegacyData() throws Exception {
        String schema = "sync_legacy_" + UUID.randomUUID().toString().replace("-", "");
        String schemaUrl = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        JdbcTemplate legacy = new JdbcTemplate(new DriverManagerDataSource(schemaUrl, USER, PASSWORD));
        try {
            Flyway.configure().dataSource(schemaUrl, USER, PASSWORD).schemas(schema).defaultSchema(schema).target("5").load().migrate();
            UUID first = UUID.randomUUID(), second = UUID.randomUUID(), template = UUID.randomUUID();
            UUID owned = UUID.randomUUID(), ambiguous = UUID.randomUUID(), orphan = UUID.randomUUID();
            for (UUID account : new UUID[]{first, second}) {
                legacy.update("INSERT INTO app_user (id, username, email, display_name, password_hash) VALUES (?, ?, ?, 'Legacy', 'unused')",
                        account, account.toString(), account + "@example.test");
            }
            for (UUID site : new UUID[]{owned, ambiguous, orphan}) {
                legacy.update("INSERT INTO site (id, name) VALUES (?, 'Legacy site')", site);
                legacy.update("INSERT INTO training_session (id, site_id, session_date, topic) VALUES (?, ?, current_date, 'Legacy training')",
                        UUID.randomUUID(), site);
            }
            legacy.update("INSERT INTO checklist_template (id, name, version) VALUES (?, 'Legacy', 1)", template);
            legacy.update("INSERT INTO rule_set (code, name, config) VALUES ('LEGACY', 'Legacy', '{}')");
            for (UUID[] pair : new UUID[][]{{owned, first}, {ambiguous, first}, {ambiguous, second}, {orphan, null}}) {
                legacy.update("""
                        INSERT INTO audit (id, site_id, template_id, rule_set_code, rule_set_snapshot, audit_date, auditor, status, created_by)
                        VALUES (?, ?, ?, 'LEGACY', '{}', current_date, 'Legacy', 'INCOMPLETE', ?)
                        """, UUID.randomUUID(), pair[0], template, pair[1]);
            }
            Flyway.configure().dataSource(schemaUrl, USER, PASSWORD).schemas(schema).defaultSchema(schema).load().migrate();
            assertEquals(first, legacy.queryForObject("SELECT owner_id FROM site WHERE id = ?", UUID.class, owned));
            assertNull(legacy.queryForObject("SELECT owner_id FROM site WHERE id = ?", UUID.class, ambiguous));
            assertNull(legacy.queryForObject("SELECT owner_id FROM site WHERE id = ?", UUID.class, orphan));
            assertEquals(first, legacy.queryForObject("SELECT owner_id FROM training_session WHERE site_id = ?", UUID.class, owned));
            assertNull(legacy.queryForObject("SELECT owner_id FROM training_session WHERE site_id = ?", UUID.class, ambiguous));
            assertEquals(4, legacy.queryForObject("SELECT count(*) FROM audit WHERE site_name = 'Legacy site'", Integer.class));
            assertEquals(1, legacy.queryForObject("SELECT count(*) FROM audit WHERE created_by IS NULL", Integer.class));
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
