package tech.turso.jdbc4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.turso.annotations.Nullable;
import tech.turso.annotations.SkipNullableCheck;
import tech.turso.core.TursoResultSet;

/** JDBC 4 ResultSet implementation for Turso databases. */
public final class JDBC4ResultSet implements ResultSet, ResultSetMetaData {

  private final TursoResultSet resultSet;
  @Nullable private final Statement statement;
  private boolean wasNull = false;

  private static final Pattern FROM_TABLE =
      Pattern.compile("(?is)\\bfrom\\s+([`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?)");

  private static final Pattern CREATE_TABLE =
      Pattern.compile(
          "(?is)\\bcreate\\s+(?:temp\\s+|temporary\\s+)?table\\s+"
              + "(?:if\\s+not\\s+exists\\s+)?([`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?)");

  private static final Pattern FROM_KEYWORD = Pattern.compile("(?is)\\bfrom\\b");

  private static final Pattern CAST_TYPE =
      Pattern.compile(
          "(?is)^\\s*cast\\s*\\(.*\\bas\\s+"
              + "([A-Za-z][A-Za-z0-9_ ]*(?:\\(\\s*\\d+\\s*(?:,\\s*\\d+\\s*)?\\))?)"
              + "\\s*\\)\\s*(?:as\\s+\\w+)?\\s*$");

  private String tableName;
  private boolean tableInfoLoaded;
  private final Map<String, ColumnInfo> columnInfo = new HashMap<>();
  private final Map<String, String> declaredTypesWithParams = new HashMap<>();

  /**
   * Creates a new JDBC4ResultSet.
   *
   * @param resultSet the underlying Turso result set
   * @param statement the statement that created this result set
   */
  public JDBC4ResultSet(TursoResultSet resultSet, @Nullable Statement statement) {
    this.resultSet = resultSet;
    this.statement = statement;
  }

  @Override
  public boolean next() throws SQLException {
    return resultSet.next();
  }

  @Override
  public void close() throws SQLException {
    resultSet.close();
  }

  @Override
  public boolean wasNull() throws SQLException {
    return wasNull;
  }

