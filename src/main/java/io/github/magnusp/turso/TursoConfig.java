package io.github.magnusp.turso;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import io.github.magnusp.turso.annotations.Nullable;

/** Turso Configuration. */
public final class TursoConfig {

  private static final String[] ON_OFF = {"on", "off"};

  private static final DriverPropertyInfo[] driverPropertyInfo = driverPropertyInfo();

  private final Properties pragma;

  public TursoConfig(Properties properties) {
    this.pragma = properties;
  }

  public static DriverPropertyInfo[] getDriverPropertyInfo() {
    return driverPropertyInfo;
  }

  public Properties toProperties() {
    Properties copy = new Properties();
    copy.putAll(pragma);
    return copy;
  }

  /**
   * Returns the foreign_keys setting, or null when the property is absent.
   *
   * @throws SQLException if the value is not on or off
   */
  @Nullable
  public Boolean foreignKeys() throws SQLException {
    return onOff(Pragma.FOREIGN_KEYS);
  }

  /**
   * Returns the query_only setting, or null when the property is absent.
   *
   * @throws SQLException if the value is not on or off
   */
  @Nullable
  public Boolean queryOnly() throws SQLException {
    return onOff(Pragma.QUERY_ONLY);
  }

  /**
   * Returns the busy_timeout in milliseconds, or null when the property is absent.
   *
   * @throws SQLException if the value is not a non-negative integer
   */
  @Nullable
  public Long busyTimeout() throws SQLException {
    String value = pragma.getProperty(Pragma.BUSY_TIMEOUT.pragmaName);
    if (value == null) {
      return null;
    }
    String expected = "a non-negative number of milliseconds";
    long millis;
    try {
      millis = Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw invalid(Pragma.BUSY_TIMEOUT, value, expected);
    }
    if (millis < 0) {
      throw invalid(Pragma.BUSY_TIMEOUT, value, expected);
    }
    return millis;
  }

  @Nullable
  private Boolean onOff(Pragma key) throws SQLException {
    String value = pragma.getProperty(key.pragmaName);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if ("on".equalsIgnoreCase(trimmed)) {
      return true;
    }
    if ("off".equalsIgnoreCase(trimmed)) {
      return false;
    }
    throw invalid(key, value, "on or off");
  }

  private static SQLException invalid(Pragma key, String value, String expected) {
    return new SQLException(
        "Invalid value for " + key.pragmaName + ": '" + value + "' (expected " + expected + ")",
        "22023");
  }

  public enum Pragma {
    FOREIGN_KEYS("foreign_keys", "Enforce foreign key constraints. Default off.", ON_OFF),
    QUERY_ONLY("query_only", "Reject writes on this connection. Default off.", ON_OFF),
    BUSY_TIMEOUT("busy_timeout", "Milliseconds to wait for a locked database. Default 0.", null);

    private final String pragmaName;
    private final String description;
    private final String[] choices;

    Pragma(String pragmaName, String description, String[] choices) {
      this.pragmaName = pragmaName;
      this.description = description;
      this.choices = choices;
    }

    public String getPragmaName() {
      return pragmaName;
    }
  }

  private static DriverPropertyInfo[] driverPropertyInfo() {
    return Arrays.stream(Pragma.values())
        .map(
            p -> {
              DriverPropertyInfo info = new DriverPropertyInfo(p.pragmaName, null);
              info.description = p.description;
              info.choices = p.choices;
              info.required = false;
              return info;
            })
        .toArray(DriverPropertyInfo[]::new);
  }
}
