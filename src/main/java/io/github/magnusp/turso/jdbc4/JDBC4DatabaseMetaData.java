package io.github.magnusp.turso.jdbc4;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.annotations.SkipNullableCheck;
import io.github.magnusp.turso.core.TursoPropertiesHolder;

/** JDBC 4 DatabaseMetaData implementation for Turso databases. */
public final class JDBC4DatabaseMetaData implements DatabaseMetaData {

  private static final List<String> TABLE_COLUMNS =
      List.of(
          "TABLE_CAT",
          "TABLE_SCHEM",
          "TABLE_NAME",
          "TABLE_TYPE",
          "REMARKS",
          "TYPE_CAT",
          "TYPE_SCHEM",
          "TYPE_NAME",
          "SELF_REFERENCING_COL_NAME",
          "REF_GENERATION");

  private static final List<String> TABLE_TYPES =
      List.of("GLOBAL TEMPORARY", "SYSTEM TABLE", "TABLE", "VIEW");

  private static final List<String> COLUMN_COLUMNS =
      List.of(
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

  private static final List<String> PRIMARY_KEY_COLUMNS =
      List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");

  private static final List<String> BEST_ROW_COLUMNS =
      List.of(
          "SCOPE",
          "COLUMN_NAME",
          "DATA_TYPE",
          "TYPE_NAME",
          "COLUMN_SIZE",
          "BUFFER_LENGTH",
          "DECIMAL_DIGITS",
          "PSEUDO_COLUMN");

  private static final List<String> FOREIGN_KEY_COLUMNS =
      List.of(
          "PKTABLE_CAT",
          "PKTABLE_SCHEM",
          "PKTABLE_NAME",
          "PKCOLUMN_NAME",
          "KEY_SEQ",
          "UPDATE_RULE",
          "DELETE_RULE",
          "FKTABLE_CAT",
          "FKTABLE_SCHEM",
          "FKTABLE_NAME",
          "FKCOLUMN_NAME",
          "FK_NAME",
          "PK_NAME",
          "DEFERRABILITY");

  private static final int DEFAULT_COLUMN_SIZE = 2000000000;

  private final JDBC4Connection connection;

  @Nullable private PreparedStatement getCatalogs = null;

  @Nullable private PreparedStatement getSchemas = null;

  @Nullable private PreparedStatement getUDTs = null;

  @Nullable private PreparedStatement getColumnsTblName = null;

  @Nullable private PreparedStatement getSuperTypes = null;

  @Nullable private PreparedStatement getSuperTables = null;

  @Nullable private PreparedStatement getTablePrivileges = null;

  @Nullable private PreparedStatement getIndexInfo = null;

  @Nullable private PreparedStatement getProcedures = null;

  @Nullable private PreparedStatement getAttributes = null;

  @Nullable private PreparedStatement getVersionColumns = null;

  @Nullable private PreparedStatement getColumnPrivileges = null;

  /**
   * Executes a metadata query with close-on-completion so the temporary statement is released when
   * its result set is closed.
   */
  private ResultSet metadataQuery(PreparedStatement statement) throws SQLException {
    statement.closeOnCompletion();
    return statement.executeQuery();
  }

  /**
   * Creates a new JDBC4DatabaseMetaData instance.
   *
   * @param connection the database connection
   */
  public JDBC4DatabaseMetaData(JDBC4Connection connection) {
    this.connection = connection;
  }

  @Override
  public boolean allProceduresAreCallable() {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() {
    return true;
  }

  @Override
  public String getURL() {
    return connection.getUrl();
  }

  @Override
  @Nullable
  public String getUserName() {
    return null;
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    return connection.isReadOnly();
  }

  @Override
  public boolean nullsAreSortedHigh() {
    return true;
  }

  @Override
  public boolean nullsAreSortedLow() {
    return !nullsAreSortedHigh();
  }

  @Override
  public boolean nullsAreSortedAtStart() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtEnd() {
    return !nullsAreSortedAtStart();
  }

  @Override
  public String getDatabaseProductName() {
    return "Turso";
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet version = statement.executeQuery("SELECT sqlite_version()")) {
      return version.next() ? version.getString(1) : "";
    }
  }

  @Override
  public String getDriverName() {
    return TursoPropertiesHolder.getDriverName();
  }

  @Override
  public String getDriverVersion() {
    return TursoPropertiesHolder.getDriverVersion();
  }

  @Override
  public int getDriverMajorVersion() {
    return Integer.parseInt(getDriverVersion().split("\\.")[0]);
  }

  @Override
  public int getDriverMinorVersion() {
    return Integer.parseInt(getDriverVersion().split("\\.")[1]);
  }

  @Override
  public boolean usesLocalFiles() {
    return true;
  }

  @Override
  public boolean usesLocalFilePerTable() {
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public String getIdentifierQuoteString() {
    return "\"";
  }

  @Override
  public String getSQLKeywords() {
    // TODO: add more turso supported keywords
    return ("ABORT,ACTION,AFTER,ANALYZE,ATTACH,AUTOINCREMENT,BEFORE,"
        + "CASCADE,CONFLICT,DATABASE,DEFERRABLE,DEFERRED,DESC,DETACH,"
        + "EXCLUSIVE,EXPLAIN,FAIL,GLOB,IGNORE,INDEX,INDEXED,INITIALLY,INSTEAD,ISNULL,"
        + "KEY,LIMIT,NOTNULL,OFFSET,PLAN,PRAGMA,QUERY,"
        + "RAISE,REGEXP,REINDEX,RENAME,REPLACE,RESTRICT,"
        + "TEMP,TEMPORARY,TRANSACTION,VACUUM,VIEW,VIRTUAL");
  }

  @Override
  public String getNumericFunctions() {
    // TODO
    return "";
  }

  @Override
  public String getStringFunctions() {
    // TOOD
    return "";
  }

  @Override
  public String getSystemFunctions() {
    // TODO
    return "";
  }

  @Override
  public String getTimeDateFunctions() {
    // TODO
    return "";
  }

  @Override
  public String getSearchStringEscape() {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() {
    return "";
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() {
    return false;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() {
    return false;
  }

  @Override
  public boolean supportsColumnAliasing() {
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() {
    return true;
  }

  @Override
  public boolean supportsConvert() {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) {
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() {
    return false;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() {
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() {
    return false;
  }

  @Override
  public boolean supportsGroupBy() {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() {
    return false;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() {
    return false;
  }

  @Override
  public boolean supportsLikeEscapeClause() {
    return false;
  }

  @Override
  public boolean supportsMultipleResultSets() {
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() {
    return true;
  }

  @Override
  public boolean supportsNonNullableColumns() {
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() {
    // ODBC minimum grammar should be supported, which are the followings:
    //   SELECT
    //   INSERT
    //   UPDATE
    //   DELETE
    //   CREATE TABLE
    //   DROP TABLE
    //   GRANT
    //   REVOKE
    //   COMMIT
    //   ROLLBACK
    //   DECLARE CURSOR
    //   FETCH
    //   CLOSE CURSOR
    // TODO: Let's return true when turso supports them all
    return false;
  }

  @Override
  public boolean supportsCoreSQLGrammar() {
    // supportsMinimumSQLGrammar() and some additional such as:
    //   Joins (INNER JOIN, OUTER JOIN, LEFT JOIN, RIGHT JOIN)
    //   Set operations (UNION, INTERSECT, EXCEPT)
    //   Subqueries (e.g., SELECT * FROM table WHERE column IN (SELECT column FROM another_table))
    //   Table expressions (SELECT column FROM (SELECT * FROM table) AS subquery)
    //   Data type support (includes more SQL data types like FLOAT, NUMERIC, DECIMAL)
    //   Basic string functions (e.g., LENGTH(), SUBSTRING(), CONCAT())
    // TODO: Let's return true when turso supports them all
    return false;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() {
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() {
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() {
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() {
    return false;
  }

  @Override
  public boolean supportsOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() {
    return true;
  }

  @Override
  public String getSchemaTerm() {
    return "schema";
  }

  @Override
  public String getProcedureTerm() {
    return "not_implemented";
  }

  @Override
  public String getCatalogTerm() {
    return "catalog";
  }

  @Override
  public boolean isCatalogAtStart() {
    // sqlite and turso doesn't use catalog
    return false;
  }

  @Override
  public String getCatalogSeparator() {
    return ".";
  }

  @Override
  public boolean supportsSchemasInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() {
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() {
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() {
    return false;
  }

  @Override
  public boolean supportsSubqueriesInExists() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() {
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() {
    return false;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() {
    return false;
  }

  @Override
  public boolean supportsUnion() {
    // TODO: return true when turso supports
    return false;
  }

  @Override
  public boolean supportsUnionAll() {
    // TODO: return true when turso supports
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() {
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() {
    return false;
  }

  @Override
  public int getMaxBinaryLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxCharLiteralLength() {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() {
    return 0;
  }

  @Override
  public int getMaxColumnsInGroupBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() {
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() {
    return 0;
  }

  @Override
  public int getMaxConnections() {
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() {
    return 0;
  }

  @Override
  public int getMaxIndexLength() {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() {
    return 0;
  }

  @Override
  public int getMaxProcedureNameLength() {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() {
    return 0;
  }

  @Override
  public int getMaxRowSize() {
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() {
    return false;
  }

  @Override
  public int getMaxStatementLength() {
    return 0;
  }

  @Override
  public int getMaxStatements() {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() {
    return 0;
  }

  @Override
  public int getMaxTablesInSelect() {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() {
    return 0;
  }

  @Override
  public int getDefaultTransactionIsolation() {
    // TODO: after turso introduces Hekaton MVCC, what should we return?
    return Connection.TRANSACTION_SERIALIZABLE;
  }

  @Override
  public boolean supportsTransactions() {
    // TODO: turso doesn't support transactions fully, let's return true when supported
    return false;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) {
    return Connection.TRANSACTION_SERIALIZABLE == level;
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() {
    // TODO: return true when supported
    return false;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() {
    return false;
  }

  @Override
  public ResultSet getProcedures(
      String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
    throw new SQLFeatureNotSupportedException("turso does not support stored procedures", "0A000");
  }

  @Override
  public ResultSet getProcedureColumns(
      String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("turso does not support stored procedures", "0A000");
  }

  @Override
  public ResultSet getTables(
      @Nullable String catalog,
      @Nullable String schemaPattern,
      @Nullable String tableNamePattern,
      @Nullable String[] types)
      throws SQLException {
    if ((catalog != null && !catalog.isEmpty())
        || (schemaPattern != null && !schemaPattern.isEmpty())) {
      return MetadataRows.of(connection, TABLE_COLUMNS, List.of());
    }

    StringBuilder sql =
        new StringBuilder(
            "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, t.name AS TABLE_NAME, "
                + "t.TABLE_TYPE AS TABLE_TYPE, NULL AS REMARKS, NULL AS TYPE_CAT, "
                + "NULL AS TYPE_SCHEM, NULL AS TYPE_NAME, "
                + "NULL AS SELF_REFERENCING_COL_NAME, NULL AS REF_GENERATION FROM ("
                + "SELECT 'sqlite_schema' AS name, 'SYSTEM TABLE' AS TABLE_TYPE "
                + "UNION ALL SELECT name, 'SYSTEM TABLE' FROM sqlite_schema "
                + "WHERE name LIKE 'sqlite\\_%' ESCAPE '\\' AND type IN ('table','view') "
                + "UNION ALL SELECT name, CASE type WHEN 'view' THEN 'VIEW' ELSE 'TABLE' END "
                + "FROM sqlite_schema WHERE type IN ('table','view') "
                + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' "
                + "AND name NOT LIKE '\\_\\_turso\\_internal\\_seq\\_%' ESCAPE '\\' "
                + "UNION ALL SELECT name, 'GLOBAL TEMPORARY' FROM sqlite_temp_master "
                + "WHERE type IN ('table','view')"
                + ") t WHERE 1=1");

    List<String> typeFilter = new ArrayList<>();
    if (types != null) {
      for (String type : types) {
        if (type != null) {
          typeFilter.add(type.toUpperCase(Locale.ROOT));
        }
      }
    }
    if (!typeFilter.isEmpty()) {
      sql.append(" AND t.TABLE_TYPE IN (");
      for (int i = 0; i < typeFilter.size(); i++) {
        sql.append(i > 0 ? ", ?" : "?");
      }
      sql.append(")");
    }
    if (tableNamePattern != null) {
      sql.append(" AND t.name LIKE ? ESCAPE '\\'");
    }
    sql.append(" ORDER BY 4, 3");

    PreparedStatement statement = connection.prepareStatement(sql.toString());
    int paramIndex = 1;
    for (String type : typeFilter) {
      statement.setString(paramIndex++, type);
    }
    if (tableNamePattern != null) {
      statement.setString(paramIndex, tableNamePattern);
    }
    return metadataQuery(statement);
  }

  @Override
  public ResultSet getSchemas() throws SQLException {
    if (getSchemas == null || getSchemas.isClosed()) {
      connection.checkOpen();
      getSchemas =
          connection.prepareStatement("select null as TABLE_SCHEM, null as TABLE_CATALOG limit 0;");
    }

    return metadataQuery(getSchemas);
  }

  @Override
  public ResultSet getCatalogs() throws SQLException {
    if (getCatalogs == null || getCatalogs.isClosed()) {
      connection.checkOpen();
      getCatalogs = connection.prepareStatement("select null as TABLE_CAT limit 0;");
    }

    return metadataQuery(getCatalogs);
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    for (String type : TABLE_TYPES) {
      rows.add(List.of(type));
    }
    return MetadataRows.of(connection, List.of("TABLE_TYPE"), rows);
  }

  @Override
  public ResultSet getColumns(
      @Nullable String catalog,
      @Nullable String schemaPattern,
      @Nullable String tableNamePattern,
      @Nullable String columnNamePattern)
      throws SQLException {
    if ((catalog != null && !catalog.isEmpty())
        || (schemaPattern != null && !schemaPattern.isEmpty())) {
      return MetadataRows.of(connection, COLUMN_COLUMNS, List.of());
    }

    String columnPattern = columnNamePattern == null ? "%" : columnNamePattern;
    Map<String, String> createSql = loadCreateSql();
    List<String> tables = new ArrayList<>();
    try (ResultSet tableRows = getTables(null, null, tableNamePattern, null)) {
      while (tableRows.next()) {
        tables.add(tableRows.getString("TABLE_NAME"));
      }
    }
    tables.sort(Comparator.naturalOrder());

    List<List<Object>> rows = new ArrayList<>();
    for (String table : tables) {
      String ddl = createSql.get(table.toLowerCase(Locale.ROOT));
      Map<String, String> declaredTypes = declaredTypes(ddl);
      List<PragmaColumn> columns = readColumns(table, columnPattern);
      int primaryKeyColumns = 0;
      for (PragmaColumn column : columns) {
        if (column.primaryKey() > 0) {
          primaryKeyColumns++;
        }
      }
      boolean autoincrement =
          ddl != null && ddl.toUpperCase(Locale.ROOT).contains("AUTOINCREMENT");
      for (PragmaColumn column : columns) {
        String declaredType =
            declaredTypes.containsKey(column.name().toLowerCase(Locale.ROOT))
                ? declaredTypes.get(column.name().toLowerCase(Locale.ROOT))
                : column.type();
        rows.add(columnRow(table, column, declaredType, autoincrement, primaryKeyColumns));
      }
    }
    return MetadataRows.of(connection, COLUMN_COLUMNS, rows);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getColumnPrivileges(
      String catalog, String schema, String table, String columnNamePattern) throws SQLException {
    if (getColumnPrivileges == null || getColumnPrivileges.isClosed()) {
      connection.checkOpen();
      getColumnPrivileges =
          connection.prepareStatement(
              "select null as TABLE_CAT, null as TABLE_SCHEM, "
                  + "null as TABLE_NAME, null as COLUMN_NAME, null as GRANTOR, null as GRANTEE, "
                  + "null as PRIVILEGE, null as IS_GRANTABLE limit 0;");
    }

    return metadataQuery(getColumnPrivileges);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {
    if (getTablePrivileges == null || getTablePrivileges.isClosed()) {
      connection.checkOpen();
      getTablePrivileges =
          connection.prepareStatement(
              "select  null as TABLE_CAT, "
                  + "null as TABLE_SCHEM, null as TABLE_NAME, null as GRANTOR, null "
                  + "GRANTEE,  null as PRIVILEGE, null as IS_GRANTABLE limit 0;");
    }
    return metadataQuery(getTablePrivileges);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getBestRowIdentifier(
      String catalog, String schema, String table, int scope, boolean nullable)
      throws SQLException {
    TableRef source = table == null ? null : findTable(table);
    if (source == null) {
      return MetadataRows.of(connection, BEST_ROW_COLUMNS, List.of());
    }

    List<PragmaColumn> columns = readTableInfo(source.name());
    List<PragmaColumn> key = primaryKeyForBestRow(columns, nullable);
    if (key.isEmpty()) {
      key = uniqueNotNullIndexColumns(source.name(), columns);
    }

    List<List<Object>> rows = new ArrayList<>();
    if (key.isEmpty()) {
      rows.add(bestRowColumn("rowid", "INTEGER", DatabaseMetaData.bestRowPseudo));
    } else {
      for (PragmaColumn column : key) {
        rows.add(bestRowColumn(column.name(), column.type(), DatabaseMetaData.bestRowNotPseudo));
      }
    }
    return MetadataRows.of(connection, BEST_ROW_COLUMNS, rows);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getVersionColumns(String catalog, String schema, String table)
      throws SQLException {
    if (getVersionColumns == null || getVersionColumns.isClosed()) {
      connection.checkOpen();
      getVersionColumns =
          connection.prepareStatement(
              "select null as SCOPE, null as COLUMN_NAME, "
                  + "null as DATA_TYPE, null as TYPE_NAME, null as COLUMN_SIZE, "
                  + "null as BUFFER_LENGTH, null as DECIMAL_DIGITS, null as PSEUDO_COLUMN limit 0;");
    }
    return metadataQuery(getVersionColumns);
  }

  @Override
  public ResultSet getPrimaryKeys(
      @Nullable String catalog, @Nullable String schema, @Nullable String table)
      throws SQLException {
    if (table == null) {
      return MetadataRows.of(connection, PRIMARY_KEY_COLUMNS, List.of());
    }
    TableRef source = findTable(table);
    if (source == null) {
      return MetadataRows.of(connection, PRIMARY_KEY_COLUMNS, List.of());
    }

    List<PragmaColumn> pragma = readTableInfo(source.name());
    SqliteDdl.PrimaryKey primaryKey = SqliteDdl.primaryKey(source.sql());
    List<PrimaryKeyColumn> keyColumns = new ArrayList<>();
    if (!primaryKey.columns().isEmpty()) {
      List<String> names = primaryKey.columns();
      for (int i = 0; i < names.size(); i++) {
        keyColumns.add(new PrimaryKeyColumn(matchColumnName(names.get(i), pragma), i + 1));
      }
    } else {
      for (PragmaColumn column : pragma) {
        if (column.primaryKey() > 0) {
          keyColumns.add(new PrimaryKeyColumn(column.name(), column.primaryKey()));
        }
      }
    }
    keyColumns.sort(Comparator.comparing(PrimaryKeyColumn::name));

    List<List<Object>> rows = new ArrayList<>();
    for (PrimaryKeyColumn column : keyColumns) {
      rows.add(
          Arrays.asList(
              null, null, table, column.name(), column.sequence(), primaryKey.name()));
    }
    return MetadataRows.of(connection, PRIMARY_KEY_COLUMNS, rows);
  }

  @Override
  public ResultSet getImportedKeys(
      @Nullable String catalog, @Nullable String schema, @Nullable String table)
      throws SQLException {
    TableRef child = table == null ? null : findTable(table);
    if (child == null) {
      return emptyForeignKeys();
    }
    List<String> fkNames = reversedForeignKeyNames(child.sql());
    List<List<Object>> rows = new ArrayList<>();
    int group = -1;
    long previousId = Long.MIN_VALUE;
    for (ForeignKeyRef foreignKey : readForeignKeys(child.name())) {
      if (foreignKey.id() != previousId) {
        group++;
        previousId = foreignKey.id();
      }
      String parentColumn = foreignKey.to();
      TableRef parent = findTable(foreignKey.table());
      if (parentColumn == null || parentColumn.isEmpty()) {
        parentColumn = firstPrimaryKeyColumn(parent);
      }
      String pkName = primaryKeyName(parent);
      rows.add(
          foreignKeyRow(
              catalog,
              schema,
              parent == null ? foreignKey.table() : parent.name(),
              parentColumn,
              foreignKey,
              child.name(),
              catalog,
              schema,
              foreignKeyName(fkNames, group),
              pkName));
    }
    // Order imported keys by parent table for stable results, keeping the engine order within a
    // parent.
    rows.sort(
        Comparator.comparing((List<Object> row) -> (String) row.get(2))
            .thenComparingInt(row -> (Integer) row.get(4)));
    return MetadataRows.of(connection, FOREIGN_KEY_COLUMNS, rows);
  }

  @Override
  public ResultSet getExportedKeys(
      @Nullable String catalog, @Nullable String schema, @Nullable String table)
      throws SQLException {
    TableRef parent = table == null ? null : findTable(table);
    if (parent == null) {
      return emptyForeignKeys();
    }
    List<String> parentKeyColumns = primaryKeyColumns(parent);
    if (parentKeyColumns.isEmpty()) {
      return emptyForeignKeys();
    }
    Set<String> parentKeyLowerCase = new HashSet<>();
    for (String column : parentKeyColumns) {
      parentKeyLowerCase.add(column.toLowerCase(Locale.ROOT));
    }

    List<List<Object>> rows = new ArrayList<>();
    for (TableRef child : userTables()) {
      List<String> fkNames = reversedForeignKeyNames(child.sql());
      int group = -1;
      long previousId = Long.MIN_VALUE;
      for (ForeignKeyRef foreignKey : readForeignKeys(child.name())) {
        if (foreignKey.id() != previousId) {
          group++;
          previousId = foreignKey.id();
        }
        if (!foreignKey.table().equalsIgnoreCase(parent.name())) {
          continue;
        }
        String parentColumn = foreignKey.to();
        if (parentColumn == null || parentColumn.isEmpty()) {
          parentColumn = parentKeyColumns.get(0);
        }
        String pkName = "";
        if (parentKeyLowerCase.contains(parentColumn.toLowerCase(Locale.ROOT))) {
          pkName = primaryKeyName(parent);
        }
        rows.add(
            foreignKeyRow(
                catalog,
                schema,
                parent.name(),
                parentColumn,
                foreignKey,
                child.name(),
                catalog,
                schema,
                foreignKeyName(fkNames, group),
                pkName));
      }
    }
    rows.sort(
        Comparator.comparing((List<Object> row) -> (String) row.get(9))
            .thenComparingInt(row -> ((Number) row.get(4)).intValue()));
    return MetadataRows.of(connection, FOREIGN_KEY_COLUMNS, rows);
  }

  @Override
  public ResultSet getCrossReference(
      @Nullable String parentCatalog,
      @Nullable String parentSchema,
      @Nullable String parentTable,
      @Nullable String foreignCatalog,
      @Nullable String foreignSchema,
      @Nullable String foreignTable)
      throws SQLException {
    if (parentTable == null) {
      return getExportedKeys(foreignCatalog, foreignSchema, foreignTable);
    }
    if (foreignTable == null) {
      return getImportedKeys(parentCatalog, parentSchema, parentTable);
    }
    return emptyForeignKeys();
  }

  @Override
  public ResultSet getTypeInfo() throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    rows.add(typeInfoRow("NULL", Types.NULL, false, true, false));
    rows.add(typeInfoRow("INTEGER", Types.INTEGER, false, false, true));
    rows.add(typeInfoRow("REAL", Types.REAL, false, false, false));
    rows.add(typeInfoRow("TEXT", Types.VARCHAR, true, true, false));
    rows.add(typeInfoRow("BLOB", Types.BLOB, false, true, false));
    return MetadataRows.of(
        connection,
        List.of(
            "TYPE_NAME",
            "DATA_TYPE",
            "PRECISION",
            "LITERAL_PREFIX",
            "LITERAL_SUFFIX",
            "CREATE_PARAMS",
            "NULLABLE",
            "CASE_SENSITIVE",
            "SEARCHABLE",
            "UNSIGNED_ATTRIBUTE",
            "FIXED_PREC_SCALE",
            "AUTO_INCREMENT",
            "LOCAL_TYPE_NAME",
            "MINIMUM_SCALE",
            "MAXIMUM_SCALE",
            "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB",
            "NUM_PREC_RADIX"),
        rows);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getIndexInfo(
      String catalog, String schema, String table, boolean unique, boolean approximate)
      throws SQLException {
    String sql =
        "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, m.name AS TABLE_NAME, "
            + "CASE WHEN il.\"unique\" = 1 THEN 0 ELSE 1 END AS NON_UNIQUE, "
            + "NULL AS INDEX_QUALIFIER, il.name AS INDEX_NAME, 2 AS TYPE, "
            + "ii.seqno + 1 AS ORDINAL_POSITION, ii.name AS COLUMN_NAME, "
            + "'A' AS ASC_OR_DESC, 0 AS CARDINALITY, 0 AS PAGES, NULL AS FILTER_CONDITION "
            + "FROM sqlite_schema m JOIN pragma_index_list(m.name) il "
            + "JOIN pragma_index_info(il.name) ii "
            + "WHERE m.name = ? AND m.type = 'table'"
            + (unique ? " AND il.\"unique\" = 1" : "")
            + " ORDER BY il.name, ii.seqno";
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setString(1, table);
    return metadataQuery(statement);
  }

  @Override
  public boolean supportsResultSetType(int type) {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) {
    return (type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) {
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) {
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) {
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) {
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() {
    // TODO - let's add support for batch updates in the future and let this method return true
    return false;
  }

  @Override
  public ResultSet getUDTs(
      String catalog, String schemaPattern, String typeNamePattern, int[] types)
      throws SQLException {
    if (getUDTs == null || getUDTs.isClosed()) {
      connection.checkOpen();
      getUDTs =
          connection.prepareStatement(
              "select  null as TYPE_CAT, null as TYPE_SCHEM, "
                  + "null as TYPE_NAME,  null as CLASS_NAME,  null as DATA_TYPE, null as REMARKS, "
                  + "null as BASE_TYPE "
                  + "limit 0;");
    }

    getUDTs.clearParameters();
    return metadataQuery(getUDTs);
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public boolean supportsSavepoints() {
    // TODO: return true when turso supports save points
    return false;
  }

  @Override
  public boolean supportsNamedParameters() {
    // TODO: return true when both turso and jdbc supports named parameters
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() {
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() {
    // TODO: check
    return false;
  }

  @Override
  @SkipNullableCheck
  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
      throws SQLException {
    if (getSuperTypes == null || getSuperTypes.isClosed()) {
      connection.checkOpen();
      getSuperTypes =
          connection.prepareStatement(
              "select null as TYPE_CAT, null as TYPE_SCHEM, "
                  + "null as TYPE_NAME, null as SUPERTYPE_CAT, null as SUPERTYPE_SCHEM, "
                  + "null as SUPERTYPE_NAME limit 0;");
    }
    return metadataQuery(getSuperTypes);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {
    if (getSuperTables == null || getSuperTables.isClosed()) {
      connection.checkOpen();
      getSuperTables =
          connection.prepareStatement(
              "select null as TABLE_CAT, null as TABLE_SCHEM, "
                  + "null as TABLE_NAME, null as SUPERTABLE_NAME limit 0;");
    }
    return metadataQuery(getSuperTables);
  }

  @Override
  @SkipNullableCheck
  public ResultSet getAttributes(
      String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
      throws SQLException {
    if (getAttributes == null || getAttributes.isClosed()) {
      connection.checkOpen();
      getAttributes =
          connection.prepareStatement(
              "select null as TYPE_CAT, null as TYPE_SCHEM, "
                  + "null as TYPE_NAME, null as ATTR_NAME, null as DATA_TYPE, "
                  + "null as ATTR_TYPE_NAME, null as ATTR_SIZE, null as DECIMAL_DIGITS, "
                  + "null as NUM_PREC_RADIX, null as NULLABLE, null as REMARKS, null as ATTR_DEF, "
                  + "null as SQL_DATA_TYPE, null as SQL_DATETIME_SUB, null as CHAR_OCTET_LENGTH, "
                  + "null as ORDINAL_POSITION, null as IS_NULLABLE, null as SCOPE_CATALOG, "
                  + "null as SCOPE_SCHEMA, null as SCOPE_TABLE, null as SOURCE_DATA_TYPE limit 0;");
    }

    return metadataQuery(getAttributes);
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) {
    return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getDatabaseMajorVersion() {
    // TODO - important
    return 0;
  }

  @Override
  public int getDatabaseMinorVersion() {
    // TODO - important
    return 0;
  }

  @Override
  public int getJDBCMajorVersion() {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() {
    return 2;
  }

  @Override
  public int getSQLStateType() {
    return DatabaseMetaData.sqlStateSQL99;
  }

  @Override
  public boolean locatorsUpdateCopy() {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() {
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
    if (getSchemas == null || getSchemas.isClosed()) {
      connection.checkOpen();
      getSchemas =
          connection.prepareStatement("select null as TABLE_SCHEM, null as TABLE_CATALOG limit 0;");
    }

    return metadataQuery(getSchemas);
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  @SkipNullableCheck
  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  @SkipNullableCheck
  public ResultSet getFunctionColumns(
      String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
      throws SQLException {
    throw new SQLFeatureNotSupportedException("Not yet implemented by SQLite JDBC driver");
  }

  @Override
  @SkipNullableCheck
  public ResultSet getPseudoColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  @SkipNullableCheck
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return null;
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return false;
  }

  private record PragmaColumn(
      long cid,
      String name,
      @Nullable String type,
      int notNull,
      @Nullable String defaultValue,
      int primaryKey,
      int hidden) {}

  private record TableRef(String name, @Nullable String sql) {}

  private record ForeignKeyRef(
      long id,
      int sequence,
      String table,
      String from,
      @Nullable String to,
      @Nullable String onUpdate,
      @Nullable String onDelete) {}

  private record PrimaryKeyColumn(String name, int sequence) {}

  private ResultSet emptyForeignKeys() throws SQLException {
    return MetadataRows.of(connection, FOREIGN_KEY_COLUMNS, List.of());
  }

  private Map<String, String> loadCreateSql() throws SQLException {
    Map<String, String> createSql = new HashMap<>();
    String sql =
        "SELECT name, sql FROM sqlite_schema "
            + "UNION ALL SELECT name, sql FROM sqlite_temp_master";
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        String name = rows.getString("name");
        if (name != null) {
          createSql.put(name.toLowerCase(Locale.ROOT), rows.getString("sql"));
        }
      }
    }
    return createSql;
  }

  private static Map<String, String> declaredTypes(@Nullable String createSql) {
    Map<String, String> types = new HashMap<>();
    for (SqliteDdl.ColumnDefinition column : SqliteDdl.columns(createSql)) {
      types.put(column.name().toLowerCase(Locale.ROOT), column.declaredType());
    }
    return types;
  }

  private List<PragmaColumn> readColumns(String table, String columnPattern) throws SQLException {
    String sql =
        "SELECT cid, name, type, \"notnull\", dflt_value, pk, hidden "
            + "FROM pragma_table_xinfo(?) WHERE upper(name) LIKE upper(?) ESCAPE '\\' "
            + "ORDER BY cid";
    List<PragmaColumn> columns = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      statement.setString(2, columnPattern);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          columns.add(
              new PragmaColumn(
                  rows.getLong("cid"),
                  rows.getString("name"),
                  rows.getString("type"),
                  rows.getInt("notnull"),
                  rows.getString("dflt_value"),
                  rows.getInt("pk"),
                  rows.getInt("hidden")));
        }
      }
    }
    return columns;
  }

  private List<PragmaColumn> readTableInfo(String table) throws SQLException {
    String sql =
        "SELECT cid, name, type, \"notnull\", dflt_value, pk "
            + "FROM pragma_table_info(?) ORDER BY cid";
    List<PragmaColumn> columns = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          columns.add(
              new PragmaColumn(
                  rows.getLong("cid"),
                  rows.getString("name"),
                  rows.getString("type"),
                  rows.getInt("notnull"),
                  rows.getString("dflt_value"),
                  rows.getInt("pk"),
                  0));
        }
      }
    }
    return columns;
  }

  @Nullable
  private TableRef findTable(String name) throws SQLException {
    String sql =
        "SELECT name, sql FROM sqlite_schema WHERE type='table' AND lower(name)=lower(?) "
            + "UNION ALL SELECT name, sql FROM sqlite_temp_master "
            + "WHERE type='table' AND lower(name)=lower(?) LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, name);
      statement.setString(2, name);
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          return new TableRef(rows.getString("name"), rows.getString("sql"));
        }
      }
    }
    return null;
  }

  private List<TableRef> userTables() throws SQLException {
    String sql =
        "SELECT name, sql FROM sqlite_schema WHERE type='table' "
            + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' "
            + "AND name NOT LIKE '\\_\\_turso\\_internal\\_seq\\_%' ESCAPE '\\'";
    List<TableRef> tables = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        tables.add(new TableRef(rows.getString("name"), rows.getString("sql")));
      }
    }
    return tables;
  }

  private List<ForeignKeyRef> readForeignKeys(String table) throws SQLException {
    String sql =
        "SELECT id, seq, \"table\", \"from\", \"to\", on_update, on_delete "
            + "FROM pragma_foreign_key_list(?)";
    List<ForeignKeyRef> keys = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          keys.add(
              new ForeignKeyRef(
                  rows.getLong("id"),
                  rows.getInt("seq"),
                  rows.getString("table"),
                  rows.getString("from"),
                  rows.getString("to"),
                  rows.getString("on_update"),
                  rows.getString("on_delete")));
        }
      }
    }
    return keys;
  }

  private static List<String> reversedForeignKeyNames(@Nullable String createSql) {
    List<String> names = new ArrayList<>(SqliteDdl.foreignKeyNames(createSql));
    Collections.reverse(names);
    return names;
  }

  private static String foreignKeyName(List<String> names, int group) {
    if (group < 0 || group >= names.size() || names.get(group) == null) {
      return "";
    }
    return names.get(group);
  }

  private List<String> primaryKeyColumns(TableRef table) throws SQLException {
    SqliteDdl.PrimaryKey primaryKey = SqliteDdl.primaryKey(table.sql());
    List<String> columns = new ArrayList<>(primaryKey.columns());
    if (columns.isEmpty()) {
      for (PragmaColumn column : readTableInfo(table.name())) {
        if (column.primaryKey() > 0) {
          columns.add(column.name());
        }
      }
    }
    return columns;
  }

  private static String primaryKeyName(@Nullable TableRef table) {
    if (table == null) {
      return "";
    }
    String name = SqliteDdl.primaryKey(table.sql()).name();
    return name == null ? "" : name;
  }

  private String firstPrimaryKeyColumn(@Nullable TableRef table) throws SQLException {
    if (table == null) {
      return "";
    }
    List<String> columns = primaryKeyColumns(table);
    return columns.isEmpty() ? "" : columns.get(0);
  }

  private static String matchColumnName(String name, List<PragmaColumn> columns) {
    for (PragmaColumn column : columns) {
      if (column.name().equalsIgnoreCase(name)) {
        return column.name();
      }
    }
    return name;
  }

  private static List<PragmaColumn> primaryKeyForBestRow(
      List<PragmaColumn> columns, boolean nullable) {
    List<PragmaColumn> key =
        columns.stream()
            .filter(column -> column.primaryKey() > 0)
            .sorted(Comparator.comparingInt(PragmaColumn::primaryKey))
            .toList();
    if (key.size() == 1 && "INTEGER".equalsIgnoreCase(key.get(0).type())) {
      return key;
    }
    if (nullable || key.stream().allMatch(column -> column.notNull() == 1)) {
      return key;
    }
    return List.of();
  }

  private List<PragmaColumn> uniqueNotNullIndexColumns(String table, List<PragmaColumn> columns)
      throws SQLException {
    Map<Long, PragmaColumn> byCid = new HashMap<>();
    for (PragmaColumn column : columns) {
      byCid.put(column.cid(), column);
    }
    String sql =
        "SELECT il.name AS index_name, ii.cid AS cid FROM pragma_index_list(?) il "
            + "JOIN pragma_index_xinfo(il.name) ii "
            + "WHERE il.\"unique\" = 1 AND il.partial = 0 AND ii.key = 1 "
            + "ORDER BY il.seq, ii.seqno";
    Map<String, List<PragmaColumn>> indexes = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          indexes
              .computeIfAbsent(rows.getString("index_name"), name -> new ArrayList<>())
              .add(byCid.get(rows.getLong("cid")));
        }
      }
    }
    for (List<PragmaColumn> index : indexes.values()) {
      if (index.stream().allMatch(column -> column != null && column.notNull() == 1)) {
        return index;
      }
    }
    return List.of();
  }

  private static List<Object> bestRowColumn(
      String name, @Nullable String declaredType, int pseudoColumn) {
    return Arrays.asList(
        DatabaseMetaData.bestRowSession,
        name,
        TursoTypeMapping.columnType(declaredType),
        TursoTypeMapping.typeName(declaredType),
        TursoTypeMapping.precision(declaredType),
        null,
        TursoTypeMapping.scale(declaredType),
        pseudoColumn);
  }

  private static List<Object> columnRow(
      String table,
      PragmaColumn column,
      @Nullable String declaredType,
      boolean autoincrement,
      int primaryKeyColumns) {
    String upper = declaredType == null ? "" : declaredType.trim().toUpperCase(Locale.ROOT);
    String typeName = upper;
    int columnSize = DEFAULT_COLUMN_SIZE;
    int decimalDigits = 10;
    int open = upper.indexOf('(');
    if (open > 0) {
      int close = upper.lastIndexOf(')');
      if (close > open) {
        typeName = upper.substring(0, open).trim();
        String params = upper.substring(open + 1, close);
        int comma = params.indexOf(',');
        try {
          if (comma >= 0) {
            int precision = Integer.parseInt(params.substring(0, comma).trim());
            int scale = Integer.parseInt(params.substring(comma + 1).trim());
            columnSize = precision + scale;
            decimalDigits = scale;
          } else {
            columnSize = Integer.parseInt(params.trim());
            decimalDigits = 0;
          }
        } catch (NumberFormatException e) {
          // Keep the defaults when the declared size is not numeric.
        }
      }
    }

    int dataType;
    if (upper.contains("INT") || upper.contains("BOOL")) {
      dataType = Types.INTEGER;
      decimalDigits = 0;
    } else if (upper.contains("CHAR")
        || upper.contains("CLOB")
        || upper.contains("TEXT")
        || upper.contains("BLOB")) {
      dataType = Types.VARCHAR;
      decimalDigits = 0;
    } else if (upper.contains("REAL")
        || upper.contains("FLOA")
        || upper.contains("DOUB")
        || upper.contains("DEC")
        || upper.contains("NUM")) {
      dataType = Types.FLOAT;
    } else {
      dataType = Types.VARCHAR;
    }

    int nullable = column.notNull() == 1 ? 0 : 1;
    boolean generated = column.hidden() == 2 || column.hidden() == 3;
    boolean isAutoincrement = autoincrement && primaryKeyColumns == 1 && column.primaryKey() == 1;
    return Arrays.asList(
        null,
        null,
        table,
        column.name(),
        dataType,
        typeName,
        columnSize,
        null,
        decimalDigits,
        10,
        nullable,
        null,
        column.defaultValue(),
        0,
        0,
        0,
        column.cid() + 1,
        nullable == 0 ? "NO" : "YES",
        null,
        null,
        null,
        null,
        isAutoincrement ? "YES" : "NO",
        generated ? "YES" : "NO");
  }

  private static List<Object> typeInfoRow(
      String name, int dataType, boolean caseSensitive, boolean unsigned, boolean autoIncrement) {
    return Arrays.asList(
        name,
        dataType,
        0,
        null,
        null,
        null,
        typeNullable,
        caseSensitive ? 1 : 0,
        typeSearchable,
        unsigned ? 1 : 0,
        0,
        autoIncrement ? 1 : 0,
        null,
        0,
        0,
        0,
        0,
        10);
  }

  private static List<Object> foreignKeyRow(
      @Nullable String pkCatalog,
      @Nullable String pkSchema,
      String pkTable,
      @Nullable String pkColumn,
      ForeignKeyRef foreignKey,
      String fkTable,
      @Nullable String fkCatalog,
      @Nullable String fkSchema,
      String fkName,
      String pkName) {
    return Arrays.asList(
        pkCatalog,
        pkSchema,
        pkTable,
        pkColumn,
        foreignKey.sequence() + 1,
        foreignKeyRule(foreignKey.onUpdate()),
        foreignKeyRule(foreignKey.onDelete()),
        fkCatalog,
        fkSchema,
        fkTable,
        foreignKey.from(),
        fkName,
        pkName,
        importedKeyInitiallyDeferred);
  }

  private static int foreignKeyRule(@Nullable String rule) {
    if (rule == null) {
      return importedKeyNoAction;
    }
    return switch (rule.toUpperCase(Locale.ROOT)) {
      case "CASCADE" -> importedKeyCascade;
      case "SET NULL" -> importedKeySetNull;
      case "SET DEFAULT" -> importedKeySetDefault;
      case "RESTRICT" -> importedKeyRestrict;
      default -> importedKeyNoAction;
    };
  }
}
