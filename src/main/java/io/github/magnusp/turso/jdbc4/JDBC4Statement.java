package io.github.magnusp.turso.jdbc4;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.annotations.SkipNullableCheck;
import io.github.magnusp.turso.core.TursoConnection;
import io.github.magnusp.turso.core.TursoStatement;

/** JDBC 4 Statement implementation for Turso databases. */
public class JDBC4Statement implements Statement {

  // INSERT or REPLACE, optionally preceded by a WITH clause (common table expression).
  private static final Pattern INSERT_PATTERN =
      Pattern.compile(
          "^\\s*(?:with\\s+.+\\(.+?\\))*\\s*(?:insert|replace)\\s*",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  protected final JDBC4Connection connection;

  /** The underlying Turso statement. */
  @Nullable protected TursoStatement statement = null;

  /** The number of rows affected by the last update operation. */
  protected long updateCount;

  // Because JDBC4Statement has different life cycle in compared to tursoStatement, let's use this
  // field to manage JDBC4Statement lifecycle
  private boolean closed;
  private boolean closeOnCompletion;

  private final int resultSetType;
  private final int resultSetConcurrency;
  private final int resultSetHoldability;

  private int queryTimeoutSeconds;
  protected int maxRows;
  private int fetchSize;

  /** The result set produced by the most recent execution, if any. */
  @Nullable protected JDBC4ResultSet currentResultSet;

  /** Whether the most recent execution produced a result set. */
  protected boolean resultSetAvailable;

  /** Whether {@link #getResultSet()} already handed the current result set to the caller. */
  protected boolean resultSetReturned;

  private ReentrantLock connectionLock = new ReentrantLock();

  /**
   * List of SQL statements to be executed as a batch. Used for batch processing as per JDBC
   * specification.
   */
  private List<String> batchCommands = new ArrayList<>();

  /** Row ids produced by the last INSERT/REPLACE, one per executed row. */
  protected final List<Long> generatedKeys = new ArrayList<>();

  private boolean batchExecution;

  public JDBC4Statement(JDBC4Connection connection) {
    this(
        connection,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.CLOSE_CURSORS_AT_COMMIT);
  }

  public JDBC4Statement(
      JDBC4Connection connection,
      int resultSetType,
      int resultSetConcurrency,
      int resultSetHoldability) {
    this.connection = connection;
    this.resultSetType = resultSetType;
    this.resultSetConcurrency = resultSetConcurrency;
    this.resultSetHoldability = resultSetHoldability;
  }

  // TODO: should executeQuery run execute right after preparing the statement?
  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    ensureOpen();
    closePreviousStatement();
    statement =
        this.withConnectionTimeout(
            () -> {
              try {
                // TODO: if sql is a readOnly query, do we still need the locks?
                connectionLock.lock();
                TursoStatement prepared = prepareFirstStatement(sql);
                if (prepared.columnCount() == 0) {
                  // The reference driver steps the first statement before rejecting it, so DDL and
                  // DML side effects are visible even though executeQuery throws.
                  try {
                    prepared.getResultSet().consumeAll();
                  } finally {
                    prepared.close();
                  }
                  updateCount = -1;
                  currentResultSet = null;
                  resultSetAvailable = false;
                  resultSetReturned = false;
                  throw new SQLException("query does not return ResultSet", "HY000", 0);
                }
                return prepared;
              } finally {
                connectionLock.unlock();
              }
            });

    statement.getResultSet().setMaxRows(maxRows);
    currentResultSet = new JDBC4ResultSet(statement.getResultSet(), this);
    resultSetAvailable = true;
    resultSetReturned = true;
    updateCount = -1;
    return currentResultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    ensureOpen();
    if (!batchExecution) {
      generatedKeys.clear();
    }
    return this.withConnectionTimeout(
        () -> {
          try {
            connectionLock.lock();
            closePreviousStatement();

            long total = 0;
            String remaining = sql;
            while (true) {
              if (remaining.isBlank()) {
                break;
              }
              TursoConnection.ParsedStatement parsed =
                  connection.getTursoConnection().prepareFirst(remaining);
              TursoStatement current = parsed.statement();
              if (current == null) {
                break;
              }
              statement = current;
              try {
                current.initializeColumnMetadata();
                current.getResultSet().setMaxRows(maxRows);
                final long previousChanges = current.totalChanges();
                current.execute();
                current.getResultSet().consumeAll();
                updateGeneratedKeys();
                total += current.totalChanges() - previousChanges;
              } finally {
                closePreviousStatement();
              }
              if (parsed.tail() <= 0) {
                break;
              }
              remaining = remaining.substring(parsed.tail());
            }

            updateCount = total;
            currentResultSet = null;
            resultSetAvailable = false;
            resultSetReturned = false;
            return (int) total;
          } finally {
            connectionLock.unlock();
          }
        });
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }

