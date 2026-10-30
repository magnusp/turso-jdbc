package io.github.magnusp.turso.jdbc4;

import io.github.magnusp.turso.TursoSyncConnection;
import io.github.magnusp.turso.TursoSyncStats;
import io.github.magnusp.turso.core.TursoConnection;
import io.github.magnusp.turso.core.TursoSyncDatabase;
import java.sql.SQLException;
import java.util.Properties;

/** A JDBC connection backed by an embedded replica. */
public final class JDBC4SyncConnection extends JDBC4Connection implements TursoSyncConnection {

  public JDBC4SyncConnection(String url, String filePath, String syncUrl, Properties properties)
      throws SQLException {
    super(new TursoConnection(url, filePath, syncUrl, properties));
  }

  private TursoSyncDatabase syncDatabase() {
    return getTursoConnection().getSyncDatabase();
  }

  @Override
  public void push() throws SQLException {
    syncDatabase().push();
  }

  @Override
  public boolean pull() throws SQLException {
    return syncDatabase().pull();
  }

  @Override
  public void sync() throws SQLException {
    syncDatabase().sync();
  }

  @Override
  public TursoSyncStats stats() throws SQLException {
    return syncDatabase().stats();
  }

  @Override
  public void checkpoint() throws SQLException {
    syncDatabase().checkpoint();
  }

  @Override
  public String getSyncUrl() {
    return syncDatabase().remoteUrl();
  }

  @Override
  public String getLocalPath() {
    return syncDatabase().localPath();
  }
}
