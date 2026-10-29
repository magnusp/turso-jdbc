package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ExperimentalFeatureTest {

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection("jdbc:turso::memory:");
  }

  @Test
  void withoutRowidTableThrowsFeatureNotSupported() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      // Turso keeps WITHOUT ROWID behind an experimental gate. When the gate is removed this
      // expectation, the translation, and the DBMetaDataTest#autoincrement quarantine go away.
      Throwable thrown =
          catchThrowable(
              () ->
                  statement.executeUpdate(
                      "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT) WITHOUT ROWID"));

      assertThat(thrown).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThat(((SQLException) thrown).getSQLState()).isEqualTo("0A000");
      assertThat(thrown)
          .hasMessageContaining("WITHOUT ROWID")
          .hasMessageContaining("--experimental-without-rowid");
    }
  }

  @Test
  void storedGeneratedColumnThrowsFeatureNotSupported() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      Throwable thrown =
          catchThrowable(
              () ->
                  statement.executeUpdate(
                      "CREATE TABLE t (id INTEGER PRIMARY KEY,"
                          + " v INTEGER GENERATED ALWAYS AS (id + 1) STORED)"));

      assertThat(thrown).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThat(((SQLException) thrown).getSQLState()).isEqualTo("0A000");
      assertThat(thrown).hasMessageContaining("generated columns");
    }
  }

  @Test
  void virtualGeneratedColumnStillWorks() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE t (id INTEGER PRIMARY KEY,"
              + " v INTEGER GENERATED ALWAYS AS (id * 2) VIRTUAL)");
      statement.executeUpdate("INSERT INTO t (id) VALUES (21)");

      try (ResultSet rows = statement.executeQuery("SELECT v FROM t WHERE id = 21")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(42);
      }
    }
  }

  @Test
  void invalidStatementStillThrowsSyntaxError() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate("SELEC 1"))
          .isInstanceOf(SQLSyntaxErrorException.class);
    }
  }
}
