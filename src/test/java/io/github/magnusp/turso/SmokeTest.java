package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class SmokeTest {

  @Test
  void crudInMemory() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
      assertThat(statement.executeUpdate("INSERT INTO t (name) VALUES ('hello')")).isEqualTo(1);

      try (ResultSet rows = statement.executeQuery("SELECT id, name FROM t")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isEqualTo(1L);
        assertThat(rows.getString(2)).isEqualTo("hello");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void fileUrlAlias() throws Exception {
    String path = java.nio.file.Files.createTempFile("turso-jdbc-", ".db").toString();
    try (Connection connection = DriverManager.getConnection("jdbc:libsql:file:" + path);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");
      statement.executeUpdate("INSERT INTO t VALUES ('file')");
      try (ResultSet rows = statement.executeQuery("SELECT v FROM t")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("file");
      }
    }
  }

  @Test
  void constraintErrorIsMapped() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:turso::memory:");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT UNIQUE)");
      statement.executeUpdate("INSERT INTO t VALUES ('x')");
      assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO t VALUES ('x')"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("UNIQUE");
    }
  }
}
