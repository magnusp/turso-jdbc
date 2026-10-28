package io.github.magnusp.turso.jdbc4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.magnusp.turso.annotations.Nullable;

/** Reads the parts of a stored {@code CREATE TABLE} statement that metadata needs. */
final class SqliteDdl {

  record ColumnDefinition(String name, String declaredType) {}

  record PrimaryKey(@Nullable String name, List<String> columns) {}

  private static final Pattern CREATE_TABLE =
      Pattern.compile("^\\s*CREATE\\s+TABLE\\s", Pattern.CASE_INSENSITIVE);

  private static final Pattern CONSTRAINT_KEYWORDS =
      Pattern.compile(
          "^(PRIMARY|UNIQUE|CHECK|FOREIGN|CONSTRAINT|NOT|DEFAULT|COLLATE|REFERENCES|GENERATED)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern NAMED_PRIMARY_KEY =
      Pattern.compile(
          "\\bCONSTRAINT\\s*"
              + "(?<name>\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|\\[[^\\]]*\\]|[^\\s]+)"
              + "\\s*PRIMARY\\s+KEY\\s*\\((?<columns>[^()]*)\\)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern PLAIN_PRIMARY_KEY =
      Pattern.compile(
          "\\bPRIMARY\\s+KEY\\s*\\(([^()]*)\\)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern FOREIGN_KEY =
      Pattern.compile(
          "\\b(?:CONSTRAINT\\s*"
              + "(?<name>\"(?:[^\"]|\"\")*\"|`(?:[^`]|``)*`|\\[[^\\]]*\\]|[^\\s]+)"
              + "\\s*)?FOREIGN\\s+KEY\\s*\\(",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private SqliteDdl() {}

  static List<ColumnDefinition> columns(@Nullable String createSql) {
    if (createSql == null || !CREATE_TABLE.matcher(createSql).find()) {
      return List.of();
    }
    int open = createSql.indexOf('(');
    int close = createSql.lastIndexOf(')');
    if (open < 0 || close <= open) {
      return List.of();
    }
    List<ColumnDefinition> columns = new ArrayList<>();
    for (String part : splitTopLevel(createSql.substring(open + 1, close))) {
      ColumnDefinition column = column(part.trim());
      if (column != null) {
        columns.add(column);
      }
    }
    return columns;
  }

  static PrimaryKey primaryKey(@Nullable String createSql) {
    if (createSql == null) {
      return new PrimaryKey(null, List.of());
    }
    String body = maskLiterals(body(createSql));
    Matcher named = NAMED_PRIMARY_KEY.matcher(body);
    if (named.find()) {
      return new PrimaryKey(unquote(named.group("name")), splitColumns(named.group("columns")));
    }
    Matcher plain = PLAIN_PRIMARY_KEY.matcher(body);
    if (plain.find()) {
      return new PrimaryKey(null, splitColumns(plain.group(1)));
    }
    return new PrimaryKey(null, List.of());
  }

  /** Constraint names in declaration order. An unnamed clause contributes {@code null}. */
  static List<String> foreignKeyNames(@Nullable String createSql) {
    if (createSql == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    Matcher matcher = FOREIGN_KEY.matcher(maskLiterals(body(createSql)));
    while (matcher.find()) {
      String name = matcher.group("name");
      names.add(name == null ? null : unquote(name));
    }
    return names;
  }

  static String unquote(String identifier) {
    String value = identifier.trim();
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1).replace("" + first + first, "" + first);
      }
      if (first == '`' && last == '`') {
        return value.substring(1, value.length() - 1).replace("``", "`");
      }
      if (first == '[' && last == ']') {
        return value.substring(1, value.length() - 1).replace("]]", "]");
      }
    }
    return value;
  }

  @Nullable
  private static ColumnDefinition column(String definition) {
    if (definition.isEmpty()) {
      return null;
    }
    String upper = definition.toUpperCase(Locale.ROOT);
    if (CONSTRAINT_KEYWORDS.matcher(upper).find()) {
      return null;
    }
    int position;
    String name;
    char first = definition.charAt(0);
    if (first == '"' || first == '\'' || first == '`' || first == '[') {
      char closing = first == '[' ? ']' : first;
      int end = closingQuoteIndex(definition, closing, 1);
      if (end < 0) {
        return null;
      }
      name = unquote(definition.substring(0, end + 1));
      position = end + 1;
    } else {
      int end = 0;
      while (end < definition.length()
          && !Character.isWhitespace(definition.charAt(end))
          && definition.charAt(end) != '(') {
        end++;
      }
      name = definition.substring(0, end);
      position = end;
    }
    return new ColumnDefinition(name, typeToken(definition.substring(position).trim()));
  }

  private static String typeToken(String rest) {
    if (rest.isEmpty() || CONSTRAINT_KEYWORDS.matcher(rest.toUpperCase(Locale.ROOT)).find()) {
      return "";
    }
    char first = rest.charAt(0);
    if (first == '"' || first == '\'' || first == '`' || first == '[') {
      char closing = first == '[' ? ']' : first;
      int end = closingQuoteIndex(rest, closing, 1);
      if (end > 0) {
        return unquote(rest.substring(0, end + 1));
      }
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
    return unquote(type);
  }

  private static List<String> splitColumns(String columns) {
    List<String> names = new ArrayList<>();
    for (String spec : splitTopLevel(columns)) {
      String name = firstIdentifier(spec.trim());
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  /** Returns the column identifier from a key list entry, dropping ASC, DESC, and COLLATE. */
  private static String firstIdentifier(String spec) {
    if (spec.isEmpty()) {
      return "";
    }
    char first = spec.charAt(0);
    if (first == '"' || first == '\'' || first == '`' || first == '[') {
      char closing = first == '[' ? ']' : first;
      int end = closingQuoteIndex(spec, closing, 1);
      return end < 0 ? unquote(spec) : unquote(spec.substring(0, end + 1));
    }
    int end = 0;
    while (end < spec.length() && !Character.isWhitespace(spec.charAt(end))) {
      end++;
    }
    return unquote(spec.substring(0, end));
  }

  private static int closingQuoteIndex(String text, char closing, int from) {
    for (int i = from; i < text.length(); i++) {
      if (text.charAt(i) == closing) {
        if (i + 1 < text.length() && text.charAt(i + 1) == closing) {
          i++;
          continue;
        }
        return i;
      }
    }
    return -1;
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
          if (i + 1 < body.length() && body.charAt(i + 1) == quote) {
            i++;
          } else {
            quote = 0;
          }
        }
        continue;
      }
      if (c == '"' || c == '\'' || c == '`') {
        quote = c;
      } else if (c == '[') {
        quote = ']';
      } else if (c == '(') {
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

  private static String body(String createSql) {
    int open = createSql.indexOf('(');
    int close = createSql.lastIndexOf(')');
    return open >= 0 && close > open ? createSql.substring(open + 1, close) : createSql;
  }

  /**
   * Replaces string literals and comments with spaces so constraint keywords inside a default
   * value or a comment do not look like primary-key or foreign-key clauses.
   */
  private static String maskLiterals(String sql) {
    StringBuilder masked = new StringBuilder(sql);
    int index = 0;
    while (index < masked.length()) {
      char c = masked.charAt(index);
      if (c == '\'') {
        int end = index + 1;
        while (end < masked.length()) {
          if (masked.charAt(end) == '\'') {
            if (end + 1 < masked.length() && masked.charAt(end + 1) == '\'') {
              end += 2;
              continue;
            }
            break;
          }
          end++;
        }
        int last = Math.min(end, masked.length() - 1);
        for (int i = index; i <= last; i++) {
          masked.setCharAt(i, ' ');
        }
        index = end + 1;
        continue;
      }
      if (c == '-' && index + 1 < masked.length() && masked.charAt(index + 1) == '-') {
        int end = masked.indexOf("\n", index);
        end = end < 0 ? masked.length() : end;
        for (int i = index; i < end; i++) {
          masked.setCharAt(i, ' ');
        }
        index = end;
        continue;
      }
      if (c == '/' && index + 1 < masked.length() && masked.charAt(index + 1) == '*') {
        int end = masked.indexOf("*/", index + 2);
        end = end < 0 ? masked.length() : end + 2;
        for (int i = index; i < end; i++) {
          masked.setCharAt(i, ' ');
        }
        index = end;
        continue;
      }
      index++;
    }
    return masked.toString();
  }
}
