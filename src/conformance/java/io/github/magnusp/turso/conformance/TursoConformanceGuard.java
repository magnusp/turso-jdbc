package io.github.magnusp.turso.conformance;

import io.github.magnusp.turso.core.TursoNative;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Fails fast when the conformance suite is not running against the embedded Turso driver.
 *
 * <p>This is deliberately stricter than a connection check: it verifies the driver identity, the
 * database product, the URL prefix, the pinned native SDK version, and a live SQLite version
 * query. It does not use libSQL's {@code alt1} source-id convention, because the embedded engine
 * is not a libSQL server.
 */
final class TursoConformanceGuard {

    static final String EXPECTED_DRIVER_NAME = "turso-jdbc";
    static final String EXPECTED_DATABASE_PRODUCT_NAME_PREFIX = "SQLite";
    static final String EXPECTED_URL_PREFIX = "jdbc:turso:";

    private static final String PROBE_URL = "jdbc:sqlite::memory:";

    private TursoConformanceGuard() {}

    static void verify() {
        try (Connection connection = DriverManager.getConnection(PROBE_URL)) {
            verify(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Conformance suite could not obtain a JDBC connection from " + PROBE_URL, e);
        }
    }

    static void verify(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String driverName = metadata.getDriverName();
        String productName = metadata.getDatabaseProductName();
        String url = metadata.getURL();

        if (!EXPECTED_DRIVER_NAME.equals(driverName)
                || !productName.startsWith(EXPECTED_DATABASE_PRODUCT_NAME_PREFIX)) {
            throw new AssertionError(
                    "Conformance suite is not connected to Turso: expected driver \""
                            + EXPECTED_DRIVER_NAME
                            + "\" and database product starting with \""
                            + EXPECTED_DATABASE_PRODUCT_NAME_PREFIX
                            + "\", but resolved driver \""
                            + driverName
                            + "\" and database product \""
                            + productName
                            + "\"");
        }

        if (url == null || !url.startsWith(EXPECTED_URL_PREFIX)) {
            throw new AssertionError(
                    "Conformance suite connection URL does not start with \""
                            + EXPECTED_URL_PREFIX
                            + "\": "
                            + url);
        }

        String nativeVersion = TursoNative.version();
        if (nativeVersion == null || nativeVersion.isBlank()) {
            throw new AssertionError(
                    "Conformance suite could not read a non-blank turso_sdk_kit version");
        }

        String sqliteVersion = readSqliteVersion(connection);
        if (sqliteVersion == null || sqliteVersion.isBlank()) {
            throw new AssertionError(
                    "Conformance suite could not read a non-blank SQLite version through driver \""
                            + driverName
                            + "\"");
        }
    }

    private static String readSqliteVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT sqlite_version()")) {
            if (!resultSet.next()) {
                throw new AssertionError(
                        "Conformance suite could not read a SQLite version from the Turso connection");
            }
            return resultSet.getString(1);
        }
    }
}
