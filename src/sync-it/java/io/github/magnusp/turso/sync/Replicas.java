package io.github.magnusp.turso.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers shared by the sync integration tests. */
final class Replicas {

  private static final Pattern VALUE = Pattern.compile("\"value\":\"([^\"]*)\"");

  static String url(Path localDatabase) {
    return "jdbc:turso:" + localDatabase.toAbsolutePath() + "?sync_url=" + SyncServer.get().url();
  }

  static Connection connect(Path localDatabase) throws SQLException {
    return DriverManager.getConnection(url(localDatabase));
  }

  static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static List<String> strings(Connection connection, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }

  static int intValue(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  static void recreateTable(Connection connection, String table) throws SQLException {
    exec(connection, "drop table if exists " + table);
    exec(connection, "create table " + table + " (v text)");
  }

  static List<String> serverStrings(String sql) throws Exception {
    return values(SyncServer.get().execute(null, sql));
  }

  static List<String> values(HttpResponse<String> response) {
    assertThat(response.statusCode())
        .as("pipeline response body: %s", response.body())
        .isEqualTo(200);
    Matcher matcher = VALUE.matcher(response.body());
    List<String> values = new ArrayList<>();
    while (matcher.find()) {
      values.add(matcher.group(1));
    }
    return values;
  }

  /** Removes the local database and every sync sidecar file. */
  static void deleteReplica(Path localDatabase) throws IOException {
    String base = localDatabase.toString();
    for (String suffix : new String[] {"", "-wal", "-wal-revert", "-shm", "-info", "-changes"}) {
      Files.deleteIfExists(Path.of(base + suffix));
    }
  }

  private Replicas() {}
}
