package io.github.magnusp.turso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The embedded Turso fit suite. Everything here goes through the public JDBC API.
 *
 * <p>Every test that needs a database uses a fresh file under a JUnit temporary directory, except
 * the in-memory test.
 */
class EmbeddedTursoTest {

    private static Connection connect(String path) throws SQLException {
        return DriverManager.getConnection("jdbc:turso:" + path);
    }

    private static String db(Path dir, String name) {
        return dir.resolve(name + ".db").toString();
    }

    @Test
    void ddlInsertSelectAndUpdateCount(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "ddl")); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate(
                            "CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)"))
                    .isZero();

            assertThat(s.executeUpdate("INSERT INTO items (name) VALUES ('alpha')")).isEqualTo(1);

            try (ResultSet rs = s.executeQuery("SELECT id, name FROM items")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1L);
                assertThat(rs.getString("name")).isEqualTo("alpha");
                assertThat(rs.next()).isFalse();
            }

            assertThat(s.executeUpdate("UPDATE items SET name = 'beta' WHERE name = 'alpha'")).isEqualTo(1);
            assertThat(s.executeUpdate("DELETE FROM items WHERE id = 1")).isEqualTo(1);
            assertThat(s.executeUpdate("DELETE FROM items WHERE id = 1")).isZero();
        }
    }

    @Test
    void generatedKeys(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "keys")); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE k (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)");
            s.executeUpdate("INSERT INTO k (name) VALUES ('one')");
            try (ResultSet keys = s.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getLong(1)).isEqualTo(1L);
            }

            try (PreparedStatement ps = c.prepareStatement("INSERT INTO k (name) VALUES (?)")) {
                ps.setString(1, "two");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    assertThat(keys.getLong(1)).isEqualTo(2L);
                }
            }
        }
    }

    @Test
    void transactionCommitAndRollback(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "tx")); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");

            c.setAutoCommit(false);
            s.executeUpdate("INSERT INTO t (v) VALUES ('committed')");
            c.commit();

            s.executeUpdate("INSERT INTO t (v) VALUES ('rolled-back')");
            c.rollback();

            try (ResultSet rs = s.executeQuery("SELECT v FROM t ORDER BY id")) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
                assertThat(values).containsExactly("committed");
            }
        }
    }

    @Test
    void savepointRollbackKeepsEarlierWork(@TempDir Path dir) throws Exception {
        String path = db(dir, "savepoint");
        try (Connection c = connect(path)) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)");
            }
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO t (v) VALUES ('base')");
                Savepoint sp = c.setSavepoint("sp1");
                s.executeUpdate("INSERT INTO t (v) VALUES ('nested')");
                c.rollback(sp);
                c.commit();
            }
        }

        // A second connection proves the commit really reached the file.
        try (Connection c = connect(path); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT v FROM t ORDER BY id")) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            assertThat(values).containsExactly("base");
        }
    }

    @Test
    void savepointInAutoCommitCommitsOnRelease(@TempDir Path dir) throws Exception {
        String path = db(dir, "savepoint-auto");
        try (Connection c = connect(path)) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)");
                Savepoint sp = c.setSavepoint();
                s.executeUpdate("INSERT INTO t (v) VALUES ('auto')");
                c.releaseSavepoint(sp);
            }
        }
        try (Connection c = connect(path); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT v FROM t")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("auto");
        }
    }

    @Test
    void batchInsert(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "batch"))) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE b (id INTEGER PRIMARY KEY AUTOINCREMENT, v TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO b (v) VALUES (?)")) {
                for (String v : List.of("a", "b", "c")) {
                    ps.setString(1, v);
                    ps.addBatch();
                }
                assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    List<Long> ids = new ArrayList<>();
                    while (keys.next()) {
                        ids.add(keys.getLong(1));
                    }
                    assertThat(ids).containsExactly(1L, 2L, 3L);
                }
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM b")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(3L);
            }
        }
    }

    @Test
    void valueTypes(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "types"))) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE v (i INTEGER, r REAL, t TEXT, b BLOB, n TEXT)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO v (i, r, t, b, n) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, 42L);
                ps.setDouble(2, 2.5d);
                ps.setString(3, "text");
                ps.setBytes(4, new byte[] {1, 2, 3});
                ps.setNull(5, Types.VARCHAR);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT i, r, t, b, n FROM v")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(42L);
                assertThat(rs.getDouble(2)).isEqualTo(2.5d);
                assertThat(rs.getString(3)).isEqualTo("text");
                assertThat(rs.getBytes(4)).containsExactly(1, 2, 3);
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.wasNull()).isTrue();
            }
        }
    }

    @Test
    void bitColumnReadsAsBoolean(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "bit"))) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE flags (id INTEGER, b BIT)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO flags (id, b) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setBoolean(2, true);
                assertThat(ps.executeUpdate()).isEqualTo(1);
                ps.setInt(1, 2);
                ps.setBoolean(2, false);
                assertThat(ps.executeUpdate()).isEqualTo(1);
                ps.setInt(1, 3);
                ps.setNull(2, Types.BIT);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT b FROM flags ORDER BY id")) {
                ResultSetMetaData metaData = rs.getMetaData();
                assertThat(metaData.getColumnType(1)).isEqualTo(Types.BIT);
                assertThat(metaData.getColumnClassName(1)).isEqualTo(Boolean.class.getName());
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isEqualTo(Boolean.TRUE);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isEqualTo(Boolean.FALSE);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
                assertThat(rs.wasNull()).isTrue();
            }
        }
    }

    @Test
    void resultSetMetaDataWithResolvedDeclaredTypes(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "md-resolved"))) {
            createMetadataTable(c, "items_resolved");
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "SELECT id, name, price, data, amount FROM items_resolved")) {
                ResultSetMetaData md = rs.getMetaData();
                System.out.println("[spike] decltype=resolved " + describe(md));
                assertThat(md.getColumnCount()).isEqualTo(5);
                assertThat(md.getColumnName(1)).isEqualTo("id");
                assertThat(md.getColumnName(2)).isEqualTo("name");
                assertThat(md.getColumnType(1)).isEqualTo(Types.INTEGER);
                assertThat(md.getColumnType(2)).isEqualTo(Types.VARCHAR);
                assertThat(md.getColumnType(3)).isEqualTo(Types.REAL);
                assertThat(md.getColumnType(4)).isEqualTo(Types.BLOB);
                assertThat(md.getColumnType(5)).isEqualTo(Types.NUMERIC);
                assertThat(md.getColumnTypeName(2)).isEqualTo("TEXT");
                assertThat(md.getColumnTypeName(5)).isEqualTo("NUMERIC");
                // The engine reports the bare type, but the driver recovers the modifiers
                // from sqlite_schema.sql.
                assertThat(md.getPrecision(5)).isEqualTo(10);
                assertThat(md.getScale(5)).isEqualTo(2);
            }
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                            "SELECT name, type FROM pragma_table_xinfo('items_resolved')")) {
                List<String> pragma = new ArrayList<>();
                while (rs.next()) {
                    pragma.add(rs.getString(1) + ":" + rs.getString(2));
                }
                System.out.println("[spike] pragma_table_xinfo=" + pragma);
            }
        }
    }

    /** Records what the JDBC metadata surfaces do with a null and with a resolved decltype. */
    private static Map<String, String> describe(ResultSetMetaData md) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            int column = i;
            out.put(
                    md.getColumnName(i),
                    "type=" + probe(() -> md.getColumnType(column))
                            + " typename=" + probe(() -> md.getColumnTypeName(column))
                            + " precision=" + probe(() -> md.getPrecision(column))
                            + " scale=" + probe(() -> md.getScale(column))
                            + " nullable=" + probe(() -> md.isNullable(column))
                            + " table=" + probe(() -> md.getTableName(column))
                            + " class=" + probe(() -> md.getColumnClassName(column)));
        }
        return out;
    }

    private interface Probe {
        Object get() throws SQLException;
    }

    private static String probe(Probe probe) {
        try {
            return String.valueOf(probe.get());
        } catch (SQLException e) {
            return "ERR(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    private static void createMetadataTable(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE " + table + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT NOT NULL, "
                    + "price REAL, "
                    + "data BLOB, "
                    + "amount NUMERIC(10,2))");
            s.executeUpdate("INSERT INTO " + table + " (name, price, data, amount) "
                    + "VALUES ('widget', 1.25, x'0102', 12.34)");
            s.executeUpdate("CREATE INDEX idx_" + table + "_name ON " + table + " (name)");
        }
    }

    @Test
    void databaseMetaData(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "meta"))) {
            createMetadataTable(c, "items_meta");
            DatabaseMetaData md = c.getMetaData();
            System.out.println("[spike] productVersion=" + md.getDatabaseProductVersion());

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, null, "items_meta", null)) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME") + ":" + rs.getString("TABLE_TYPE"));
                }
            }
            System.out.println("[spike] getTables=" + tables);
            assertThat(tables).contains("items_meta:TABLE");

            List<String> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, null, "items_meta", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME") + ":" + rs.getString("TYPE_NAME")
                            + ":" + rs.getInt("NULLABLE"));
                }
            }
            System.out.println("[spike] getColumns=" + columns);
            assertThat(columns).hasSize(5);

            List<String> primaryKeys = new ArrayList<>();
            try (ResultSet rs = md.getPrimaryKeys(null, null, "items_meta")) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME") + ":" + rs.getShort("KEY_SEQ"));
                }
            }
            System.out.println("[spike] getPrimaryKeys=" + primaryKeys);
            assertThat(primaryKeys).containsExactly("id:1");

            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = md.getIndexInfo(null, null, "items_meta", false, false)) {
                while (rs.next()) {
                    indexes.add(rs.getString("INDEX_NAME") + ":" + rs.getString("COLUMN_NAME"));
                }
            }
            System.out.println("[spike] getIndexInfo=" + indexes);

            assertThat(md.getDatabaseProductVersion()).isNotBlank();
        }
    }

    @Test
    void uniqueConstraintMapsToIntegrityViolation(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "unique"))) {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE u (id INTEGER PRIMARY KEY, v TEXT UNIQUE)");
                s.executeUpdate("INSERT INTO u (v) VALUES ('dup')");
                assertThatThrownBy(() -> s.executeUpdate("INSERT INTO u (v) VALUES ('dup')"))
                        .isInstanceOf(SQLIntegrityConstraintViolationException.class)
                        .satisfies(e -> {
                            System.out.println("[spike] unique error: " + e);
                            assertThat(((SQLException) e).getSQLState()).isEqualTo("23000");
                            assertThat(((SQLException) e).getErrorCode()).isEqualTo(19);
                        });
            }
        }
    }

    @Test
    void syntaxErrorMapsToSyntaxErrorException(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "syntax")); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("SELEC 1"))
                    .isInstanceOf(SQLSyntaxErrorException.class)
                    .satisfies(e -> System.out.println("[spike] syntax error: " + e));
        }
    }

    /**
     * FFM-only: unlike the published binding, the C ABI treats {@code :memory:} as an in-memory
     * database, so two statements on one connection share the same database.
     */
    @Test
    void memoryDatabaseSurvivesTwoStatements() throws Exception {
        try (Connection c = connect(":memory:"); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE m (id INTEGER PRIMARY KEY, v TEXT)");
            s.executeUpdate("INSERT INTO m (v) VALUES ('kept')");
            try (ResultSet rs = s.executeQuery("SELECT v FROM m")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("kept");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    /** FFM-only: the C ABI reports a real declared type for direct table columns. */
    @Test
    void colDecltypeSuppliesBaseType(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "decltype")); Statement s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE d (i INTEGER, t TEXT, r REAL, b BLOB, n NUMERIC(10,2))");
            s.executeUpdate("INSERT INTO d (i, t, r, b, n) VALUES (1, 'x', 2.5, x'00ff', 3.5)");
            try (ResultSet rs = s.executeQuery("SELECT i, t, r, b, n FROM d")) {
                ResultSetMetaData md = rs.getMetaData();
                System.out.println("[spike] decltype=ffm " + describe(md));
                assertThat(md.getColumnTypeName(1)).isEqualTo("INTEGER");
                assertThat(md.getColumnTypeName(2)).isEqualTo("TEXT");
                assertThat(md.getColumnTypeName(3)).isEqualTo("REAL");
                assertThat(md.getColumnTypeName(4)).isEqualTo("BLOB");
                assertThat(md.getColumnTypeName(5)).isEqualTo("NUMERIC");
            }
        }
    }

    @Test
    void filePathPersistsAcrossConnections(@TempDir Path dir) throws Exception {
        String path = db(dir, "file");
        try (Connection c = connect(path); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE f (v TEXT)");
            s.executeUpdate("INSERT INTO f (v) VALUES ('persisted')");
        }
        try (Connection c = connect(path); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT v FROM f")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("persisted");
        }
    }

    @Test
    void setReadOnlyRejectsWritesAndRestoresThemOnRequest(@TempDir Path dir) throws Exception {
        try (Connection c = connect(db(dir, "readonly")); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE r (v TEXT)");
            s.executeUpdate("INSERT INTO r (v) VALUES ('before')");

            assertThat(c.isReadOnly()).isFalse();
            c.setReadOnly(true);
            assertThat(c.isReadOnly()).isTrue();
            assertThat(c.getMetaData().isReadOnly()).isTrue();

            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO r (v) VALUES ('blocked')"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM r")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }

            c.setReadOnly(false);
            assertThat(c.isReadOnly()).isFalse();
            assertThat(c.getMetaData().isReadOnly()).isFalse();
            assertThat(s.executeUpdate("INSERT INTO r (v) VALUES ('after')")).isEqualTo(1);
        }
    }

}
