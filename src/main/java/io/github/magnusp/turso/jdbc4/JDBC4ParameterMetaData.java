package io.github.magnusp.turso.jdbc4;

import java.sql.JDBCType;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import io.github.magnusp.turso.annotations.Nullable;

/** Parameter metadata derived from the values bound to a prepared statement. */
final class JDBC4ParameterMetaData implements ParameterMetaData {

  private final JDBC4PreparedStatement statement;

  JDBC4ParameterMetaData(JDBC4PreparedStatement statement) {
    this.statement = statement;
  }

  @Override
  public int getParameterCount() {
    return statement.parameterCount();
  }

  @Override
  public int isNullable(int param) throws SQLException {
    parameterValue(param);
    return parameterNullable;
  }

  @Override
  public boolean isSigned(int param) throws SQLException {
    parameterValue(param);
    return true;
  }

  @Override
  public int getPrecision(int param) throws SQLException {
    parameterValue(param);
    return 0;
  }

  @Override
  public int getScale(int param) throws SQLException {
    parameterValue(param);
    return 0;
  }

  @Override
  public int getParameterType(int param) throws SQLException {
    return jdbcType(parameterValue(param));
  }

  @Override
  public String getParameterTypeName(int param) throws SQLException {
    return JDBCType.valueOf(getParameterType(param)).getName();
  }

  @Override
  public String getParameterClassName(int param) throws SQLException {
    parameterValue(param);
    return "java.lang.String";
  }

  @Override
  public int getParameterMode(int param) throws SQLException {
    parameterValue(param);
    return parameterModeIn;
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("ParameterMetaData does not wrap " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  private Object parameterValue(int param) throws SQLException {
    Object value = statement.parameterValue(param);
    if (!statement.isParameterSet(param)) {
      throw new SQLException("No parameter has been set yet");
    }
    return value;
  }

  private static int jdbcType(@Nullable Object value) {
    if (value == null) {
      return Types.NULL;
    }
    if (value instanceof Integer
        || value instanceof Short
        || value instanceof Byte
        || value instanceof Boolean) {
      return Types.INTEGER;
    }
    if (value instanceof Long) {
      return Types.BIGINT;
    }
    if (value instanceof Double || value instanceof Float) {
      return Types.REAL;
    }
    return Types.VARCHAR;
  }
}
