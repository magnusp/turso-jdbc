package io.github.magnusp.turso.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import io.github.magnusp.turso.utils.Logger;
import io.github.magnusp.turso.utils.LoggerFactory;

/** A Turso connection backed by the FFM core. */
public final class TursoConnection {

  private static final Logger logger = LoggerFactory.getLogger(TursoConnection.class);

  private final String url;
  private long connectionPtr;
  private final TursoDB database;
  private final boolean ownsDatabase;

  private boolean autoCommit = true;
  private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
  private boolean inTransaction = false;
  private final Object transactionLock = new Object();
  private long totalChanges;

  public TursoConnection(String url, String filePath) throws SQLException {
    this(url, filePath, new Properties());
  }

  public TursoConnection(String url, String filePath, Properties properties) throws SQLException {
    this.url = url;
    this.database = TursoDB.create(url, filePath);
    this.ownsDatabase = true;
    this.connectionPtr = this.database.connect();
  }

  public TursoConnection(String url, TursoDB database) throws SQLException {
    this.url = url;
    this.database = database;
    this.ownsDatabase = false;
    this.connectionPtr = this.database.connect();
  }

  public void checkOpen() throws SQLException {
    if (isClosed()) {
      throw new SQLException("database connection closed");
    }
  }

  public String getUrl() {
    return url;
  }

  public void close() throws SQLException {
    if (isClosed()) {
      return;
    }

    synchronized (transactionLock) {
      if (inTransaction) {
        try {
          executeInternal("ROLLBACK");
        } catch (SQLException e) {
          logger.warn("Failed to rollback transaction during close", e);
        } finally {
          inTransaction = false;
        }
      }
    }

    synchronized (this) {
      if (isClosed()) {
        return;
      }
      long pointer = connectionPtr;
      connectionPtr = 0;
      TursoNative.deinitConnection(pointer);
      if (ownsDatabase) {
        database.close();
      }
    }
  }

  public synchronized boolean isClosed() {
    return connectionPtr == 0;
  }

  public TursoDB getDatabase() {
    return database;
  }

  public TursoStatement prepare(String sql) throws SQLException {
    return prepare(sql, true);
  }

  private TursoStatement prepare(String sql, boolean checkTransaction) throws SQLException {
    logger.trace("DriverManager [{}] [Turso EXEC] {}", Thread.currentThread().getName(), sql);

    if (checkTransaction) {
      ensureTransactionStarted(sql);
    }

    synchronized (this) {
      checkOpen();
      long statement = TursoNative.prepare(connectionPtr, sql);
      return new TursoStatement(sql, statement, this);
    }
  }

  public void checkCursor(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
      throw new SQLException("Turso only supports TYPE_FORWARD_ONLY cursors");
    }
    if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
      throw new SQLException("Turso only supports CONCUR_READ_ONLY cursors");
    }
    if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
      throw new SQLException("Turso only supports closing cursors at commit");
    }
  }

  public void setAutoCommit(boolean autoCommit) throws SQLException {
    synchronized (transactionLock) {
      checkOpen();
      if (this.autoCommit == autoCommit) {
        return;
      }
      if (autoCommit && inTransaction) {
        commit();
      }
      this.autoCommit = autoCommit;
    }
  }

  public boolean getAutoCommit() throws SQLException {
    checkOpen();
    return autoCommit;
  }

  public void commit() throws SQLException {
    synchronized (transactionLock) {
      checkOpen();
      if (autoCommit) {
        throw new SQLException("Cannot commit in autocommit mode.");
      }
      if (inTransaction) {
        executeInternal("COMMIT");
        inTransaction = false;
      }
    }
  }

  public void rollback() throws SQLException {
    synchronized (transactionLock) {
      checkOpen();
      if (autoCommit) {
        throw new SQLException("Cannot rollback in autocommit mode.");
      }
      if (inTransaction) {
        executeInternal("ROLLBACK");
        inTransaction = false;
      }
    }
  }

  public void setTransactionIsolation(int level) throws SQLException {
    synchronized (transactionLock) {
      checkOpen();
      if (inTransaction) {
        throw new SQLException("Cannot change isolation level while transaction is active.");
      }
      if (level != Connection.TRANSACTION_READ_UNCOMMITTED
          && level != Connection.TRANSACTION_READ_COMMITTED
          && level != Connection.TRANSACTION_REPEATABLE_READ
          && level != Connection.TRANSACTION_SERIALIZABLE) {
        throw new SQLException("Invalid transaction isolation level: " + level);
      }
      this.transactionIsolation = level;
    }
  }

  public int getTransactionIsolation() throws SQLException {
    checkOpen();
    return transactionIsolation;
  }

  public long getLastInsertRowid() throws SQLException {
    checkOpen();
    return TursoNative.lastInsertRowid(connectionPtr);
  }

  public synchronized void addChanges(long changes) {
    if (changes > 0) {
      totalChanges += changes;
    }
  }

  public synchronized long getTotalChanges() {
    return totalChanges;
  }

  public void setBusyTimeout(long millis) throws SQLException {
    checkOpen();
    TursoNative.setBusyTimeout(connectionPtr, millis);
  }

  private void ensureTransactionStarted(String sql) throws SQLException {
    if (autoCommit || inTransaction) {
      return;
    }
    String trimmed = sql.trim().toUpperCase(java.util.Locale.ROOT);
    if (trimmed.startsWith("BEGIN")
        || trimmed.startsWith("COMMIT")
        || trimmed.startsWith("ROLLBACK")) {
      return;
    }
    String beginMode = TursoTransactionMode.fromIsolationLevel(transactionIsolation).getSql();
    executeInternal(beginMode);
    inTransaction = true;
  }

  private void executeInternal(String sql) throws SQLException {
    try (TursoStatement statement = prepare(sql, false)) {
      statement.getResultSet().consumeAll();
    }
  }
}