  @Override
  @Nullable
  public String getString(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result == null ? null : JdbcValueConversions.toStringValue(result);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result != null && JdbcValueConversions.toBoolean(result);
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> (byte) JdbcValueConversions.toLong(result));
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> (short) JdbcValueConversions.toLong(result));
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> (int) JdbcValueConversions.toLong(result));
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> JdbcValueConversions.toLong(result));
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> JdbcValueConversions.toFloat(result));
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return 0;
    }
    return wrapTypeConversion(() -> JdbcValueConversions.toDouble(result));
  }

  @Override
  @Nullable
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    BigDecimal value = wrapTypeConversion(() -> JdbcValueConversions.toBigDecimal(result));
    return value.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  @Nullable
  public byte[] getBytes(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result == null ? null : JdbcValueConversions.toBytes(result);
  }

  @Override
  @Nullable
  public Date getDate(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result == null ? null : JdbcValueConversions.toDate(result);
  }

  @Override
  @SkipNullableCheck
  public Time getTime(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result == null ? null : JdbcValueConversions.toTime(result);
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result == null ? null : JdbcValueConversions.toTimestamp(result);
  }

  @Override
  @SkipNullableCheck
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof String) {
            return new ByteArrayInputStream(((String) result).getBytes("US-ASCII"));
          } else if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to ASCII stream: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof String) {
            return new ByteArrayInputStream(((String) result).getBytes("UTF-8"));
          } else if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to Unicode stream: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) result);
          }
          throw new SQLException("Cannot convert to binary stream: " + result.getClass());
        });
  }

  @Override
  @Nullable
  public String getString(String columnLabel) throws SQLException {
    return getString(findColumn(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  @Nullable
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  @Nullable
  public Date getDate(String columnLabel) throws SQLException {
    final Object result = resultSet.get(columnLabel);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(
        () -> {
          if (result instanceof byte[]) {
            byte[] bytes = (byte[]) result;
            if (bytes.length == Long.BYTES) {
              long time = ByteBuffer.wrap(bytes).getLong();
              return new Date(time);
            }
          }
          // Try to parse as string if it's stored as TEXT
          if (result instanceof String) {
            return Date.valueOf((String) result);
          }
          throw new SQLException("Cannot convert value to Date: " + result.getClass());
        });
  }

  @Override
  @SkipNullableCheck
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return getAsciiStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return getUnicodeStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return getBinaryStream(findColumn(columnLabel));
  }

  @Override
  @SkipNullableCheck
  public SQLWarning getWarnings() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void clearWarnings() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getCursorName() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return this;
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    return result;
  }

  @Override
  @SkipNullableCheck
  public Object getObject(String columnLabel) throws SQLException {
    return getObject(findColumn(columnLabel));
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    if (columnLabel == null || columnLabel.isEmpty()) {
      throw new SQLException("column name not found");
    }

    final String[] columnNames = resultSet.getColumnNames();
    for (int i = 0; i < columnNames.length; i++) {
      if (columnNames[i].equalsIgnoreCase(columnLabel)) {
        return i + 1;
      }
    }
    throw new SQLException("column name " + columnLabel + " not found");
  }

  @Override
  @SkipNullableCheck
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    return wrapTypeConversion(() -> new StringReader((String) result));
  }

  @Override
  @Nullable
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn(columnLabel));
  }

  @Override
  @Nullable
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    final Object result = resultSet.get(columnIndex);
    wasNull = result == null;
    if (result == null) {
      return null;
    }
    try {
      return JdbcValueConversions.toBigDecimal(result);
    } catch (NumberFormatException e) {
      throw new SQLException("Bad value for type BigDecimal: " + result, e);
    }
  }

  @Override
  @SkipNullableCheck
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    // Empty ResultSet should return false per JDBC spec
    if (resultSet.isEmpty()) {
      return false;
    }
    return resultSet.isOpen() && resultSet.getRow() == 0 && !resultSet.isPastLastRow();
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    return resultSet.isOpen() && resultSet.isPastLastRow();
  }

  @Override
  public boolean isFirst() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isLast() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void beforeFirst() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void afterLast() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean first() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean last() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getRow() throws SQLException {
    return resultSet.getRow();
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean previous() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getFetchDirection() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getFetchSize() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getType() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getConcurrency() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowInserted() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void insertRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void deleteRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void refreshRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @Nullable
  public Statement getStatement() throws SQLException {
    return statement;
  }

  @Override
  @SkipNullableCheck
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Ref getRef(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Blob getBlob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Clob getClob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Array getArray(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Ref getRef(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Blob getBlob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Clob getClob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Array getArray(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @Nullable
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    final Date date = getDate(columnIndex);
    if (date == null || cal == null) {
      return date;
    }
    return new Date(date.getTime() + calculateTimezoneOffset(date.getTime(), cal));
  }

  @Override
  @Nullable
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return getDate(findColumn(columnLabel), cal);
  }

  @Override
  @Nullable
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    final Time time = getTime(columnIndex);
    if (time == null || cal == null) {
      return time;
    }
    return new Time(time.getTime() + calculateTimezoneOffset(time.getTime(), cal));
  }

  @Override
  @SkipNullableCheck
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return getTime(findColumn(columnLabel), cal);
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    final Timestamp timestamp = getTimestamp(columnIndex);
    if (timestamp == null || cal == null) {
      return timestamp;
    }
    return new Timestamp(timestamp.getTime() + calculateTimezoneOffset(timestamp.getTime(), cal));
  }

  @Override
  @SkipNullableCheck
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return getTimestamp(findColumn(columnLabel), cal);
  }

  @Override
  @SkipNullableCheck
  public URL getURL(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public URL getURL(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public RowId getRowId(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public RowId getRowId(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getHoldability() throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isClosed() throws SQLException {
    return !resultSet.isOpen();
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public NClob getNClob(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public NClob getNClob(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  @SkipNullableCheck
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    Object value = resultSet.get(columnIndex);
    wasNull = value == null;
    if (value == null) {
      return null;
    }
    if (type == Object.class) {
      return type.cast(value);
    }
    if (type == String.class) {
      return type.cast(JdbcValueConversions.toStringValue(value));
    }
    if (type == Boolean.class) {
      return type.cast(JdbcValueConversions.toBoolean(value));
    }
    if (type == byte[].class) {
      return type.cast(JdbcValueConversions.toBytes(value));
    }
    if (type == Date.class) {
      return type.cast(JdbcValueConversions.toDate(value));
    }
    if (type == Time.class) {
      return type.cast(JdbcValueConversions.toTime(value));
    }
    if (type == Timestamp.class) {
      return type.cast(JdbcValueConversions.toTimestamp(value));
    }
    if (type == LocalDate.class) {
      return type.cast(JdbcValueConversions.toLocalDate(value));
    }
    if (type == LocalTime.class) {
      return type.cast(JdbcValueConversions.toLocalTime(value));
    }
    if (type == LocalDateTime.class) {
      return type.cast(JdbcValueConversions.toLocalDateTime(value));
    }
    try {
      if (type == Byte.class) {
        return type.cast((byte) JdbcValueConversions.toLong(value));
      }
      if (type == Short.class) {
        return type.cast((short) JdbcValueConversions.toLong(value));
      }
      if (type == Integer.class) {
        return type.cast((int) JdbcValueConversions.toLong(value));
      }
      if (type == Long.class) {
        return type.cast(JdbcValueConversions.toLong(value));
      }
      if (type == Float.class) {
        return type.cast(JdbcValueConversions.toFloat(value));
      }
      if (type == Double.class) {
        return type.cast(JdbcValueConversions.toDouble(value));
      }
      if (type == BigDecimal.class) {
        return type.cast(JdbcValueConversions.toBigDecimal(value));
      }
    } catch (NumberFormatException e) {
      throw new SQLException("Bad value for type " + type.getSimpleName() + ": " + value, e);
    }
    throw new SQLException("Unsupported conversion to " + type.getName());
  }

  @Override
  @SkipNullableCheck
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  @SkipNullableCheck
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public int getColumnCount() throws SQLException {
    return this.resultSet.getColumnNames().length;
  }

  @Override
  public boolean isAutoIncrement(int column) throws SQLException {
    ColumnInfo info = columnInfo(column);
    return info != null
        && info.primaryKey()
        && info.type().toUpperCase(Locale.ROOT).contains("INT");
  }

  @Override
  public boolean isCaseSensitive(int column) throws SQLException {
    return TursoTypeMapping.isCaseSensitive(getColumnType(column));
  }

  @Override
  public boolean isSearchable(int column) throws SQLException {
    return true;
  }

  @Override
  public boolean isCurrency(int column) throws SQLException {
    return false;
  }

  @Override
  public int isNullable(int column) throws SQLException {
    ColumnInfo info = columnInfo(column);
    if (info == null) {
      return ResultSetMetaData.columnNullableUnknown;
    }
    return info.notNull()
        ? ResultSetMetaData.columnNoNulls
        : ResultSetMetaData.columnNullable;
  }

  @Override
  public boolean isSigned(int column) throws SQLException {
    return TursoTypeMapping.isSigned(getColumnType(column));
  }

  @Override
  public int getColumnDisplaySize(int column) throws SQLException {
    return switch (getColumnType(column)) {
      case java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.SMALLINT,
              java.sql.Types.TINYINT -> 20;
      case java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.LONGVARCHAR -> 255;
      case java.sql.Types.REAL, java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> 24;
      case java.sql.Types.BLOB, java.sql.Types.BINARY, java.sql.Types.VARBINARY,
              java.sql.Types.LONGVARBINARY -> Integer.MAX_VALUE;
      default -> 0;
    };
  }

  @Override
  public String getColumnLabel(int column) throws SQLException {
    return getColumnName(column);
  }

  @Override
  public String getColumnName(int column) throws SQLException {
    if (column > 0 && column <= resultSet.getColumnNames().length) {
      return resultSet.getColumnNames()[column - 1];
    }
    throw new SQLException("Index out of bound: " + column);
  }

  @Override
  public String getSchemaName(int column) throws SQLException {
    return "";
  }

  @Override
  public int getPrecision(int column) throws SQLException {
    return TursoTypeMapping.precision(declaredType(column));
  }

  @Override
  public int getScale(int column) throws SQLException {
    return TursoTypeMapping.scale(declaredType(column));
  }

  @Override
  public String getTableName(int column) throws SQLException {
    getColumnName(column);
    if (!isDirectColumn(column)) {
      return "";
    }
    loadTableInfo();
    return tableName == null ? "" : tableName;
  }

  @Override
  public String getCatalogName(int column) throws SQLException {
    return "";
  }

  @Override
  public int getColumnType(int column) throws SQLException {
    String declared = declaredType(column);
    if (declared != null) {
      return TursoTypeMapping.columnType(declared);
    }
    Object value;
    try {
      value = resultSet.get(column);
    } catch (SQLException e) {
      return Types.OTHER;
    }
    if (value == null) {
      return Types.OTHER;
    }
    if (value instanceof Long longValue) {
      return longValue > Integer.MAX_VALUE || longValue < Integer.MIN_VALUE
          ? Types.BIGINT
          : Types.INTEGER;
    }
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      return Types.INTEGER;
    }
    if (value instanceof Double || value instanceof Float) {
      return Types.REAL;
    }
    if (value instanceof String) {
      return Types.VARCHAR;
    }
    if (value instanceof byte[]) {
      return Types.BLOB;
    }
    return Types.OTHER;
  }

  @Override
  public String getColumnTypeName(int column) throws SQLException {
    String type = TursoTypeMapping.typeName(declaredType(column));
    if (type != null) {
      return type;
    }
    return switch (getColumnType(column)) {
      case Types.INTEGER -> "INTEGER";
      case Types.REAL -> "REAL";
      case Types.VARCHAR -> "TEXT";
      case Types.BLOB -> "BLOB";
      default -> "NUMERIC";
    };
  }

  @Override
  public boolean isReadOnly(int column) throws SQLException {
    return true;
  }

  @Override
  public boolean isWritable(int column) throws SQLException {
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) throws SQLException {
    return false;
  }

  @Override
  public String getColumnClassName(int column) throws SQLException {
    return TursoTypeMapping.className(getColumnType(column));
  }

  private String declaredType(int column) throws SQLException {
    String columnName = getColumnName(column);
    loadTableInfo();
    String fullType = declaredTypesWithParams.get(columnName.toLowerCase(Locale.ROOT));
    if (fullType != null) {
      return fullType;
    }
    String[] types = resultSet.getColumnDeclaredTypes();
    if (types != null && column >= 1 && column <= types.length && types[column - 1] != null) {
      return types[column - 1];
    }
    return expressionType(column);
  }

  private boolean isDirectColumn(int column) {
    String[] types = resultSet.getColumnDeclaredTypes();
    return types != null && column >= 1 && column <= types.length && types[column - 1] != null;
  }

  private String expressionType(int column) {
    if (!(statement instanceof JDBC4Statement jdbcStatement)) {
      return null;
    }
    String sql = jdbcStatement.getSql();
    if (sql == null) {
      return null;
    }
    int select = sql.toLowerCase(Locale.ROOT).indexOf("select");
    if (select < 0) {
      return null;
    }
    Matcher fromMatcher = FROM_KEYWORD.matcher(sql);
    int from = fromMatcher.find(select + 6) ? fromMatcher.start() : -1;
    String list = from < 0 ? sql.substring(select + 6) : sql.substring(select + 6, from);
    List<String> items = splitTopLevel(list);
    if (column < 1 || column > items.size()) {
      return null;
    }
    String item = items.get(column - 1).trim();
    Matcher castMatcher = CAST_TYPE.matcher(item);
    if (castMatcher.matches()) {
      return castMatcher.group(1).trim();
    }
    String lower = item.toLowerCase(Locale.ROOT);
    if (lower.startsWith("datetime(")) {
      return "TIMESTAMP";
    }
    if (lower.startsWith("time(")) {
      return "TIME";
    }
    if (lower.startsWith("date(")) {
      return "DATE";
    }
    return null;
  }

  private void parseColumnTypes(String createSql) {
    if (createSql == null) {
      return;
    }
    int open = createSql.indexOf('(');
    int close = createSql.lastIndexOf(')');
    if (open < 0 || close <= open) {
      return;
    }
    for (String part : splitTopLevel(createSql.substring(open + 1, close))) {
      String definition = part.trim();
      if (definition.isEmpty()) {
        continue;
      }
      String upper = definition.toUpperCase(Locale.ROOT);
      if (upper.startsWith("PRIMARY")
          || upper.startsWith("UNIQUE")
          || upper.startsWith("CHECK")
          || upper.startsWith("FOREIGN")
          || upper.startsWith("CONSTRAINT")) {
        continue;
      }
      int position = 0;
      String name;
      char first = definition.charAt(0);
      if (first == '"' || first == '`' || first == '[') {
        char closing = first == '[' ? ']' : first;
        int end = definition.indexOf(closing, 1);
        if (end < 0) {
          continue;
        }
        name = definition.substring(1, end);
        position = end + 1;
      } else {
        int end = 0;
        while (end < definition.length() && !Character.isWhitespace(definition.charAt(end))) {
          end++;
        }
        name = definition.substring(0, end);
        position = end;
      }
      String type = typeToken(definition.substring(position).trim());
      if (!type.isEmpty()) {
        declaredTypesWithParams.put(name.toLowerCase(Locale.ROOT), type);
      }
    }
  }

  private static List<String> splitTopLevel(String body) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    char quote = 0;
    int start = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
        continue;
      }
      if (c == '"' || c == '\'' || c == '`') {
        quote = c;
        continue;
      }
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(body.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(body.substring(start));
    return parts;
  }

  private static String typeToken(String rest) {
    if (rest.isEmpty()) {
      return "";
    }
    String upper = rest.toUpperCase(Locale.ROOT);
    int end = rest.length();
    for (String keyword :
        List.of(
            " NOT ",
            " PRIMARY ",
            " UNIQUE ",
            " CHECK ",
            " DEFAULT ",
            " COLLATE ",
            " REFERENCES ",
            " GENERATED ",
            " AS ")) {
      int index = upper.indexOf(keyword);
      if (index >= 0 && index < end) {
        end = index;
      }
    }
    String type = rest.substring(0, end).trim();
    int open = type.indexOf('(');
    if (open >= 0) {
      int close = type.indexOf(')', open);
      if (close > open) {
        type = type.substring(0, close + 1);
      }
    }
    return type;
  }

  private ColumnInfo columnInfo(int column) throws SQLException {
    loadTableInfo();
    String name = getColumnName(column);
    return columnInfo.get(name.toLowerCase(Locale.ROOT));
  }

  private void loadTableInfo() {
    if (tableInfoLoaded) {
      return;
    }
    tableInfoLoaded = true;
    if (!(statement instanceof JDBC4Statement jdbcStatement)) {
      return;
    }
    String sql = jdbcStatement.getSql();
    if (sql == null) {
      return;
    }
    Matcher matcher = FROM_TABLE.matcher(sql);
    if (!matcher.find()) {
      return;
    }
    tableName = unquote(matcher.group(1));
    java.sql.Connection metadataConnection = jdbcStatement.getConnection();
    try (PreparedStatement canonical =
        metadataConnection.prepareStatement(
            "SELECT name FROM sqlite_schema WHERE type IN ('table','view')")) {
      try (ResultSet rows = canonical.executeQuery()) {
        while (rows.next()) {
          String name = rows.getString(1);
          if (name != null && name.equalsIgnoreCase(tableName)) {
            tableName = name;
            break;
          }
        }
      }
    } catch (SQLException e) {
      // Keep the name from the SQL when the catalogue lookup fails.
    }
    try (PreparedStatement ddl =
        metadataConnection.prepareStatement(
            "SELECT sql FROM sqlite_schema WHERE name = ? LIMIT 1")) {
      ddl.setString(1, tableName);
      try (ResultSet rows = ddl.executeQuery()) {
        if (rows.next()) {
          String createSql = rows.getString(1);
          String declaredName = declaredTableName(createSql);
          if (declaredName != null) {
            tableName = declaredName;
          }
          parseColumnTypes(createSql);
        }
      }
    } catch (SQLException e) {
      // Precision and scale remain unavailable without the DDL.
    }
    try (PreparedStatement metadata =
            jdbcStatement
                .getConnection()
                .prepareStatement(
                    "SELECT name, \"notnull\", pk, type FROM pragma_table_xinfo(?)")) {
      metadata.setString(1, tableName);
      try (ResultSet rows = metadata.executeQuery()) {
        while (rows.next()) {
          String name = rows.getString(1);
          if (name == null) {
            continue;
          }
          columnInfo.put(
              name.toLowerCase(Locale.ROOT),
              new ColumnInfo(
                  rows.getLong(2) != 0,
                  rows.getLong(3) != 0,
                  rows.getString(4) == null ? "" : rows.getString(4)));
        }
      }
    } catch (SQLException e) {
      // Metadata is best effort.
    }
  }

  private static String declaredTableName(String createSql) {
    if (createSql == null) {
      return null;
    }
    Matcher matcher = CREATE_TABLE.matcher(createSql);
    return matcher.find() ? unquote(matcher.group(1)) : null;
  }

  private static String unquote(String name) {
    if (name == null || name.length() < 2) {
      return name;
    }
    char first = name.charAt(0);
    char last = name.charAt(name.length() - 1);
    if ((first == '"' && last == '"')
        || (first == '`' && last == '`')
        || (first == '[' && last == ']')) {
      return name.substring(1, name.length() - 1);
    }
    return name;
  }

  private record ColumnInfo(boolean notNull, boolean primaryKey, String type) {}

  private long calculateTimezoneOffset(long timeMillis, Calendar targetCal) {
    Calendar localCal = Calendar.getInstance();
    return targetCal.getTimeZone().getOffset(timeMillis)
        - localCal.getTimeZone().getOffset(timeMillis);
  }

  /**
   * Functional interface for result set value suppliers.
   *
   * @param <T> the type of value to supply
   */
  @FunctionalInterface
  public interface ResultSetSupplier<T> {
    /**
     * Gets a result from the result set.
     *
     * @return the result value
     * @throws Exception if an error occurs
     */
    T get() throws Exception;
  }

  private <T> T wrapTypeConversion(ResultSetSupplier<T> supplier) throws SQLException {
    try {
      return supplier.get();
    } catch (Exception e) {
      throw new SQLException("Type conversion failed: " + e);
    }
  }
}
