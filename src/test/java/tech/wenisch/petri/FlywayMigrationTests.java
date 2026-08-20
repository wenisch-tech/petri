package tech.wenisch.petri;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that Flyway actually migrated the schema.
 *
 * <p>This exists because the opposite failed silently. With {@code flyway-core}
 * on the classpath but the {@code spring-boot-flyway} auto-configuration module
 * missing, the application started perfectly happily, Hibernate connected, the
 * health endpoint reported UP, and no migration ever ran - there was not even a
 * warning. Nothing failed until a later change expected a table to exist.
 *
 * <p>An assertion on the applied migration turns that silence into a failing
 * test, which is the only reliable way to notice a step that does nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTests {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void baselineMigrationHasBeenApplied() {
        // Asked through Flyway's own API rather than by querying its history
        // table. That table is created with quoted lower-case identifiers, so a
        // hand-written query needs quoting that differs between H2 and
        // PostgreSQL - which would make this test about identifier folding
        // instead of about whether migrations ran.
        MigrationInfo current = flyway.info().current();

        assertThat(current)
                .as("Flyway must have applied a migration")
                .isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("1");
        assertThat(current.getState().isApplied()).isTrue();
    }

    @Test
    void baselineTableExistsAndIsPopulated() {
        String description = jdbcTemplate.queryForObject(
                "SELECT description FROM petri_schema_marker WHERE id = 1", String.class);

        assertThat(description).isEqualTo("baseline");
    }
}
