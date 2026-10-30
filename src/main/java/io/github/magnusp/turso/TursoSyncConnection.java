package io.github.magnusp.turso;

import java.sql.SQLException;

/**
 * Sync operations for an embedded replica.
 *
 * <p>A replica is a local database file plus a remote URL. Reads and writes are local. {@link
 * #push()} sends local changes to the remote, and {@link #pull()} fetches remote changes and
 * rebases local changes on top of them. Nothing is sent implicitly by {@code commit()} or {@code
 * close()}.
 *
 * <pre>{@code
 * try (Connection connection =
 *     DriverManager.getConnection("jdbc:turso:/var/lib/app.db?sync_url=http://localhost:18080")) {
 *   TursoSyncConnection sync = connection.unwrap(TursoSyncConnection.class);
 *   sync.push();
 *   sync.pull();
 * }
 * }</pre>
 *
 * <p>Obtain this interface with {@link java.sql.Connection#unwrap(Class)}. A connection created
 * without {@code sync_url} does not implement it.
 */
public interface TursoSyncConnection {

  /** Sends local changes to the remote. Does not fetch remote changes. */
  void push() throws SQLException;

  /**
   * Fetches remote changes and applies them locally, rebasing any local changes on top.
   *
   * @return true if the remote had changes to apply
   */
  boolean pull() throws SQLException;

  /** Runs {@link #pull()} and then {@link #push()}. */
  void sync() throws SQLException;

  /** Returns the sync engine statistics. */
  TursoSyncStats stats() throws SQLException;

  /** Checkpoints the local WAL without changing the remote revision. */
  void checkpoint() throws SQLException;

  /** Returns the remote URL this replica syncs with. */
  String getSyncUrl();

  /** Returns the local database path this replica uses. */
  String getLocalPath();
}
