package io.github.magnusp.turso.core;

/** Leading SQL trivia handling shared by statement classification and transaction control. */
final class SqlText {

  private SqlText() {}

  /** Returns {@code sql} without leading whitespace, empty statements, or comments. */
  static String stripLeadingTrivia(String sql) {
    if (sql == null) {
      return "";
    }
    int index = 0;
    while (index < sql.length()) {
      char c = sql.charAt(index);
      if (Character.isWhitespace(c) || c == ';') {
        index++;
        continue;
      }
      if (c == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
        int end = sql.indexOf('\n', index + 2);
        index = end < 0 ? sql.length() : end + 1;
        continue;
      }
      if (c == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
        int end = sql.indexOf("*/", index + 2);
        index = end < 0 ? sql.length() : end + 2;
        continue;
      }
      break;
    }
    return sql.substring(index);
  }

  static boolean hasContent(String sql) {
    return sql != null && !stripLeadingTrivia(sql).isBlank();
  }
}
