package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SyncConfigTest {

  private static TursoConfig config(String... pairs) {
    Properties properties = new Properties();
    for (int i = 0; i < pairs.length; i += 2) {
      properties.setProperty(pairs[i], pairs[i + 1]);
    }
    return new TursoConfig(properties);
  }

  @Test
  void syncUrlAcceptsEveryAlias() throws Exception {
    assertThat(config("sync_url", "http://host:8080").syncUrl()).isEqualTo("http://host:8080");
    assertThat(config("syncUrl", "http://host:8080").syncUrl()).isEqualTo("http://host:8080");
    assertThat(config("remote_url", "http://host:8080").syncUrl()).isEqualTo("http://host:8080");
    assertThat(config("remoteUrl", "http://host:8080").syncUrl()).isEqualTo("http://host:8080");
  }

  @Test
  void libsqlAndTursoSchemesBecomeHttps() throws Exception {
    assertThat(config("sync_url", "libsql://db.turso.io").syncUrl())
        .isEqualTo("https://db.turso.io");
    assertThat(config("sync_url", "turso://db.turso.io").syncUrl())
        .isEqualTo("https://db.turso.io");
  }

  @Test
  void conflictingSyncUrlsAreRejected() {
    assertThatThrownBy(() -> config("sync_url", "http://a:1", "remote_url", "http://b:2").syncUrl())
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("22023"))
        .hasMessageContaining("Conflicting sync URLs");
  }

  @Test
  void nonHttpSyncUrlIsRejected() {
    assertThatThrownBy(() -> config("sync_url", "/tmp/remote.db").syncUrl())
        .isInstanceOfSatisfying(
            SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("22023"))
        .hasMessageContaining("http://");
  }

  @Test
  void emptySyncUrlIsRejected() {
    assertThatThrownBy(() -> config("sync_url", "  ").syncUrl())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("sync_url");
  }

  @Test
  void syncDefaults() throws Exception {
    TursoConfig config = config();
    assertThat(config.syncUrl()).isNull();
    assertThat(config.authToken()).isNull();
    assertThat(config.clientName()).isEqualTo("turso-jdbc");
    assertThat(config.bootstrapIfEmpty()).isTrue();
    assertThat(config.longPollTimeoutMs()).isZero();
  }

  @Test
  void authTokenAndClientNameAreTrimmed() {
    TursoConfig config = config("auth_token", "  secret  ", "client_name", "  app  ");
    assertThat(config.authToken()).isEqualTo("secret");
    assertThat(config.clientName()).isEqualTo("app");
  }

  @Test
  void bootstrapCanBeDisabled() throws Exception {
    assertThat(config("sync_bootstrap", "off").bootstrapIfEmpty()).isFalse();
    assertThat(config("bootstrap_if_empty", "on").bootstrapIfEmpty()).isTrue();
  }

  @Test
  void invalidBootstrapIsRejected() {
    assertThatThrownBy(() -> config("sync_bootstrap", "sometimes").bootstrapIfEmpty())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("sync_bootstrap");
  }

  @Test
  void invalidLongPollTimeoutIsRejected() {
    assertThatThrownBy(() -> config("sync_long_poll_timeout", "-1").longPollTimeoutMs())
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("sync_long_poll_timeout");
  }

  @Test
  void driverPropertyInfoAdvertisesSyncProperties() throws Exception {
    Driver driver = new JDBC();
    assertThat(driver.getPropertyInfo("jdbc:turso:/tmp/app.db", new Properties()))
        .extracting(info -> info.name)
        .contains("sync_url", "auth_token", "client_name", "sync_bootstrap");
  }

  @Test
  void remoteOnlyUrlFailsWithGuidance() {
    assertThatThrownBy(() -> JDBC.createConnection("jdbc:turso:http://127.0.0.1:18080", null))
        .isInstanceOf(SQLFeatureNotSupportedException.class)
        .hasMessageContaining("http://127.0.0.1:18080")
        .hasMessageContaining("sync_url");
  }

  @Test
  void authTokenWithoutSyncUrlIsRejected() {
    assertThatThrownBy(
            () -> JDBC.createConnection("jdbc:turso:/tmp/app.db?auth_token=secret", null))
        .isInstanceOf(SQLFeatureNotSupportedException.class)
        .hasMessageContaining("auth_token")
        .hasMessageContaining("sync_url");
  }

  @Test
  void memoryReplicaIsRejected() {
    assertThatThrownBy(
            () -> JDBC.createConnection("jdbc:turso::memory:?sync_url=http://host:1", null))
        .isInstanceOf(SQLFeatureNotSupportedException.class)
        .hasMessageContaining("local file path");
  }

  @Test
  void syncUrlWithoutLocalPathIsRejected() {
    assertThatThrownBy(() -> JDBC.createConnection("jdbc:turso:?sync_url=http://host:1", null))
        .isInstanceOf(SQLFeatureNotSupportedException.class)
        .hasMessageContaining("local database path");
  }
}
