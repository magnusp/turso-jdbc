package io.github.magnusp.turso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Database metadata behaviour covered directly rather than only through the reference suite. */
class DatabaseMetaDataTest {

  private static Connection connect(Path dir, String name) throws SQLException {
    return DriverManager.getConnection("jdbc:turso:" + dir.resolve(name + ".db"));
  }

  private static List<String> columnNames(ResultSet rs) throws SQLException {
    ResultSetMetaData metadata = rs.getMetaData();
    List<String> names = new ArrayList<>();
    for (int i = 1; i <= metadata.getColumnCount(); i++) {
      names.add(metadata.getColumnName(i));
    }
    return names;
  }

  @Test
  void getColumnsHasTheJdbc43Shape(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "columns");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE shape (id INTEGER, name TEXT)");

      try (ResultSet columns = connection.getMetaData().getColumns(null, null, "shape", "%")) {
        assertThat(columns.next()).isTrue();
        assertThat(columnNames(columns))
            .containsExactly(
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "COLUMN_NAME",
                "DATA_TYPE",
                "TYPE_NAME",
                "COLUMN_SIZE",
                "BUFFER_LENGTH",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "NULLABLE",
                "REMARKS",
                "COLUMN_DEF",
                "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE",
                "SCOPE_CATALOG",
                "SCOPE_SCHEMA",
                "SCOPE_TABLE",
                "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT",
                "IS_GENERATEDCOLUMN");
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("id");
        assertThat(columns.getInt("DATA_TYPE")).isEqualTo(Types.INTEGER);
        assertThat(columns.getInt("ORDINAL_POSITION")).isEqualTo(1);
      }
    }
  }

  @Test
  void getColumnsMarksVirtualGeneratedColumn(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "generated");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE generated (value INTEGER, doubled INTEGER GENERATED ALWAYS AS (value * 2))");

      try (ResultSet columns =
          connection.getMetaData().getColumns(null, null, "generated", "%")) {
        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("value");
        assertThat(columns.getString("IS_GENERATEDCOLUMN")).isEqualTo("NO");

        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("doubled");
        assertThat(columns.getString("IS_GENERATEDCOLUMN")).isEqualTo("YES");
        assertThat(columns.next()).isFalse();
      }
    }
  }

  @Test
  void getTableTypesAndTypeInfoShape(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "types")) {
      DatabaseMetaData metadata = connection.getMetaData();

      List<String> tableTypes = new ArrayList<>();
      try (ResultSet rows = metadata.getTableTypes()) {
        assertThat(columnNames(rows)).containsExactly("TABLE_TYPE");
        while (rows.next()) {
          tableTypes.add(rows.getString("TABLE_TYPE"));
        }
      }
      assertThat(tableTypes)
          .containsExactly("GLOBAL TEMPORARY", "SYSTEM TABLE", "TABLE", "VIEW");

      List<String> typeNames = new ArrayList<>();
      try (ResultSet rows = metadata.getTypeInfo()) {
        List<String> names = columnNames(rows);
        assertThat(names).hasSize(18);
        assertThat(names.get(0)).isEqualTo("TYPE_NAME");
        assertThat(names.get(17)).isEqualTo("NUM_PREC_RADIX");
        while (rows.next()) {
          typeNames.add(rows.getString("TYPE_NAME"));
          assertThat(rows.getInt("NUM_PREC_RADIX")).isEqualTo(10);
        }
      }
      assertThat(typeNames).containsExactly("NULL", "INTEGER", "REAL", "TEXT", "BLOB");
    }
  }

  @Test
  void foreignKeysRoundTripWithNamedConstraint(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "foreign-keys");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE parent (id INTEGER, CONSTRAINT parent_pk PRIMARY KEY (id))");
      statement.executeUpdate(
          "CREATE TABLE child (pid INTEGER, "
              + "CONSTRAINT child_fk FOREIGN KEY (pid) REFERENCES parent (id))");

      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet imported = metadata.getImportedKeys(null, null, "child")) {
        assertThat(imported.next()).isTrue();
        assertThat(imported.getString("PKTABLE_NAME")).isEqualTo("parent");
        assertThat(imported.getString("PKCOLUMN_NAME")).isEqualTo("id");
        assertThat(imported.getString("PK_NAME")).isEqualTo("parent_pk");
        assertThat(imported.getString("FKTABLE_NAME")).isEqualTo("child");
        assertThat(imported.getString("FKCOLUMN_NAME")).isEqualTo("pid");
        assertThat(imported.getString("FK_NAME")).isEqualTo("child_fk");
        assertThat(imported.next()).isFalse();
      }

      try (ResultSet exported = metadata.getExportedKeys(null, null, "parent")) {
        assertThat(exported.next()).isTrue();
        assertThat(exported.getString("PKTABLE_NAME")).isEqualTo("parent");
        assertThat(exported.getString("PKCOLUMN_NAME")).isEqualTo("id");
        assertThat(exported.getString("PK_NAME")).isEqualTo("parent_pk");
        assertThat(exported.getString("FKTABLE_NAME")).isEqualTo("child");
        assertThat(exported.getString("FKCOLUMN_NAME")).isEqualTo("pid");
        assertThat(exported.getString("FK_NAME")).isEqualTo("child_fk");
        assertThat(exported.next()).isFalse();
      }

      try (ResultSet primaryKeys = metadata.getPrimaryKeys(null, null, "parent")) {
        assertThat(primaryKeys.next()).isTrue();
        assertThat(primaryKeys.getString("COLUMN_NAME")).isEqualTo("id");
        assertThat(primaryKeys.getString("PK_NAME")).isEqualTo("parent_pk");
        assertThat(primaryKeys.next()).isFalse();
      }
    }
  }

  @Test
  void getTablesOmitsImplicitIndexes(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "implicit-indexes");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE composite (a INTEGER, b INTEGER, PRIMARY KEY (a, b))");

      List<String> tables = new ArrayList<>();
      try (ResultSet rows =
          connection.getMetaData().getTables(null, null, null, new String[] {"TABLE", "SYSTEM TABLE"})) {
        while (rows.next()) {
          tables.add(rows.getString("TABLE_NAME"));
        }
      }
      assertThat(tables).contains("composite", "sqlite_schema");
      assertThat(tables).noneMatch(name -> name.startsWith("sqlite_autoindex_"));
    }
  }

  @Test
  void exportedKeysAreOrderedByChildTable(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "exported-order");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE books (id INTEGER, previous INTEGER,"
              + " CONSTRAINT books_pk PRIMARY KEY (id),"
              + " CONSTRAINT books_previous FOREIGN KEY (previous) REFERENCES books (id))");
      statement.executeUpdate(
          "CREATE TABLE bookauthors (bookid INTEGER,"
              + " CONSTRAINT bookauthors_book FOREIGN KEY (bookid) REFERENCES books (id))");

      List<String> children = new ArrayList<>();
      try (ResultSet rows = connection.getMetaData().getExportedKeys(null, null, "books")) {
        while (rows.next()) {
          children.add(rows.getString("FKTABLE_NAME"));
        }
      }
      assertThat(children).containsExactly("bookauthors", "books");
    }
  }

  @Test
  void primaryKeyParsingHandlesOrderingAndQuotedCommas(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "primary-key-parsing");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE ordered (a TEXT, b TEXT, CONSTRAINT ordered_pk PRIMARY KEY (a DESC))");
      statement.executeUpdate(
          "CREATE TABLE quoted (a TEXT, b TEXT, \"a,b\" TEXT,"
              + " CONSTRAINT quoted_pk PRIMARY KEY (\"a,b\"))");

      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet keys = metadata.getPrimaryKeys(null, null, "ordered")) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getString("COLUMN_NAME")).isEqualTo("a");
        assertThat(keys.getString("PK_NAME")).isEqualTo("ordered_pk");
        assertThat(keys.next()).isFalse();
      }
      try (ResultSet keys = metadata.getPrimaryKeys(null, null, "quoted")) {
        assertThat(keys.next()).isTrue();
        assertThat(keys.getString("COLUMN_NAME")).isEqualTo("a,b");
        assertThat(keys.getString("PK_NAME")).isEqualTo("quoted_pk");
        assertThat(keys.next()).isFalse();
      }
    }
  }

  @Test
  void defaultLiteralDoesNotLookLikeAPrimaryKey(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "phantom-key");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE phantom (a TEXT DEFAULT 'PRIMARY KEY (x)', b TEXT)");

      try (ResultSet keys = connection.getMetaData().getPrimaryKeys(null, null, "phantom")) {
        assertThat(keys.next()).isFalse();
      }
    }
  }

  @Test
  void getColumnsHandlesQuotedIdentifiersAndTypes(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "quoted-columns");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE quoted (\"dq\"\"name\" VARCHAR(10), a \"VARCHAR(10)\", b [INTEGER])");

      try (ResultSet columns = connection.getMetaData().getColumns(null, null, "quoted", "%")) {
        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("dq\"name");
        assertThat(columns.getString("TYPE_NAME")).isEqualTo("VARCHAR");
        assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(10);

        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("a");
        assertThat(columns.getString("TYPE_NAME")).isEqualTo("VARCHAR");
        assertThat(columns.getInt("COLUMN_SIZE")).isEqualTo(10);

        assertThat(columns.next()).isTrue();
        assertThat(columns.getString("COLUMN_NAME")).isEqualTo("b");
        assertThat(columns.getString("TYPE_NAME")).isEqualTo("INTEGER");
        assertThat(columns.next()).isFalse();
      }
    }
  }

  @Test
  void getTablesSkipsTempIndexesAndTriggers(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "temp-objects");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TEMP TABLE tt (a TEXT)");
      statement.executeUpdate("CREATE INDEX ti ON tt(a)");
      statement.executeUpdate(
          "CREATE TRIGGER trg AFTER INSERT ON tt BEGIN UPDATE tt SET a = a; END");

      List<String> tempTables = new ArrayList<>();
      try (ResultSet rows =
          connection.getMetaData().getTables(null, null, null, new String[] {"GLOBAL TEMPORARY"})) {
        while (rows.next()) {
          tempTables.add(rows.getString("TABLE_NAME"));
        }
      }
      assertThat(tempTables).containsExactly("tt");
    }
  }

  @Test
  void foreignKeysForUnknownTableAreEmpty(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "unknown-foreign-keys")) {
      DatabaseMetaData metadata = connection.getMetaData();

      try (ResultSet imported = metadata.getImportedKeys(null, null, "missing")) {
        assertThat(columnNames(imported)).hasSize(14);
        assertThat(imported.next()).isFalse();
      }

      try (ResultSet exported = metadata.getExportedKeys(null, null, "missing")) {
        assertThat(columnNames(exported)).hasSize(14);
        assertThat(exported.next()).isFalse();
      }
    }
  }

  @Test
  void supportsIntegrityEnhancementFacility(@TempDir Path dir) throws Exception {
    try (Connection connection = connect(dir, "integrity")) {
      assertThat(connection.getMetaData().supportsIntegrityEnhancementFacility()).isTrue();
    }
  }

  @Test
  void orphanRowIsAcceptedByDefaultAndRejectedWithForeignKeysOn(@TempDir Path dir)
      throws Exception {
    try (Connection connection = connect(dir, "orphan-default");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE p (id INTEGER PRIMARY KEY)");
      statement.executeUpdate("CREATE TABLE c (pid INTEGER REFERENCES p(id))");
      assertThat(statement.executeUpdate("INSERT INTO c VALUES (99)")).isEqualTo(1);
    }
    try (Connection connection =
            DriverManager.getConnection(
                "jdbc:turso:" + dir.resolve("orphan-default.db") + "?foreign_keys=on");
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO c VALUES (98)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("FOREIGN KEY constraint failed");
    }
  }
}
