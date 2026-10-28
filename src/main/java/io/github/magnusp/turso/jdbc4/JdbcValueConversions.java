package io.github.magnusp.turso.jdbc4;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Lenient conversions used by the JDBC result-set getters. */
final class JdbcValueConversions {

  private JdbcValueConversions() {}

  static String toStringValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return String.valueOf(value);
  }

  static boolean toBoolean(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue() != 0d;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  static long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof Boolean bool) {
      return bool ? 1L : 0L;
    }
    String text = String.valueOf(value).trim();
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      return (long) Double.parseDouble(text);
    }
  }

  static int toInt(Object value) {
    return (int) toLong(value);
  }

  static short toShort(Object value) {
    return (short) toLong(value);
  }

  static byte toByte(Object value) {
    return (byte) toLong(value);
  }

  static double toDouble(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof Boolean bool) {
      return bool ? 1d : 0d;
    }
    return Double.parseDouble(String.valueOf(value).trim());
  }

  static float toFloat(Object value) {
    return (float) toDouble(value);
  }

  static BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    if (value instanceof Double || value instanceof Float) {
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    if (value instanceof Number number) {
      return BigDecimal.valueOf(number.longValue());
    }
    return new BigDecimal(String.valueOf(value).trim());
  }

  static byte[] toBytes(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return bytes;
    }
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }

  static Date toDate(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof Date date) {
      return date;
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return new Date(timestamp.getTime());
    }
    if (value instanceof Number number) {
      return new Date(number.longValue());
    }
    String text = String.valueOf(value).trim();
    String date = text.length() > 10 ? text.substring(0, 10) : text;
    try {
      return Date.valueOf(date);
    } catch (IllegalArgumentException e) {
      throw new SQLException("Bad value for type Date: " + text, e);
    }
  }

  static Time toTime(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof Time time) {
      return time;
    }
    if (value instanceof Number number) {
      return new Time(number.longValue());
    }
    String text = String.valueOf(value).trim();
    int space = text.indexOf(' ');
    if (space >= 0) {
      text = text.substring(space + 1);
    }
    try {
      return Time.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new SQLException("Bad value for type Time: " + text, e);
    }
  }

  static Timestamp toTimestamp(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp;
    }
    if (value instanceof Date date) {
      return new Timestamp(date.getTime());
    }
    if (value instanceof Number number) {
      return new Timestamp(number.longValue());
    }
    String text = String.valueOf(value).trim();
    if (text.length() == 10) {
      text = text + " 00:00:00";
    }
    try {
      return Timestamp.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new SQLException("Bad value for type Timestamp: " + text, e);
    }
  }

  static LocalDate toLocalDate(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDate date) {
      return date;
    }
    if (value instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    String text = String.valueOf(value).trim();
    try {
      return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
    } catch (RuntimeException e) {
      throw new SQLException("Bad value for type LocalDate: " + text, e);
    }
  }

  static LocalTime toLocalTime(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalTime time) {
      return time;
    }
    String text = String.valueOf(value).trim();
    int space = text.indexOf(' ');
    if (space >= 0) {
      text = text.substring(space + 1);
    }
    try {
      return LocalTime.parse(text);
    } catch (RuntimeException e) {
      throw new SQLException("Bad value for type LocalTime: " + text, e);
    }
  }

  static LocalDateTime toLocalDateTime(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    String text = String.valueOf(value).trim();
    if (text.length() == 10) {
      text = text + " 00:00:00";
    }
    try {
      return LocalDateTime.parse(text.replace(' ', 'T'));
    } catch (RuntimeException e) {
      throw new SQLException("Bad value for type LocalDateTime: " + text, e);
    }
  }
}
