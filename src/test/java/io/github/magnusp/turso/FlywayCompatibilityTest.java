package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Flyway identifies the database by product name, not by the {@code jdbc:turso:} URL prefix, so
 * this pins the compatibility contract from issue #77.
 */
class FlywayCompatibilityTest {

  @Test
  void flywayDetectsSqliteAndAppliesMigration(@TempDir Path dir) throws Exception {
    String url = "jdbc:turso:" + dir.resolve("flyway.db");
    TursoDataSource dataSource = new TursoDataSource(new TursoConfig(new Properties()), url);
    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/flyway")
            .load();

    MigrateResult result = flyway.migrate();

    assertThat(result.success).isTrue();
    assertThat(result.migrationsExecuted).isEqualTo(1);
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT name FROM flyway_widget")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("migrated");
      assertThat(rows.next()).isFalse();
    }
  }
}
