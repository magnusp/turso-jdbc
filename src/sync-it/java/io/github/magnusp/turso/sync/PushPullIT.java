package io.github.magnusp.turso.sync;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.magnusp.turso.TursoSyncConnection;
import io.github.magnusp.turso.TursoSyncStats;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Push, pull, two-replica exchange, statistics, and checkpoint behavior. */
class PushPullIT {

  @TempDir Path dir;

  @Test
  void pushSendsLocalRowsToTheServer() throws Exception {
    String table = "push_rows";
    try (Connection connection = Replicas.connect(dir.resolve("push.db"))) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      assertThat(sync.getLocalPath())
          .isEqualTo(dir.resolve("push.db").toAbsolutePath().toString());
      assertThat(sync.getSyncUrl()).isEqualTo(SyncServer.get().url());
      Replicas.recreateTable(connection, table);
      Replicas.exec(connection, "insert into " + table + " values ('from-jdbc')");

      sync.push();

      assertThat(Replicas.serverStrings("select v from " + table + " order by v"))
          .containsExactly("from-jdbc");
      TursoSyncStats stats = sync.stats();
      assertThat(stats.lastPushUnixTime()).isPositive();
      assertThat(stats.lastPullUnixTime()).isPositive();
      assertThat(stats.cdcOperations()).isZero();
    }
  }

  @Test
  void pullBootstrapsADeletedReplica() throws Exception {
    String table = "pull_rows";
    Path local = dir.resolve("pull.db");
    try (Connection connection = Replicas.connect(local)) {
      Replicas.recreateTable(connection, table);
      Replicas.exec(connection, "insert into " + table + " values ('remote')");
      connection.unwrap(TursoSyncConnection.class).push();
    }

    Replicas.deleteReplica(local);

    // Bootstrap is off so the row arrives through the explicit pull, not through create().
    String url = Replicas.url(local) + "&sync_bootstrap=off";
    try (Connection connection = DriverManager.getConnection(url)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      assertThat(
              Replicas.intValue(
                  connection,
                  "select count(*) from sqlite_master where type='table' and name='" + table + "'"))
          .isZero();

      assertThat(sync.pull()).isTrue();

      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("remote");
    }
  }

  @Test
  void twoReplicasExchangeChanges() throws Exception {
    String table = "two_replica_rows";
    Path first = dir.resolve("first.db");
    Path second = dir.resolve("second.db");

    try (Connection connection = Replicas.connect(first)) {
      Replicas.recreateTable(connection, table);
      Replicas.exec(connection, "insert into " + table + " values ('a')");
      connection.unwrap(TursoSyncConnection.class).push();
    }

    try (Connection connection = Replicas.connect(second)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      sync.pull();
      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("a");

      Replicas.exec(connection, "insert into " + table + " values ('b')");
      sync.push();
    }

    try (Connection connection = Replicas.connect(first)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      assertThat(sync.pull()).isTrue();
      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("a", "b");
    }
  }

  @Test
  void checkpointKeepsLocalAndRemoteConsistent() throws Exception {
    String table = "checkpoint_rows";
    Path local = dir.resolve("checkpoint.db");
    try (Connection connection = Replicas.connect(local)) {
      TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
      Replicas.recreateTable(connection, table);
      Replicas.exec(connection, "insert into " + table + " values ('one')");
      sync.push();
      sync.checkpoint();

      Replicas.exec(connection, "insert into " + table + " values ('two')");
      sync.push();
      sync.checkpoint();

      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("one", "two");
      assertThat(Replicas.serverStrings("select v from " + table + " order by v"))
          .containsExactly("one", "two");
    }

    // The checkpointed replica still opens and reads after the connection closes.
    try (Connection connection = Replicas.connect(local)) {
      assertThat(Replicas.strings(connection, "select v from " + table + " order by v"))
          .containsExactly("one", "two");
    }
  }
}
