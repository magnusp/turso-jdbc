package io.github.magnusp.turso.conformance;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Excludes explicitly listed reference-test methods from the Turso conformance run.
 *
 * <p>Entries cover the selected reference-test methods whose expectations the embedded engine
 * cannot meet today: folded identifier case (#23), two experimental schema features (#19), the
 * deliberate stored-procedure contract (#21), BIT read as Boolean (#20), the catalog model
 * (#36), and the engine's zero-column `PRAGMA compile_options` result (#83). Every entry names a
 * method, a reason, the difference from the reference driver, and an
 * issue, and {@link TursoQuarantineTest} rejects a blank or stale entry. Class-level entries are
 * not supported. The filter is inactive in the reference probe, where the reference driver is the
 * subject.
 */
public final class TursoQuarantine implements PostDiscoveryFilter {

    /** One reference-test method whose expectation is quarantined on Turso. */
    record Quarantined(
            String className,
            String methodName,
            String reason,
            String referenceDifference,
            String issue) {}

    static final List<Quarantined> QUARANTINED =
            List.of(
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetTables",
                            "column order fixture creates TABLE1, TABLE3 and VIEW1, which the engine stores folded",
                            "the reference driver reports the declared spelling; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getTables",
                            "the fixture view is stored as testview",
                            "the reference driver reports testView; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumns",
                            "the fixture view is stored as testview",
                            "the reference driver reports testView; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "moreOfgetColumns",
                            "the mixed-case fixture table is stored as tabcols2",
                            "the reference driver reports TabCols2; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetExportedKeys",
                            "the last fixture parent is stored as referred",
                            "the reference driver reports REFERRED; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysColsForMultipleImports",
                            "the fixture parent is stored as parent1",
                            "the reference driver reports PARENT1; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getExportedKeysColsForNamedKeys",
                            "the fixture parent is stored as parent1",
                            "the reference driver reports PARENT1; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysCols2",
                            "the fixture tables are stored as authors and books",
                            "the reference driver reports Authors and Books; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForMixedCaseDefinition",
                            "the fixture parent is stored as parent1",
                            "the reference driver reports Parent1; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForMultipleImports",
                            "the fixture parents are stored as parent1 and parent2",
                            "the reference driver reports PARENT1 and PARENT2; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysColsForNamedKeys",
                            "the fixture parent is stored as parent1",
                            "the reference driver reports PARENT1; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getImportedKeysMultipleColumns",
                            "the fixture parent is stored as pone",
                            "the reference driver reports PONE; the engine folds every identifier to lower case",
                            "https://github.com/magnusp/turso-jdbc/issues/23"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "autoincrement",
                            "the engine gates WITHOUT ROWID tables behind the experimental without_rowid feature",
                            "the reference driver creates the table and asserts autoincrement metadata",
                            "https://github.com/magnusp/turso-jdbc/issues/19"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "getColumnsIncludingGeneratedStored",
                            "the engine rejects stored generated columns",
                            "the reference driver creates the table and reports IS_GENERATEDCOLUMN=YES",
                            "https://github.com/magnusp/turso-jdbc/issues/19"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetProcedurColumns",
                            "getProcedureColumns throws SQLFeatureNotSupportedException by design",
                            "the reference driver returns an empty 20-column procedure-column result set",
                            "https://github.com/magnusp/turso-jdbc/issues/21"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetProcedures",
                            "getProcedures throws SQLFeatureNotSupportedException by design",
                            "the reference driver returns an empty 9-column procedure result set",
                            "https://github.com/magnusp/turso-jdbc/issues/21"),
                    new Quarantined(
                            "org.sqlite.PrepStmtTest",
                            "getObject",
                            "JDBC 4.3 maps BIT to Boolean; the reference driver returns Integer",
                            "the reference test asserts Integer for the BIT column; the engine returns Boolean for a declared BIT column",
                            "https://github.com/magnusp/turso-jdbc/issues/20"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "columnOrderOfgetCatalogs",
                            "getCatalogs returns the main and temp databases as catalogs",
                            "the reference driver returns an empty catalog result set; Turso models the database name as the catalog",
                            "https://github.com/magnusp/turso-jdbc/issues/36"),
                    new Quarantined(
                            "org.sqlite.DBMetaDataTest",
                            "version",
                            "PRAGMA compile_options returns a result with no columns, so reading it as a result set throws before the test's assumption runs",
                            "the reference driver returns compile-option rows with one column; the engine's compile_options result has zero columns",
                            "https://github.com/magnusp/turso-jdbc/issues/83"));

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
