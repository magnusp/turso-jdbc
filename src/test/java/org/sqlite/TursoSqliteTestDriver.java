package org.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import tech.turso.JDBC;

/**
 * Test-only shim that lets the vendored xerial tests keep their {@code jdbc:sqlite:} URLs.
 *
 * <p>The shim strips the prefix and opens the Turso driver directly. It is not part of the shipped
 * jar.
 */
public final class TursoSqliteTestDriver implements Driver {

  private static final String PREFIX = "jdbc:sqlite:";

  static {
    register();
  }

  public static void register() {
    try {
      DriverManager.registerDriver(new TursoSqliteTestDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    String path = url.substring(PREFIX.length());
    int query = path.indexOf('?');
    if (query >= 0) {
      path = path.substring(0, query);
    }
    if (path.isEmpty()) {
      path = temporaryFile();
    }
    return JDBC.createConnection("jdbc:turso:" + path, info);
  }

  private static String temporaryFile() throws SQLException {
    try {
      Path file = Files.createTempFile("turso-jdbc-xerial-", ".db");
      file.toFile().deleteOnExit();
      return file.toString();
    } catch (IOException e) {
      throw new SQLException("cannot create a temporary database file", e);
    }
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion() {
    return 0;
  }

  @Override
  public int getMinorVersion() {
    return 1;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }
}
