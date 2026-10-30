package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiStatementTest {

  private static Connection connect(Path dir, String name) throws SQLException {
    return DriverManager.getConnection("jdbc:turso:" + dir.resolve(name + ".db"));
  }

  private static long count(Statement statement) throws SQLException {
    try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM t")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static boolean tableExists(Statement statement, String name) throws SQLException {
    try (ResultSet rows =
        statement.executeQuery(
            "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = '" + name + "'")) {
      rows.next();
      return rows.getLong(1) > 0;
    }
  }

  @Test
  void executeUpdateReturnsCombinedAffectedCount(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "combined");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");

      assertThat(
              statement.executeUpdate(
                  "INSERT INTO t (id, v) VALUES (1, 'a');"
                      + "INSERT INTO t (id, v) VALUES (2, 'b');"
                      + "UPDATE t SET v = 'c' WHERE id = 2;"))
          .isEqualTo(3);
      assertThat(count(statement)).isEqualTo(2);
    }
  }

  @Test
  void semicolonInsideStringLiteralDoesNotSplitTheStatement(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "literal");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");

      assertThat(
              statement.executeUpdate(
                  "INSERT INTO t (v) VALUES ('a;b'); INSERT INTO t (v) VALUES ('c')"))
          .isEqualTo(2);
      try (ResultSet rows = statement.executeQuery("SELECT v FROM t ORDER BY rowid")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("a;b");
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("c");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void semicolonsInsideTriggerBodyStayInOneStatement(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "trigger");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT); CREATE TABLE log (msg TEXT)");

      assertThat(
              statement.executeUpdate(
                  "CREATE TRIGGER t_ins AFTER INSERT ON t BEGIN"
                      + " INSERT INTO log (msg) VALUES ('first;');"
                      + " INSERT INTO log (msg) VALUES ('second');"
                      + " END;"))
          .isZero();

      statement.executeUpdate("INSERT INTO t (v) VALUES ('x')");
      try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM log")) {
        rows.next();
        assertThat(rows.getLong(1)).isEqualTo(2);
      }
    }
  }

  @Test
  void nonAsciiTextDoesNotShiftTheNextStatement(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "unicode");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");

      // The engine reports the tail as a byte offset. A wrong conversion would slice the next
      // statement inside a multi-byte character.
      assertThat(
              statement.executeUpdate(
                  "INSERT INTO t (v) VALUES ('håll 😀'); INSERT INTO t (v) VALUES ('done')"))
          .isEqualTo(2);
      try (ResultSet rows = statement.executeQuery("SELECT v FROM t ORDER BY rowid")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("håll 😀");
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("done");
      }
    }
  }

  @Test
  void trailingCommentDoesNotAddAStatement(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "comment");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");

      assertThat(
              statement.executeUpdate(
                  "INSERT INTO t (v) VALUES ('once'); -- nothing follows; not a statement"))
          .isEqualTo(1);
      assertThat(count(statement)).isEqualTo(1);
    }
  }

  @Test
  void withClauseInsertCountsAsAnUpdate(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "with-insert");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v INTEGER)");

      assertThat(
              statement.executeUpdate(
                  "WITH x AS (SELECT 1 AS v) INSERT INTO t SELECT v FROM x"))
          .isEqualTo(1);
    }
  }

  @Test
  void leadingEmptyStatementsAndCommentsKeepCountAndKeys(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "leading-trivia");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");

      assertThat(
              statement.executeUpdate(
                  ";;;-- lead\nINSERT INTO t (v) VALUES ('a');"
                      + "/* gap */ INSERT INTO t (v) VALUES ('b')"))
          .isEqualTo(2);
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getLong(1)).isEqualTo(1L);
        assertThat(keys.next()).isTrue();
        assertThat(keys.getLong(1)).isEqualTo(2L);
        assertThat(keys.next()).isFalse();
      }
    }
  }

  @Test
  void manualTransactionControlInsideTheBatchKeepsStateCoherent(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "manual-control");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");
      connection.setAutoCommit(false);

      statement.executeUpdate("BEGIN");
      statement.executeUpdate("INSERT INTO t (v) VALUES ('rolled back')");
      connection.rollback();

      statement.executeUpdate("INSERT INTO t (v) VALUES ('kept'); COMMIT");
      connection.commit();

      try (ResultSet rows = statement.executeQuery("SELECT v FROM t")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("kept");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void laterStatementFailurePropagatesAfterEarlierStatementsRan(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "failing");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v INTEGER NOT NULL)");

      // Autocommit mode keeps the first insert. The driver does not undo earlier statements.
      assertThatThrownBy(
              () ->
                  statement.executeUpdate(
                      "INSERT INTO t (v) VALUES (1); INSERT INTO t (v) VALUES (NULL)"))
          .isInstanceOf(SQLException.class);
      assertThat(count(statement)).isEqualTo(1);
    }
  }

  @Test
  void executeRunsOnlyTheFirstStatementAndIgnoresTheTail(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "execute-first");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v INTEGER)");

      assertThat(statement.execute("INSERT INTO t (v) VALUES (1); INSERT INTO t (v) VALUES (2)"))
          .isFalse();
      assertThat(statement.getUpdateCount()).isEqualTo(1);
      assertThat(count(statement)).isEqualTo(1);

      // A broken tail is ignored too, matching the reference driver.
      assertThat(statement.execute("CREATE TABLE ignored (v); BOGUS")).isFalse();
      assertThat(tableExists(statement, "ignored")).isTrue();
    }
  }

  @Test
  void executeQueryRunsOnlyTheFirstStatementAndIgnoresTheTail(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "query-first");
        Statement statement = connection.createStatement()) {
      try (ResultSet rows = statement.executeQuery("select 1; select 2; CREATE TABLE ignored (v)")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(1);
        assertThat(rows.next()).isFalse();
      }
      assertThat(tableExists(statement, "ignored")).isFalse();
    }
  }

  @Test
  void executeQueryOnDdlThrowsAfterTheStatementRuns(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "query-ddl");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeQuery("CREATE TABLE q (x)"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> {
                assertThat(e.getMessage()).isEqualTo("query does not return ResultSet");
                assertThat(e.getSQLState()).isEqualTo("SQLITE_DONE");
                assertThat(e.getErrorCode()).isEqualTo(101);
              });
      assertThat(tableExists(statement, "q")).isTrue();
    }
  }

  @Test
  void commentOnlyAndBlankInputReturnZero(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "comment-only");
        Statement statement = connection.createStatement()) {
      assertThat(statement.executeUpdate("-- just a comment")).isZero();
      assertThat(statement.executeUpdate("/* just a block comment */")).isZero();
      assertThat(statement.executeUpdate("  \n\t ")).isZero();
      assertThat(statement.executeUpdate(";;;")).isZero();
      assertThat(statement.executeUpdate("")).isZero();
    }
  }

  @Test
  void semicolonsInsideCommentsDoNotSplitStatements(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "comment-semicolons");
        Statement statement = connection.createStatement()) {
      assertThat(statement.executeUpdate("-- a; b\nCREATE TABLE t1 (x)")).isZero();
      assertThat(statement.executeUpdate("/* a; b */ CREATE TABLE t3 (x)")).isZero();

      assertThat(tableExists(statement, "t1")).isTrue();
      assertThat(tableExists(statement, "t3")).isTrue();
    }
  }

  @Test
  void migrationScriptWithSemicolonInCommentRuns(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "migration");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE notifications (id INTEGER)");

      assertThat(
              statement.executeUpdate(
                  "-- Notification mutates through markRead()/markClicked(); without a version the\n"
                      + "-- concurrent updates are silent last-write-wins.\n"
                      + "ALTER TABLE notifications ADD COLUMN version INTEGER NOT NULL DEFAULT 0;"))
          .isZero();

      statement.executeUpdate("INSERT INTO notifications (id) VALUES (1)");
      try (ResultSet rows = statement.executeQuery("SELECT version FROM notifications")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    }
  }

  @Test
  void commentBetweenStatementsDoesNotBreakTheScript(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "comment-between");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE t (v TEXT)");

      assertThat(
              statement.executeUpdate(
                  "INSERT INTO t (v) VALUES ('a'); -- gap; here\n"
                      + "INSERT INTO t (v) VALUES ('b'); /* gap; here */"
                      + "INSERT INTO t (v) VALUES ('c')"))
          .isEqualTo(3);
      assertThat(count(statement)).isEqualTo(3);
    }
  }

  @Test
  void executeAndExecuteQueryRejectCommentOnlyInputWithoutANativeError(@TempDir Path dir)
      throws Exception {
    try (Connection connection = connect(dir, "execute-comment-only");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("-- only a comment"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> assertThat(e.getMessage()).doesNotContain("no statements"));
      assertThatThrownBy(() -> statement.executeQuery("/* only a comment */"))
          .isInstanceOfSatisfying(
              SQLException.class,
              e -> assertThat(e.getMessage()).doesNotContain("no statements"));
    }
  }

  @Test
  void executeAcceptsTrailingCommentAfterOneStatement(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "execute-comment");
        Statement statement = connection.createStatement()) {
      assertThat(statement.execute("select 1; -- trailing comment")).isTrue();
    }
  }
}