    if (this.statement != null) {
      this.statement.close();
    }

    closed = true;
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    // TODO
    return 0;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    // TODO
  }

  @Override
  public int getMaxRows() throws SQLException {
    return maxRows;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    if (max < 0) {
      throw new SQLException("max rows must not be negative");
    }
    this.maxRows = max;
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    // TODO
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    // TODO
    return 0;
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    if (seconds < 0) {
      throw new SQLException("Query timeout must be greater than 0");
    }
    this.queryTimeoutSeconds = seconds;
  }

  @Override
  public void cancel() throws SQLException {
    // TODO
  }

  @Override
  @SkipNullableCheck
  public SQLWarning getWarnings() throws SQLException {
    // TODO
    return null;
  }

  @Override
  public void clearWarnings() throws SQLException {
    // TODO
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    // TODO
  }

  /**
   * The <code>execute</code> method executes an SQL statement and indicates the form of the first
   * result. You must then use the methods <code>getResultSet</code> or <code>getUpdateCount</code>
   * to retrieve the result, and <code>getMoreResults</code> to move to any subsequent result(s).
   */
  @Override
  public boolean execute(String sql) throws SQLException {
    ensureOpen();
    return this.withConnectionTimeout(
        () -> {
          try {
            // TODO: if sql is a readOnly query, do we still need the locks?
            connectionLock.lock();
            if (!batchExecution) {
              generatedKeys.clear();
            }
            closePreviousStatement();
            statement = prepareFirstStatement(sql);
            statement.getResultSet().setMaxRows(maxRows);
            final long previousChanges = statement.totalChanges();
            final boolean result = statement.execute();
            if (result) {
              currentResultSet = new JDBC4ResultSet(statement.getResultSet(), this);
              resultSetAvailable = true;
              resultSetReturned = false;
              updateCount = -1;
            } else {
              statement.getResultSet().consumeAll();
              updateGeneratedKeys();
              updateCount = statement.totalChanges() - previousChanges;
              resultSetAvailable = false;
              resultSetReturned = false;
              currentResultSet = null;
            }

            return result;
          } finally {
            connectionLock.unlock();
          }
        });
  }

  @Override
  @SkipNullableCheck
  public ResultSet getResultSet() throws SQLException {
    ensureOpen();
    if (!resultSetAvailable) {
      return null;
    }
    if (resultSetReturned) {
      throw new SQLException("No result set available");
    }
    resultSetReturned = true;
    return currentResultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return (int) updateCount;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction != ResultSet.FETCH_FORWARD
        && direction != ResultSet.FETCH_REVERSE
        && direction != ResultSet.FETCH_UNKNOWN) {
      throw new SQLException("Invalid fetch direction: " + direction);
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    if (rows < 0) {
      throw new SQLException("Fetch size must not be negative");
    }
    this.fetchSize = rows;
  }

  @Override
  public int getFetchSize() throws SQLException {
    return fetchSize;
  }

  @Override
  public int getResultSetConcurrency() {
    return resultSetConcurrency;
  }

  @Override
  public int getResultSetType() {
    return resultSetType;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    ensureOpen();
    if (sql == null) {
      throw new SQLException("SQL command cannot be null");
    }
    batchCommands.add(sql);
  }

  @Override
  public void clearBatch() throws SQLException {
    ensureOpen();
    batchCommands.clear();
  }

  // TODO: let's make this batch operation atomic
  @Override
  public int[] executeBatch() throws SQLException {
    ensureOpen();

    int[] updateCounts = new int[batchCommands.size()];
    batchExecution = true;
    generatedKeys.clear();

    // Execute each command in the batch
    for (int i = 0; i < batchCommands.size(); i++) {
      String sql = batchCommands.get(i);
      try {
        boolean producedResultSet = execute(sql);
        if (producedResultSet) {
          closeCurrentResultSet();
          updateCounts[i] = EXECUTE_FAILED;
          clearBatch();
          batchExecution = false;
          throw new BatchUpdateException(
              "Batch entry "
                  + i
                  + " ("
                  + sql
                  + ") was aborted. "
                  + "Batch commands cannot return result sets.",
              "HY000", // General error SQL state
              0,
              updateCounts);
        }
        updateCounts[i] = getUpdateCount();
      } catch (BatchUpdateException e) {
        throw e;
      } catch (SQLException e) {
        updateCounts[i] = EXECUTE_FAILED;

        // Create a BatchUpdateException with the partial results
        BatchUpdateException bue =
            new BatchUpdateException(
                "Batch entry " + i + " (" + sql + ") failed: " + e.getMessage(),
                e.getSQLState(),
                e.getErrorCode(),
                updateCounts,
                e.getCause());
        // Clear the batch after failure
        clearBatch();
        batchExecution = false;
        throw bue;
      }
    }

    // Clear the batch after successful execution
    clearBatch();
    batchExecution = false;
    return updateCounts;
  }

  private void closeCurrentResultSet() throws SQLException {
    if (statement != null) {
      statement.getResultSet().close();
    }
    currentResultSet = null;
    resultSetAvailable = false;
    resultSetReturned = false;
  }

  /**
   * Closes the native statement of the previous execution. Keeping it open would keep a read
   * snapshot alive on this connection.
   */
  private void closePreviousStatement() throws SQLException {
    if (statement != null) {
      statement.close();
      statement = null;
    }
  }

  /**
   * Prepares exactly one statement. A second statement, or input with no statement at all, is
   * rejected before anything runs.
   */
  private TursoStatement prepareFirstStatement(String sql) throws SQLException {
    TursoConnection.ParsedStatement parsed = connection.getTursoConnection().prepareFirst(sql);
    TursoStatement first = parsed.statement();
    if (first == null) {
      throw new SQLException("The supplied SQL string contains no statements", "42000");
    }
    try {
      if (connection.getTursoConnection().hasStatementAfter(sql, parsed.tail())) {
        throw new SQLException("You can only execute one statement at a time", "42000");
      }
      first.initializeColumnMetadata();
      return first;
    } catch (SQLException e) {
      try {
        first.close();
      } catch (SQLException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(Statement.CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    if (current == Statement.KEEP_CURRENT_RESULT || current == Statement.CLOSE_ALL_RESULTS) {
      throw new SQLFeatureNotSupportedException(
          "Argument not supported: Statement.KEEP_CURRENT_RESULT or Statement.CLOSE_ALL_RESULTS");
    }
    if (current != Statement.CLOSE_CURRENT_RESULT) {
      throw new SQLException("Invalid argument");
    }

    if (statement != null) {
      statement.getResultSet().close();
    }
    currentResultSet = null;
    resultSetAvailable = false;
    resultSetReturned = false;
    updateCount = -1;

    return false;
  }

  @Override
  @SkipNullableCheck
  public ResultSet getGeneratedKeys() throws SQLException {
    ensureOpen();
    StringBuilder sql = new StringBuilder();
    if (generatedKeys.isEmpty()) {
      sql.append("SELECT 1 AS GENERATED_KEY WHERE 1=0");
    } else {
      for (int i = 0; i < generatedKeys.size(); i++) {
        if (i > 0) {
          sql.append(" UNION ALL ");
        }
        sql.append("SELECT ").append(generatedKeys.get(i)).append(" AS GENERATED_KEY");
      }
    }
    Statement keys = connection.createStatement();
    return keys.executeQuery(sql.toString());
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    // TODO: enhance
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    // TODO: enhance
    return executeUpdate(sql);
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    // TODO: enhance
    return executeUpdate(sql);
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    // TODO: enhance
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    // TODO: enhance
    return execute(sql);
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    // TODO
    return false;
  }

  @Override
  public int getResultSetHoldability() {
    return resultSetHoldability;
  }

  @Override
  public boolean isClosed() throws SQLException {
    return this.closed;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    // TODO
  }

  @Override
  public boolean isPoolable() throws SQLException {
    // TODO
    return false;
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    if (closed) {
      throw new SQLException("statement is closed");
    }
    closeOnCompletion = true;
  }

  /**
   * Indicates whether the statement should be closed automatically when all its dependent result
   * sets are closed.
   */
  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    if (closed) {
      throw new SQLException("statement is closed");
    }
    return closeOnCompletion;
  }

  @Override
  @SkipNullableCheck
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  protected void updateGeneratedKeys() throws SQLException {
    if (statement == null) {
      return;
    }
    String sql = statement.getSql();
    if (sql == null) {
      return;
    }
    if (INSERT_PATTERN.matcher(sql).find()) {
      generatedKeys.add(connection.getLastInsertRowid());
    }
  }

  public String getSql() {
    return statement == null ? null : statement.getSql();
  }

  private <T> T withConnectionTimeout(SQLCallable<T> callable) throws SQLException {
    final int originalBusyTimeoutMillis = connection.getBusyTimeout();
    if (queryTimeoutSeconds > 0) {
      // TODO: set busy timeout
      connection.setBusyTimeout(1000 * queryTimeoutSeconds);
    }

    try {
      return callable.call();
    } finally {
      if (queryTimeoutSeconds > 0) {
        connection.setBusyTimeout(originalBusyTimeoutMillis);
      }
    }
  }

  /**
   * Functional interface for SQL callable operations.
   *
   * @param <T> the return type
   */
  @FunctionalInterface
  protected interface SQLCallable<T> {
    /**
     * Executes the SQL operation.
     *
     * @return the result of the operation
     * @throws SQLException if a database access error occurs
     */
    T call() throws SQLException;
  }

  protected void ensureOpen() throws SQLException {
    if (closed) {
      throw new SQLException("Statement is closed");
    }
  }
}
