package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionPropertiesTest {

  private static Connection connect(Path dir, String name, String query) throws SQLException {
    return DriverManager.getConnection("jdbc:turso:" + dir.resolve(name + ".db") + query);
  }

  private static int pragma(Statement statement, String name) throws SQLException {
    try (ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static void createParentAndChild(Statement statement) throws SQLException {
    statement.executeUpdate("CREATE TABLE p (id INTEGER PRIMARY KEY)");
    statement.executeUpdate("CREATE TABLE c (pid INTEGER REFERENCES p(id))");
  }

  @Test
  void foreignKeysDefaultsOff(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "fk-default", "");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "foreign_keys")).isZero();
    }
  }

  @Test
  void foreignKeysOnEnforcesBeforeUserSql(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "fk-on", "?foreign_keys=on");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "foreign_keys")).isEqualTo(1);
      createParentAndChild(statement);
      assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO c VALUES (99)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("FOREIGN KEY");
    }
  }

  @Test
  void foreignKeysOffKeepsOrphanRows(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "fk-off", "?foreign_keys=off");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "foreign_keys")).isZero();
      createParentAndChild(statement);
      assertThat(statement.executeUpdate("INSERT INTO c VALUES (99)")).isEqualTo(1);
    }
  }

  @Test
  void propertiesObjectAppliesForeignKeys(@TempDir Path dir) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("foreign_keys", "on");
    try (Connection connection =
            DriverManager.getConnection("jdbc:turso:" + dir.resolve("fk-props.db"), properties);
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "foreign_keys")).isEqualTo(1);
    }
  }

  @Test
  void invalidForeignKeysNamesTheKey(@TempDir Path dir) {
    assertThatThrownBy(() -> connect(dir, "fk-bad", "?foreign_keys=maybe"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("foreign_keys");
  }

  @Test
  void queryOnlyOnRejectsWrites(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "qo-on", "?query_only=on");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "query_only")).isEqualTo(1);
      assertThatThrownBy(() -> statement.executeUpdate("CREATE TABLE t (v TEXT)"))
          .isInstanceOf(SQLException.class);
      try (ResultSet rows = statement.executeQuery("SELECT 1")) {
        assertThat(rows.next()).isTrue();
      }
    }
  }

  @Test
  void queryOnlyDefaultsOff(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "qo-default", "");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "query_only")).isZero();
      assertThat(statement.executeUpdate("CREATE TABLE t (v TEXT)")).isZero();
    }
  }

  @Test
  void invalidQueryOnlyNamesTheKey(@TempDir Path dir) {
    assertThatThrownBy(() -> connect(dir, "qo-bad", "?query_only=yes"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("query_only");
  }

  @Test
  void busyTimeoutAcceptsNonNegativeMilliseconds(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "busy", "?busy_timeout=5000");
        Statement statement = connection.createStatement()) {
      assertThat(statement.executeUpdate("CREATE TABLE t (v TEXT)")).isZero();
    }
  }

  @Test
  void invalidBusyTimeoutNamesTheKey(@TempDir Path dir) {
    assertThatThrownBy(() -> connect(dir, "busy-text", "?busy_timeout=soon"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("busy_timeout");
    assertThatThrownBy(() -> connect(dir, "busy-negative", "?busy_timeout=-1"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("busy_timeout");
  }

  @Test
  void unknownKeysAreIgnored(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "unknown", "?unknown_key=1&foreign_keys=on");
        Statement statement = connection.createStatement()) {
      assertThat(pragma(statement, "foreign_keys")).isEqualTo(1);
    }
  }

  @Test
  void driverPropertyInfoListsSupportedKeys() throws Exception {
    DriverPropertyInfo[] info =
        new JDBC().getPropertyInfo("jdbc:turso::memory:", new Properties());

    assertThat(info)
        .extracting(p -> p.name)
        .containsExactly(
            "foreign_keys",
            "query_only",
            "busy_timeout",
            "sync_url",
            "auth_token",
            "client_name",
            "sync_bootstrap",
            "sync_long_poll_timeout");
    assertThat(info[0].choices).containsExactly("on", "off");
    assertThat(info[1].choices).containsExactly("on", "off");
    assertThat(info[2].choices).isNull();
    assertThat(info[2].description).isNotBlank();
    assertThat(info[6].choices).containsExactly("on", "off");
  }
}
