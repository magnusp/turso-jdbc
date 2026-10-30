package io.github.magnusp.turso;

import io.github.magnusp.turso.annotations.Nullable;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Turso Configuration. */
public final class TursoConfig {

  private static final String[] ON_OFF = {"on", "off"};
  private static final String DEFAULT_CLIENT_NAME = "turso-jdbc";

  private static final String[] SYNC_URL_KEYS = {"sync_url", "syncUrl", "remote_url", "remoteUrl"};
  private static final String[] AUTH_TOKEN_KEYS = {"auth_token", "authToken"};
  private static final String[] CLIENT_NAME_KEYS = {"client_name", "clientName"};
  private static final String[] BOOTSTRAP_KEYS = {"sync_bootstrap", "bootstrap_if_empty"};
  private static final String[] LONG_POLL_KEYS = {"sync_long_poll_timeout", "long_poll_timeout_ms"};

  private static final DriverPropertyInfo[] driverPropertyInfo = driverPropertyInfo();

  private final Properties properties;

  public TursoConfig(Properties properties) {
    this.properties = properties;
  }

  public static DriverPropertyInfo[] getDriverPropertyInfo() {
    return driverPropertyInfo;
  }

  public Properties toProperties() {
    Properties copy = new Properties();
    copy.putAll(properties);
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
    String value = properties.getProperty(Pragma.BUSY_TIMEOUT.pragmaName);
    if (value == null) {
      return null;
    }
    return nonNegative(value, Pragma.BUSY_TIMEOUT.pragmaName);
  }

  /**
   * Returns the normalized remote URL for an embedded replica, or null when the connection is
   * local-only.
   *
   * <p>Accepts {@code sync_url}, {@code syncUrl}, {@code remote_url}, and {@code remoteUrl}. The
   * libSQL and Turso schemes are rewritten to {@code https}.
   *
   * @throws SQLException if the URL is empty, conflicting, or not http(s)/libsql/turso
   */
  @Nullable
  public String syncUrl() throws SQLException {
    String url = null;
    String source = null;
    for (String key : SYNC_URL_KEYS) {
      String value = properties.getProperty(key);
      if (value == null) {
        continue;
      }
      String trimmed = value.trim();
      if (trimmed.isEmpty()) {
        throw invalid(key, value, "a remote URL");
      }
      if (url == null) {
        url = trimmed;
        source = key;
      } else if (!url.equals(trimmed)) {
        throw new SQLException(
            "Conflicting sync URLs in " + source + " and " + key + " (expected one remote URL)",
            "22023");
      }
    }
    if (url == null) {
      return null;
    }

    String lower = url.toLowerCase(Locale.ROOT);
    if (lower.startsWith("libsql://")) {
      return "https://" + url.substring("libsql://".length());
    }
    if (lower.startsWith("turso://")) {
      return "https://" + url.substring("turso://".length());
    }
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      throw invalid(source, url, "an http://, https://, libsql://, or turso:// URL");
    }
    return url;
  }

  /** Returns the bearer token for the remote, or null when it is absent or blank. */
  @Nullable
  public String authToken() {
    String value = first(AUTH_TOKEN_KEYS);
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /** Returns the sync client name prefix, defaulting to {@code turso-jdbc}. */
  public String clientName() {
    String value = first(CLIENT_NAME_KEYS);
    return value == null || value.isBlank() ? DEFAULT_CLIENT_NAME : value.trim();
  }

  /**
   * Returns whether an empty local replica bootstraps from the remote. Defaults to on.
   *
   * @throws SQLException if the value is not on or off
   */
  public boolean bootstrapIfEmpty() throws SQLException {
    String value = first(BOOTSTRAP_KEYS);
    if (value == null) {
      return true;
    }
    String trimmed = value.trim();
    if ("on".equalsIgnoreCase(trimmed)) {
      return true;
    }
    if ("off".equalsIgnoreCase(trimmed)) {
      return false;
    }
    throw invalid(BOOTSTRAP_KEYS[0], value, "on or off");
  }

  /**
   * Returns the long-poll timeout for pulls in milliseconds. Defaults to zero (no long poll).
   *
   * @throws SQLException if the value is not a non-negative integer
   */
  public long longPollTimeoutMs() throws SQLException {
    String value = first(LONG_POLL_KEYS);
    if (value == null) {
      return 0L;
    }
    return nonNegative(value, LONG_POLL_KEYS[0]);
  }

  @Nullable
  private String first(String[] keys) {
    for (String key : keys) {
      String value = properties.getProperty(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private long nonNegative(String value, String key) throws SQLException {
    String expected = "a non-negative number of milliseconds";
    long millis;
    try {
      millis = Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw invalid(key, value, expected);
    }
    if (millis < 0) {
      throw invalid(key, value, expected);
    }
    return millis;
  }

  @Nullable
  private Boolean onOff(Pragma key) throws SQLException {
    String value = properties.getProperty(key.pragmaName);
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
    throw invalid(key.pragmaName, value, "on or off");
  }

  private static SQLException invalid(String key, String value, String expected) {
    return new SQLException(
        "Invalid value for " + key + ": '" + value + "' (expected " + expected + ")", "22023");
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
    List<DriverPropertyInfo> infos = new ArrayList<>();
    for (Pragma pragma : Pragma.values()) {
      infos.add(info(pragma.pragmaName, pragma.description, pragma.choices));
    }
    infos.add(
        info(
            "sync_url",
            "Remote URL for an embedded replica. The local path stays the database path.",
            null));
    infos.add(info("auth_token", "Bearer token sent to the sync remote.", null));
    infos.add(
        info("client_name", "Prefix for the generated sync client id. Default turso-jdbc.", null));
    infos.add(
        info(
            "sync_bootstrap",
            "Pull the initial state when the local replica is empty. Default on.",
            ON_OFF));
    infos.add(
        info(
            "sync_long_poll_timeout",
            "Milliseconds the server may hold a pull open. Default 0.",
            null));
    return infos.toArray(DriverPropertyInfo[]::new);
  }

  private static DriverPropertyInfo info(String name, String description, String[] choices) {
    DriverPropertyInfo info = new DriverPropertyInfo(name, null);
    info.description = description;
    info.choices = choices;
    info.required = false;
    return info;
  }
}
