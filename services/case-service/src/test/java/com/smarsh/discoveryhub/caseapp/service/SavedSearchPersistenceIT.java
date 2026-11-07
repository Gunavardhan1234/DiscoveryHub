package com.smarsh.discoveryhub.caseapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarsh.discoveryhub.caseapp.api.dto.CaseCreateRequest;
import com.smarsh.discoveryhub.caseapp.api.dto.SavedSearchRequest;
import com.smarsh.discoveryhub.caseapp.domain.model.MatterType;
import com.smarsh.discoveryhub.caseapp.domain.port.AuditEventPublisher;
import com.smarsh.discoveryhub.testsupport.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the {@code saved_searches.spec} JPA/Postgres type
 * mismatch: the column is {@code jsonb} (V1 migration) but {@link
 * com.smarsh.discoveryhub.caseapp.infrastructure.persistence.SavedSearchEntity#spec}
 * used to be a plain {@code String} with no {@code @JdbcTypeCode}, so every
 * insert through the real {@link SavedSearchService} (JPA) path failed with
 * "column \"spec\" is of type jsonb but expression is of type character
 * varying" — {@code CaseSchemaIT} didn't catch this because it round-trips
 * {@code saved_searches} through raw JDBC with an explicit {@code ::jsonb}
 * cast, bypassing Hibernate entirely.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
// A regression test born from a real production-shaped bug (see the class-level Javadoc): proves
// the specific gap the earlier CaseSchemaIT missed — the real JPA/Hibernate write path, not a raw
// JDBC round-trip, actually persists the jsonb column correctly. This is TDD-after-the-fact: the
// bug was found first, then this test was written to pin the fix and prevent the exact regression.
class SavedSearchPersistenceIT extends PostgresTestBase {

    @MockBean
    private AuditEventPublisher auditEventPublisher;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestBase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestBase::dbUsername);
        registry.add("spring.datasource.password", PostgresTestBase::dbPassword);
    }

    @Autowired CaseService caseService;
    @Autowired SavedSearchService savedSearchService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void savedSearchPersistsThroughRealJpaAndPostgres() throws Exception {
        var created = caseService.create(new CaseCreateRequest(
                "Saved Search Persistence", "regression test", MatterType.INVESTIGATION, "investigator@demo"));

        var saved = savedSearchService.create(created.id(),
                new SavedSearchRequest("wire transfers", "{\"q\":\"wire transfer\"}"));

        assertThat(saved.name()).isEqualTo("wire transfers");
        // jsonb round-trips through Postgres's canonical text form (e.g. a
        // space after ':'), so compare parsed JSON rather than raw text.
        assertThat(objectMapper.readTree(saved.spec()))
                .isEqualTo(objectMapper.readTree("{\"q\":\"wire transfer\"}"));

        var reloaded = savedSearchService.get(saved.id());
        assertThat(objectMapper.readTree(reloaded.spec()))
                .isEqualTo(objectMapper.readTree("{\"q\":\"wire transfer\"}"));

        var listed = savedSearchService.list(created.id());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).id()).isEqualTo(saved.id());
    }
}
