package io.github.magnusp.turso.core;

import java.sql.SQLException;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.utils.Logger;
import io.github.magnusp.turso.utils.LoggerFactory;

/** A prepared Turso statement backed by the FFM core. */
public final class TursoStatement implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TursoStatement.class);

  private final String sql;
  private final TursoConnection connection;
  private long statementPointer;
  private final TursoResultSet resultSet;
  private String[] columnDeclaredTypes = new String[0];

  public TursoStatement(String sql, long statementPointer, TursoConnection connection) {
    this.sql = SqlText.stripLeadingTrivia(sql);
    this.statementPointer = statementPointer;
    this.connection = connection;
    this.resultSet = TursoResultSet.of(this);
    log.debug("Creating statement with sql: {}", this.sql);
  }

  public TursoResultSet getResultSet() {
    return resultSet;
  }

  public boolean execute() throws SQLException {
    // Decide from the prepared statement metadata so the first row is left for the result set.
    return TursoNative.columnCount(openStatementPointer()) > 0;
  }

  synchronized TursoStepResult step() throws SQLException {
    long pointer = openStatementPointer();
    int status = TursoNative.step(pointer);
    if (status == TursoNative.ROW) {
      int columnCount = TursoNative.columnCount(pointer);
      Object[] values = TursoNative.row(pointer, columnCount);
      return TursoStepResult.row(values);
    }
    if (status == TursoNative.DONE) {
      connection.noteTransactionControl(sql);
      if (isDml(sql)) {
        connection.addChanges(TursoNative.changes(pointer));
      }
      return TursoStepResult.done();
    }
    throw new SQLException("Unexpected Turso step status: " + status);
  }

  public synchronized void close() throws SQLException {
    if (isClosed()) {
      return;
    }
    long pointer = statementPointer;
    statementPointer = 0;
    try {
      resultSet.close();
    } catch (SQLException e) {
      log.warn("Failed to close result set", e);
    }
    try {
      TursoNative.finalizeStatement(pointer);
    } catch (SQLException e) {
      log.warn("Failed to finalize statement", e);
    } finally {
      TursoNative.deinitStatement(pointer);
    }
  }

  public synchronized void initializeColumnMetadata() throws SQLException {
    long pointer = openStatementPointer();
    String[] columnNames = TursoNative.columnNames(pointer);
    String[] declaredTypes = TursoNative.columnDeclaredTypes(pointer);
    this.columnDeclaredTypes = declaredTypes;
    this.resultSet.setColumnNames(columnNames);
    this.resultSet.setColumnDeclaredTypes(declaredTypes);
  }

  public String[] getColumnDeclaredTypes() {
    return columnDeclaredTypes;
  }

  public String getSql() {
    return sql;
  }

  public synchronized int bindNull(int position) throws SQLException {
    TursoNative.bindNull(openStatementPointer(), position);
    return 0;
  }

  public int bindInt(int position, int value) throws SQLException {
    return bindLong(position, value);
  }

  public synchronized int bindLong(int position, long value) throws SQLException {
    TursoNative.bindLong(openStatementPointer(), position, value);
    return 0;
  }

  public synchronized int bindDouble(int position, double value) throws SQLException {
    TursoNative.bindDouble(openStatementPointer(), position, value);
    return 0;
  }

  public synchronized int bindText(int position, @Nullable String value) throws SQLException {
    TursoNative.bindText(openStatementPointer(), position, value == null ? "" : value);
    return 0;
  }

  public synchronized int bindBlob(int position, byte[] value) throws SQLException {
    TursoNative.bindBlob(openStatementPointer(), position, value);
    return 0;
  }

  public void bindObject(int parameterIndex, Object x) throws SQLException {
    if (x == null) {
      bindNull(parameterIndex);
    } else if (x instanceof Byte) {
      bindInt(parameterIndex, (Byte) x);
    } else if (x instanceof Short) {
      bindInt(parameterIndex, (Short) x);
    } else if (x instanceof Integer) {
      bindInt(parameterIndex, (Integer) x);
    } else if (x instanceof Long) {
      bindLong(parameterIndex, (Long) x);
    } else if (x instanceof String) {
      bindText(parameterIndex, (String) x);
    } else if (x instanceof Float) {
      bindDouble(parameterIndex, (Float) x);
    } else if (x instanceof Double) {
      bindDouble(parameterIndex, (Double) x);
    } else if (x instanceof byte[]) {
      bindBlob(parameterIndex, (byte[]) x);
    } else {
      throw new SQLException("Unsupported object type in bindObject: " + x.getClass().getName());
    }
  }

  public synchronized long totalChanges() throws SQLException {
    return connection.getTotalChanges();
  }

  public synchronized long changes() throws SQLException {
    return TursoNative.changes(openStatementPointer());
  }

  public synchronized int parameterCount() throws SQLException {
    return TursoNative.parameterCount(openStatementPointer());
  }

  /** Number of result columns, or zero when the statement does not produce rows. */
  public synchronized int columnCount() throws SQLException {
    return TursoNative.columnCount(openStatementPointer());
  }

  public long lastInsertRowid() throws SQLException {
    return connection.getLastInsertRowid();
  }

  public synchronized void reset() throws SQLException {
    long pointer = openStatementPointer();
    TursoNative.reset(pointer);
    this.resultSet.reset();
  }

  /** Limits the number of rows the next execution of this statement returns. */
  public void setMaxRows(long maxRows) {
    this.resultSet.setMaxRows(maxRows);
  }

  public synchronized boolean isClosed() {
    return statementPointer == 0;
  }

  private long openStatementPointer() throws SQLException {
    if (isClosed()) {
      throw new SQLException("statement is closed");
    }
    return statementPointer;
  }

  private static final java.util.regex.Pattern DML_PATTERN =
      java.util.regex.Pattern.compile(
          "^\\s*(?:WITH\\b.*\\)\\s*)?(?:INSERT|UPDATE|DELETE|REPLACE)\\b",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

  private static boolean isDml(String sql) {
    return sql != null && DML_PATTERN.matcher(sql).find();
  }

  @Override
  public String toString() {
    return "tursoStatement{sql='" + sql + "', closed=" + isClosed() + '}';
  }
}
