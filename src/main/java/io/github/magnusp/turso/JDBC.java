package io.github.magnusp.turso;

import java.sql.*;
import java.util.Locale;
import java.util.Properties;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.annotations.SkipNullableCheck;
import io.github.magnusp.turso.core.TursoPropertiesHolder;
import io.github.magnusp.turso.jdbc4.JDBC4Connection;
import io.github.magnusp.turso.utils.Logger;
import io.github.magnusp.turso.utils.LoggerFactory;

/** Turso JDBC driver implementation. */
public final class JDBC implements Driver {

  private static final Logger logger = LoggerFactory.getLogger(JDBC.class);

  private static final String VALID_URL_PREFIX = "jdbc:turso:";
  private static final String LIBSQL_FILE_URL_PREFIX = "jdbc:libsql:file:";

  static {
    try {
      DriverManager.registerDriver(new JDBC());
    } catch (Exception e) {
      logger.error("Failed to register driver", e);
    }
  }

  /**
   * Creates a new Turso JDBC connection.
   *
   * @param url the database URL
   * @param properties connection properties
   * @return a new connection instance, or null if the URL is not valid
   * @throws SQLException if a database access error occurs
   */
  @Nullable
  public static JDBC4Connection createConnection(String url, Properties properties)
      throws SQLException {
    if (!isValidURL(url)) return null;

    url = url.trim();
    Properties merged = new Properties();
    if (properties != null) {
      merged.putAll(properties);
    }
    String address = extractAddress(url);
    int query = address.indexOf('?');
    if (query >= 0) {
      merged.putAll(parseQuery(address.substring(query + 1)));
      address = address.substring(0, query);
    }
    return new JDBC4Connection(url, address, merged);
  }

  private static Properties parseQuery(String query) {
    Properties properties = new Properties();
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      if (equals < 0) {
        properties.setProperty(pair, "");
      } else {
        properties.setProperty(pair.substring(0, equals), pair.substring(equals + 1));
      }
    }
    return properties;
  }

  private static boolean isValidURL(String url) {
    if (url == null) {
      return false;
    }
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith(VALID_URL_PREFIX) || lower.startsWith(LIBSQL_FILE_URL_PREFIX);
  }

  private static String extractAddress(String url) {
    if (url.toLowerCase(Locale.ROOT).startsWith(VALID_URL_PREFIX)) {
      return url.substring(VALID_URL_PREFIX.length());
    }
    return url.substring(LIBSQL_FILE_URL_PREFIX.length());
  }

  @Nullable
  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    return createConnection(url, info);
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return isValidURL(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return TursoConfig.getDriverPropertyInfo();
  }

  @Override
  public int getMajorVersion() {
    return Integer.parseInt(TursoPropertiesHolder.getDriverVersion().split("\\.")[0]);
  }

  @Override
  public int getMinorVersion() {
    return Integer.parseInt(TursoPropertiesHolder.getDriverVersion().split("\\.")[1]);
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  @SkipNullableCheck
  public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
    // TODO
    return null;
  }
}
