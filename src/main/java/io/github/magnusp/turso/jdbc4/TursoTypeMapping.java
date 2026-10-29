package io.github.magnusp.turso.jdbc4;

import java.sql.Types;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps Turso's declared type strings to JDBC types. */
final class TursoTypeMapping {

  private static final Pattern SIZE = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

  private TursoTypeMapping() {}

  static int columnType(String declaredType) {
    if (declaredType == null) {
      return Types.OTHER;
    }
    String type = baseName(declaredType).toUpperCase(java.util.Locale.ROOT);
    if (type.equals("TINYINT")) {
      return Types.TINYINT;
    }
    if (type.equals("SMALLINT") || type.equals("INT2")) {
      return Types.SMALLINT;
    }
    if (type.equals("BIGINT") || type.equals("INT8") || type.contains("BIG")) {
      return Types.BIGINT;
    }
    if (type.contains("INT")) {
      return Types.INTEGER;
    }
    if (type.contains("VARCHAR") || type.contains("NVARCHAR") || type.contains("VARYING")) {
      return Types.VARCHAR;
    }
    if (type.contains("CLOB")) {
      return Types.CLOB;
    }
    if (type.contains("CHAR")) {
      return Types.CHAR;
    }
    if (type.contains("TEXT") || type.contains("STRING")) {
      return Types.VARCHAR;
    }
    if (type.contains("REAL")) {
      return Types.REAL;
    }
    if (type.contains("DOUBLE")) {
      return Types.DOUBLE;
    }
    if (type.contains("FLOAT")) {
      return Types.FLOAT;
    }
    if (type.contains("BLOB") || type.isEmpty()) {
      return Types.BLOB;
    }
    if (type.contains("DEC")) {
      return Types.DECIMAL;
    }
    if (type.contains("NUM")) {
      return Types.NUMERIC;
    }
    if (type.equals("BIT")) {
      return Types.BIT;
    }
    if (type.contains("BOOL")) {
      return Types.BOOLEAN;
    }
    if (type.contains("DATETIME") || type.contains("TIMESTAMP")) {
      return Types.TIMESTAMP;
    }
    if (type.contains("DATE")) {
      return Types.DATE;
    }
    if (type.contains("TIME")) {
      return Types.TIME;
    }
    return Types.OTHER;
  }

  static String typeName(String declaredType) {
    return declaredType == null ? null : baseName(declaredType);
  }

  static int precision(String declaredType) {
    Matcher matcher = SIZE.matcher(declaredType == null ? "" : declaredType);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  static int scale(String declaredType) {
    Matcher matcher = SIZE.matcher(declaredType == null ? "" : declaredType);
    return matcher.find() && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
  }

  static String className(int type) {
    return switch (type) {
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.BIGINT -> "java.lang.Integer";
      case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR -> "java.lang.String";
      case Types.REAL, Types.FLOAT, Types.DOUBLE -> "java.lang.Double";
      case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "java.lang.Object";
      case Types.NUMERIC, Types.DECIMAL -> "java.math.BigDecimal";
      case Types.BOOLEAN -> "java.lang.Integer";
      case Types.BIT -> "java.lang.Boolean";
      case Types.DATE -> "java.sql.Date";
      case Types.TIME -> "java.sql.Time";
      case Types.TIMESTAMP -> "java.sql.Timestamp";
      default -> "java.lang.Object";
    };
  }

  static boolean isSigned(int type) {
    return type == Types.INTEGER
        || type == Types.SMALLINT
        || type == Types.TINYINT
        || type == Types.BIGINT
        || type == Types.REAL
        || type == Types.FLOAT
        || type == Types.DOUBLE
        || type == Types.NUMERIC
        || type == Types.DECIMAL;
  }

  static boolean isCaseSensitive(int type) {
    return type == Types.VARCHAR || type == Types.CHAR || type == Types.LONGVARCHAR;
  }

  private static String baseName(String declaredType) {
    int open = declaredType.indexOf('(');
    String base = open < 0 ? declaredType : declaredType.substring(0, open);
    return base.trim();
  }
}
