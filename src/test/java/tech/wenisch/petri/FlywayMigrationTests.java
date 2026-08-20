package tech.wenisch.petri;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that Flyway actually migrated the schema.
 *
 * <p>This exists because the opposite failed silently. With {@code flyway-core}
 * on the classpath but the {@code spring-boot-flyway} auto-configuration module
 * missing, the application started happily, Hibernate connected, health reported
 * UP, and no migration ever ran - without so much as a warning. Nothing failed
 * until a later change expected a table to exist.
 *
 * <p>Assertions are on state rather than on a version number, so adding a
 * migration does not require editing this test.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTests {

    @Autowired
    private Flyway flyway;

    @Test
    void everyMigrationHasBeenApplied() {
        // Asked through Flyway's API rather than by querying flyway_schema_history:
        // that table uses quoted lower-case identifiers, and H2 folds unquoted
        // identifiers to upper case, so a hand-written query would end up testing
        // identifier folding instead of migrations.
        assertThat(flyway.info().pending())
                .as("no migration may be left pending at startup")
                .isEmpty();

        MigrationInfo current = flyway.info().current();
        assertThat(current).as("at least one migration must have been applied").isNotNull();
        assertThat(current.getState().isApplied()).isTrue();
    }

    @Test
    void allAppliedMigrationsSucceeded() {
        assertThat(flyway.info().applied())
                .isNotEmpty()
                .allSatisfy(info -> assertThat(info.getState().isFailed()).isFalse());
    }
}
