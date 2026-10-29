package io.github.magnusp.turso.jdbc4;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import io.github.magnusp.turso.annotations.Nullable;

/** Builds a synthetic result set from already computed metadata rows. */
final class MetadataRows {

  private MetadataRows() {}

  /**
   * Returns a result set with the named columns. The first branch never matches so the column
   * shape is present even when {@code rows} is empty.
   */
  static ResultSet of(
      JDBC4Connection connection, List<String> columns, List<List<Object>> rows)
      throws SQLException {
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append("NULL AS ").append(columns.get(i));
    }
    sql.append(" WHERE 1=0");
    for (List<Object> row : rows) {
      sql.append(" UNION ALL SELECT ");
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          sql.append(", ");
        }
        appendLiteral(sql, row.get(i));
      }
    }
    PreparedStatement statement = connection.prepareStatement(sql.toString());
    statement.closeOnCompletion();
    return statement.executeQuery();
  }

  private static void appendLiteral(StringBuilder sql, @Nullable Object value) {
    if (value == null) {
      sql.append("NULL");
    } else if (value instanceof Number || value instanceof Boolean) {
      sql.append(value);
    } else {
      sql.append('\'').append(value.toString().replace("'", "''")).append('\'');
    }
  }
}
