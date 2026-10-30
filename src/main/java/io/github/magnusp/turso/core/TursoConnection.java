package io.github.magnusp.turso.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import io.github.magnusp.turso.TursoConfig;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.utils.Logger;
import io.github.magnusp.turso.utils.LoggerFactory;

/** A Turso connection backed by the FFM core. */
public final class TursoConnection {

  private static final Logger logger = LoggerFactory.getLogger(TursoConnection.class);

  private final String url;
  private long connectionPtr;
  private final @Nullable TursoDB database;
  private final @Nullable TursoSyncDatabase syncDatabase;
  private final boolean ownsDatabase;

  private boolean autoCommit = true;
  private int transactionIsolation = Connection.TRANSACTION_SERIALIZABLE;
  private volatile boolean inTransaction = false;
  private final Object transactionLock = new Object();
  private long totalChanges;

  public TursoConnection(String url, String filePath) throws SQLException {
    this(url, filePath, new Properties());
  }

  public TursoConnection(String url, String filePath, Properties properties) throws SQLException {
    this.url = url;
    TursoConfig config = new TursoConfig(properties);
    Boolean foreignKeys = config.foreignKeys();
    Boolean queryOnly = config.queryOnly();
    Long busyTimeout = config.busyTimeout();
    this.database = TursoDB.create(url, filePath);
    this.syncDatabase = null;
    this.ownsDatabase = true;
    this.connectionPtr = this.database.connect();
    try {
      applySettings(foreignKeys, queryOnly, busyTimeout);
    } catch (SQLException e) {
      close();
      throw e;
    }
  }

  /**
   * Opens an embedded replica: the local file at {@code filePath} plus the remote at {@code
   * syncUrl}.
   */
  public TursoConnection(String url, String filePath, String syncUrl, Properties properties)
      throws SQLException {
    this.url = url;
    TursoConfig config = new TursoConfig(properties);
    Boolean foreignKeys = config.foreignKeys();
    Boolean queryOnly = config.queryOnly();
    Long busyTimeout = config.busyTimeout();
    this.database = null;
    this.ownsDatabase = false;
    this.syncDatabase =
        TursoSyncDatabase.create(
            filePath,
            syncUrl,
            config.clientName(),
            config.longPollTimeoutMs(),
            config.bootstrapIfEmpty(),
            config.authToken());
    try {
      this.connectionPtr = this.syncDatabase.connect();
      applySettings(foreignKeys, queryOnly, busyTimeout);
    } catch (SQLException e) {
      long pointer = this.connectionPtr;
      this.connectionPtr = 0;
      if (pointer != 0) {
        TursoNative.deinitConnection(pointer);
      }
      this.syncDatabase.close();
      throw e;
    }
  }

  private void applySettings(
      @Nullable Boolean foreignKeys, @Nullable Boolean queryOnly, @Nullable Long busyTimeout)
      throws SQLException {
    if (busyTimeout != null) {
      setBusyTimeout(busyTimeout);
    }
    if (foreignKeys != null) {
      executeInternal("PRAGMA foreign_keys = " + (foreignKeys ? 1 : 0));
    }
    if (queryOnly != null) {
      executeInternal("PRAGMA query_only = " + (queryOnly ? 1 : 0));
    }
  }

