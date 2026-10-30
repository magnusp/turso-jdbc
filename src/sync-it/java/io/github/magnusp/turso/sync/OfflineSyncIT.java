package io.github.magnusp.turso.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.magnusp.turso.TursoSyncConnection;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local writes must survive a server outage and reach a second replica afterwards. */
class OfflineSyncIT {

  @TempDir Path dir;

  @Test
  void writesMadeWhileTheServerIsDownArePushedAfterRestart() throws Exception {
    SyncServer server = SyncServer.get();
    assumeTrue(server.isManaged(), "the offline test needs a harness-owned container");

    String table = "offline_rows";
    Path first = dir.resolve("offline-first.db");
    Path second = dir.resolve("offline-second.db");

    try (Connection connection = Replicas.connect(first)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      Replicas.recreateTable(connection, table);
      sync.push();

      server.stopService();
      Replicas.exec(connection, "insert into " + table + " values ('offline')");

      server.startService();
      server.awaitHealthy(Duration.ofMinutes(2));

      sync.push();
    }

    try (Connection connection = Replicas.connect(second)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      sync.pull();
      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("offline");
    }
  }
}
