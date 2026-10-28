package io.github.magnusp.turso.conformance;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Excludes explicitly listed xerial test methods from the Turso conformance run.
 *
 * <p>Entries cover the selected xerial methods whose expectations the embedded engine cannot meet
 * today: missing DatabaseMetaData and ParameterMetaData surfaces, engine schema limits, and the
 * deliberate stored-procedure contract. Every entry names a method, a reason, the upstream
 * difference, and an issue, and {@link TursoQuarantineTest} rejects a blank or stale entry.
 * Class-level entries are not supported. The filter is inactive in the {@code xerial-control} run,
 * where the reference driver is the subject.
 */
public final class TursoQuarantine implements PostDiscoveryFilter {

    /** One upstream test method whose xerial expectation is quarantined on Turso. */
    record Quarantined(
            String className,
            String methodName,
            String reason,
            String upstreamDifference,
            String issue) {}

    static final List<Quarantined> QUARANTINED =
            List.of(
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetColumns",
                            "getColumns returns 20 columns",
                            "xerial returns 24 columns in JDBC 4.3 order",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetPrimaryKeys",
                            "getPrimaryKeys returns the literal PK_NAME 'PRIMARY'",
                            "xerial returns null for an unnamed primary key and the constraint name when named",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetTables",
                            "getTables exposes engine-internal __turso_internal_seq_* tables and omits temp and system tables",
                            "xerial returns GLOBAL TEMPORARY, SYSTEM TABLE, TABLE, and VIEW rows in that order",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumns",
                            "getColumns returns lower-case type names and zero size and precision",
                            "xerial reports declared type names in upper case with column size and decimal digits",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumnsIncludingGenerated",
                            "getColumns has no IS_GENERATEDCOLUMN column at position 24",
                            "xerial returns 24 columns including IS_GENERATEDCOLUMN",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumnsPrecisionScale",
                            "getColumns returns lower-case type names and zero size and precision",
                            "xerial reports NUMERIC(10,5) as NUMERIC with size 15 and 5 decimal digits",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumnsWithEscape",
                            "getColumns does not apply getSearchStringEscape",
                            "xerial expands the escape character before matching the column pattern",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getTables",
                            "getTables omits sqlite_schema and sqlite_sequence",
                            "xerial lists sqlite_schema first as SYSTEM TABLE, then the user tables and views",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getTablesWithEscape",
                            "getTables does not apply getSearchStringEscape",
                            "xerial expands the escape character before matching the table pattern",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "moreOfgetColumns",
                            "getColumns maps the declared type to Types.OTHER (1111)",
                            "xerial maps the declared type to its JDBC type",
                            "https://github.com/magnusp/turso-jdbc/issues/14"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetTableTypes",
                            "getTableTypes returns null",
                            "xerial returns the TABLE_TYPE column and four type rows",
                            "https://github.com/magnusp/turso-jdbc/issues/15"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetTypeInfo",
                            "getTypeInfo returns null",
                            "xerial returns the 18-column type-info result set in JDBC 4.3 order",
                            "https://github.com/magnusp/turso-jdbc/issues/15"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getTableTypes",
                            "getTableTypes returns null",
                            "xerial returns GLOBAL TEMPORARY, SYSTEM TABLE, TABLE, and VIEW in that order",
                            "https://github.com/magnusp/turso-jdbc/issues/15"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getTypeInfo",
                            "getTypeInfo returns null",
                            "xerial returns the 18-column type-info result set and the SQLite type rows",
                            "https://github.com/magnusp/turso-jdbc/issues/15"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetExportedKeys",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetImportedKeys",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getCrossReferenceCatalogSchemaWithQuote",
                            "getCrossReference returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysCatalogSchemaWithQuote",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysColsForMultipleImports",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysColsForNamedKeys",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysTableNameWithQuote",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysCatalogSchemaWithQuote",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysCols2",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForMixedCaseDefinition",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForMultipleImports",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForNamedKeys",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysMultipleColumns",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysWithIncorrectReference",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "numberOfgetExportedKeysCols",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "numberOfgetImportedKeysCols",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest$SqliteSchema",
                            "getExportedKeys",
                            "getExportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest$SqliteSchema",
                            "getImportedKeys",
                            "getImportedKeys returns null",
                            "xerial returns a JDBC 4.3 foreign-key result set with the parent and child table, column, and key sequence",
                            "https://github.com/magnusp/turso-jdbc/issues/16"),
                    new Quarantined(
                            "org.sqlite.PrepStmtTest",
                            "getParameterTypeTest",
                            "getParameterMetaData returns null",
                            "xerial infers BIGINT and REAL from the bound setter",
                            "https://github.com/magnusp/turso-jdbc/issues/17"),
                    new Quarantined(
                            "org.sqlite.PrepStmtTest",
                            "getParameterTypeTest_when_no_parameter_set",
                            "getParameterMetaData returns null",
                            "xerial returns a ParameterMetaData that throws 'No parameter has been set yet'",
                            "https://github.com/magnusp/turso-jdbc/issues/17"),
                    new Quarantined(
                            "org.sqlite.PrepStmtTest",
                            "paramMetaData",
                            "getParameterMetaData returns null",
                            "xerial returns a ParameterMetaData with getParameterCount() == 4",
                            "https://github.com/magnusp/turso-jdbc/issues/17"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "autoincrement",
                            "the embedded engine rejects WITHOUT ROWID tables",
                            "xerial creates the table and asserts autoincrement metadata",
                            "https://github.com/magnusp/turso-jdbc/issues/19"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumnsIncludingGeneratedStored",
                            "the embedded engine rejects stored generated columns",
                            "xerial creates the table and reports IS_GENERATEDCOLUMN=YES",
                            "https://github.com/magnusp/turso-jdbc/issues/19"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetProcedurColumns",
                            "getProcedureColumns throws SQLFeatureNotSupportedException by design",
                            "xerial returns an empty 20-column procedure-column result set",
                            "https://github.com/magnusp/turso-jdbc/issues/21"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetProcedures",
                            "getProcedures throws SQLFeatureNotSupportedException by design",
                            "xerial returns an empty 9-column procedure result set",
                            "https://github.com/magnusp/turso-jdbc/issues/21"));

    /**
     * Development switch for running quarantined methods against Turso. The guard tests stay
     * active; only the method filter is bypassed.
     */
    static final String ENABLED_PROPERTY = "conformance.quarantine.enabled";

    private static final Set<String> EXCLUDED = ConcurrentHashMap.newKeySet();

    static void reset() {
        EXCLUDED.clear();
    }

    static int excludedCount() {
        return EXCLUDED.size();
    }

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (TursoControl.isEnabled()
                || !quarantineEnabled()
                || !(descriptor.getSource().orElse(null) instanceof MethodSource source)) {
            return FilterResult.included("Turso quarantine is inactive for this run");
        }
        for (Quarantined quarantined : QUARANTINED) {
            if (quarantined.className().equals(source.getClassName())
                    && quarantined.methodName().equals(source.getMethodName())) {
                EXCLUDED.add(source.getClassName() + "#" + source.getMethodName());
                return FilterResult.excluded(quarantined.reason());
            }
        }
        return FilterResult.included("not quarantined");
    }

    private static boolean quarantineEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));
    }
}
