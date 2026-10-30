package io.github.magnusp.turso.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A broken sync remote must name the URL instead of looking like a missing local file. */
class SyncFailureIT {

  @TempDir Path dir;

  @Test
  void unreachableServerNamesTheUrl() throws Exception {
    // Port 1 is reserved and never bound by the test client.
    int port = 1;
    Path local = dir.resolve("unreachable.db");
    String remote = "http://127.0.0.1:" + port;
    String url = "jdbc:turso:" + local.toAbsolutePath() + "?sync_url=" + remote;

    Throwable thrown = catchThrowable(() -> DriverManager.getConnection(url));

    assertThat(thrown).isInstanceOf(SQLTransientConnectionException.class);
    assertThat(thrown.getMessage())
        .contains("Turso sync open failed")
        .contains(local.toAbsolutePath().toString())
        .contains(remote)
        .doesNotContain("entity not found");
  }

  @Test
  void remoteWithoutTheSyncProtocolFailsInsteadOfDiverging() throws Exception {
    Path local = dir.resolve("wrong-endpoint.db");
    String remote = SyncServer.get().url() + "/not-the-sync-api";
    String url = "jdbc:turso:" + local.toAbsolutePath() + "?sync_url=" + remote;

    Throwable thrown = catchThrowable(() -> DriverManager.getConnection(url));

    assertThat(thrown).isInstanceOf(SQLException.class);
    assertThat(thrown.getMessage()).contains(local.toAbsolutePath().toString()).contains(remote);
  }
}