  public TursoConnection(String url, TursoDB database) throws SQLException {
    this.url = url;
    this.database = database;
    this.syncDatabase = null;
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
      if (ownsDatabase && database != null) {
        database.close();
      }
      if (syncDatabase != null) {
        syncDatabase.close();
      }
    }
  }

  public synchronized boolean isClosed() {
    return connectionPtr == 0;
  }

  /** Returns the embedded database handle, or null for a sync connection. */
  @Nullable
  public TursoDB getDatabase() {
    return database;
  }

  /** Returns the embedded replica, or null for a local-only connection. */
  @Nullable
  public TursoSyncDatabase getSyncDatabase() {
    return syncDatabase;
  }

  /** Steps a statement, serving the sync IO queue when this is an embedded replica. */
  public int step(long statement) throws SQLException {
    TursoSyncDatabase sync = syncDatabase;
    return TursoNative.step(statement, sync == null ? null : sync::processOneIo);
  }

  public TursoStatement prepare(String sql) throws SQLException {
    return prepare(sql, true);
  }

  /** Prepares a statement without starting an implicit transaction. For connection pragmas. */
  public TursoStatement prepareWithoutTransaction(String sql) throws SQLException {
    return prepare(sql, false);
  }

  /**
   * Parses the first statement in {@code sql} and returns it with the char offset of the tail.
   * {@code statement} is null when nothing parseable remains.
   */
  public ParsedStatement prepareFirst(String sql) throws SQLException {
    return prepareFirst(sql, true);
  }

  /**
   * Parses the first statement in {@code sql} without starting an implicit transaction. Used to
   * inspect a tail while deciding whether the caller supplied more than one statement.
   */
  public ParsedStatement prepareFirstWithoutTransaction(String sql) throws SQLException {
    return prepareFirst(sql, false);
  }

  private ParsedStatement prepareFirst(String sql, boolean checkTransaction) throws SQLException {
    logger.trace("DriverManager [{}] [Turso EXEC] {}", Thread.currentThread().getName(), sql);
    if (checkTransaction) {
      ensureTransactionStarted(sql);
    }
    synchronized (this) {
      checkOpen();
      TursoNative.PreparedFirst prepared = TursoNative.prepareFirst(connectionPtr, sql);
      if (prepared.statement() == 0) {
        return new ParsedStatement(null, prepared.tail());
      }
      return new ParsedStatement(
          new TursoStatement(sql.substring(0, prepared.tail()), prepared.statement(), this),
          prepared.tail());
    }
  }

  /**
   * Reports whether another statement follows {@code tail} in {@code sql}.
   *
   * <p>Uses the prepare-first contract, so trailing whitespace, semicolons, and comments do not
   * count. The probe does not start an implicit transaction and finalizes any statement it
   * prepares. A tail that fails to prepare still names a second statement, so it counts.
   */
  public boolean hasStatementAfter(String sql, int tail) throws SQLException {
    if (tail < 0 || tail > sql.length()) {
      throw new IllegalArgumentException(
          "tail " + tail + " is outside a SQL string of " + sql.length());
    }
    if (tail == sql.length()) {
      return false;
    }
    ParsedStatement next;
    try {
      next = prepareFirstWithoutTransaction(sql.substring(tail));
    } catch (SQLException e) {
      // Preparing the tail can fail for reasons that only exist before the first statement runs,
      // such as a missing table that the first statement creates. It is still a second statement.
      return true;
    }
    TursoStatement statement = next.statement();
    if (statement == null) {
      return false;
    }
    statement.close();
    return true;
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

  /** Keeps the driver's transaction flag in step with SQL transaction control statements. */
  void noteTransactionControl(String sql) {
    String upper = SqlText.stripLeadingTrivia(sql).toUpperCase(java.util.Locale.ROOT);
    boolean begins = upper.startsWith("BEGIN") || upper.startsWith("START TRANSACTION");
    boolean ends =
        upper.startsWith("COMMIT")
            || upper.equals("END")
            || upper.startsWith("END ")
            || (upper.startsWith("ROLLBACK") && !upper.startsWith("ROLLBACK TO"));
    if (begins || ends) {
      synchronized (this) {
        inTransaction = begins;
      }
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
    if (autoCommit || inTransaction || !SqlText.hasContent(sql)) {
      return;
    }
    String trimmed = SqlText.stripLeadingTrivia(sql).toUpperCase(java.util.Locale.ROOT);
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

  /** One statement parsed from the head of a SQL string, plus the char offset of its tail. */
  public record ParsedStatement(@Nullable TursoStatement statement, int tail) {}
}
