package io.github.magnusp.turso.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.Locale;

/**
 * Panama FFM bridge over the {@code turso_sdk_kit} C ABI.
 *
 * <p>Embedded connections use {@code async_io=0}, so the engine drives I/O internally and step
 * returns ROW or DONE. Sync connections use {@code async_io=1} and hand each IO step to an {@link
 * IoDriver}, which serves the sync engine's IO queue before the statement is resumed.
 */
public final class TursoNative {

  public static final int OK = 0;
  public static final int DONE = 1;
  public static final int ROW = 2;
  public static final int IO = 3;
  public static final int BUSY = 4;
  public static final int INTERRUPT = 5;
  public static final int BUSY_SNAPSHOT = 6;
  public static final int ERROR = 127;
  public static final int MISUSE = 128;
  public static final int CONSTRAINT = 129;
  public static final int READONLY = 130;
  public static final int DATABASE_FULL = 131;
  public static final int NOTADB = 132;
  public static final int CORRUPT = 133;
  public static final int IOERR = 134;

  private static final int TYPE_INTEGER = 1;
  private static final int TYPE_REAL = 2;
  private static final int TYPE_TEXT = 3;
  private static final int TYPE_BLOB = 4;
  private static final int TYPE_NULL = 5;

  private static final String LIB_NAME = "libturso_sdk_kit.so";

  static final NativeManifest MANIFEST = NativeManifest.load();

  private static final StructLayout DB_CONFIG =
      MemoryLayout.structLayout(
              JAVA_LONG.withName("async_io"),
              ADDRESS.withName("path"),
              ADDRESS.withName("experimental_features"),
              ADDRESS.withName("vfs"),
              ADDRESS.withName("encryption_cipher"),
              ADDRESS.withName("encryption_hexkey"),
              ADDRESS.withName("page_codec"),
              JAVA_INT.withName("open_flags"),
              MemoryLayout.paddingLayout(4))
          .withName("turso_database_config_t");

  private static final long OFF_ASYNC_IO =
      DB_CONFIG.byteOffset(PathElement.groupElement("async_io"));
  private static final long OFF_PATH = DB_CONFIG.byteOffset(PathElement.groupElement("path"));
  private static final long OFF_FEATURES =
      DB_CONFIG.byteOffset(PathElement.groupElement("experimental_features"));
  private static final long OFF_CIPHER =
      DB_CONFIG.byteOffset(PathElement.groupElement("encryption_cipher"));
  private static final long OFF_HEXKEY =
      DB_CONFIG.byteOffset(PathElement.groupElement("encryption_hexkey"));
  private static final long OFF_OPEN_FLAGS =
      DB_CONFIG.byteOffset(PathElement.groupElement("open_flags"));

  private static final Linker LINKER = Linker.nativeLinker();

  private static final NativeLibrary.Loaded LOADED = load();
  private static final SymbolLookup LOOKUP = LOADED.lookup();

  private static final MethodHandle H_VERSION =
      handle("turso_version", FunctionDescriptor.of(ADDRESS));
  private static final MethodHandle H_DB_NEW =
      handle("turso_database_new", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_DB_OPEN =
      handle("turso_database_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_CONNECT =
      handle("turso_database_connect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_PREPARE =
      handle(
          "turso_connection_prepare_single",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_PREPARE_FIRST =
      handle(
          "turso_connection_prepare_first",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_STEP =
      handle("turso_statement_step", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_RUN_IO =
      handle("turso_statement_run_io", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_FINALIZE =
      handle("turso_statement_finalize", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_RESET =
      handle("turso_statement_reset", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_COL_COUNT =
      handle("turso_statement_column_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle H_COL_NAME =
      handle("turso_statement_column_name", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_COL_DECLTYPE =
      handle("turso_statement_column_decltype", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_N_CHANGE =
      handle("turso_statement_n_change", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle H_PARAM_COUNT =
      handle("turso_statement_parameters_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle H_BIND_NULL =
      handle(
          "turso_statement_bind_positional_null",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BIND_INT =
      handle(
          "turso_statement_bind_positional_int",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
  private static final MethodHandle H_BIND_DOUBLE =
      handle(
          "turso_statement_bind_positional_double",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_DOUBLE));
  private static final MethodHandle H_BIND_TEXT =
      handle(
          "turso_statement_bind_positional_text",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BIND_BLOB =
      handle(
          "turso_statement_bind_positional_blob",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_ROW_KIND =
      handle("turso_statement_row_value_kind", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BYTES_COUNT =
      handle(
          "turso_statement_row_value_bytes_count",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BYTES_PTR =
      handle(
          "turso_statement_row_value_bytes_ptr",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_ROW_INT =
      handle("turso_statement_row_value_int", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_ROW_DOUBLE =
      handle(
          "turso_statement_row_value_double",
          FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_LAST_ROWID =
      handle("turso_connection_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle H_BUSY_TIMEOUT =
      handle("turso_connection_set_busy_timeout_ms", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
  private static final MethodHandle H_AUTOCOMMIT =
      handle("turso_connection_get_autocommit", FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
  private static final MethodHandle H_STR_DEINIT =
      handle("turso_str_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_CONN_DEINIT =
      handle("turso_connection_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_DB_DEINIT =
      handle("turso_database_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_STMT_DEINIT =
      handle("turso_statement_deinit", FunctionDescriptor.ofVoid(ADDRESS));

  private static final String VERSION = readVersion();

  static {
    String expected = MANIFEST.tursoSdkKitVersion();
    if (!expected.equals(VERSION)) {
      throw new ExceptionInInitializerError(
          "Turso native version mismatch: "
              + NativeManifest.RESOURCE_PATH
              + " expects TURSO_SDK_KIT_VERSION="
              + expected
              + " but "
              + LOADED.path()
              + " reports turso_version()="
              + VERSION
              + ". Replace the native library or adopt the new pin with"
              + " scripts/build-native.sh --update.");
    }
  }

  private TursoNative() {}

  /** Forces the class initializer to run and load the library. */
  public static void ensureLoaded() {
    // The static initializer does the work.
  }

  public static String version() {
    return VERSION;
  }

  public static long openDatabase(String path, String features, String cipher, String hexkey)
      throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment config = databaseConfig(arena, path, 0L, features);
      if (cipher != null) {
        config.set(ADDRESS, OFF_CIPHER, arena.allocateFrom(cipher));
      }
      if (hexkey != null) {
        config.set(ADDRESS, OFF_HEXKEY, arena.allocateFrom(hexkey));
      }

      MemorySegment dbSlot = arena.allocate(ADDRESS);
      dbSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = errorSlot(arena);

      int status = (int) H_DB_NEW.invokeExact(config, dbSlot, err);
      if (status != OK) {
        throw error("turso_database_new", status, err);
      }
      MemorySegment db = dbSlot.get(ADDRESS, 0);

      status = (int) H_DB_OPEN.invokeExact(db, err);
      while (status == IO) {
        err.set(ADDRESS, 0, MemorySegment.NULL);
        status = (int) H_DB_OPEN.invokeExact(db, err);
      }
      if (status != OK) {
        deinitDatabase(db.address());
        throw error("turso_database_open", status, err);
      }
      return db.address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to open Turso database", t);
    }
  }

  /**
   * Allocates and fills a {@code turso_database_config_t}.
   *
   * @param asyncIo 0 for engine-driven IO, 1 when the caller serves the sync IO queue
   */
  static MemorySegment databaseConfig(Arena arena, String path, long asyncIo, String features)
      throws SQLException {
    if (path == null || path.isEmpty()) {
      throw new SQLException("database path is empty");
    }
    MemorySegment config = arena.allocate(DB_CONFIG);
    config.set(JAVA_LONG, OFF_ASYNC_IO, asyncIo);
    config.set(ADDRESS, OFF_PATH, arena.allocateFrom(path));
    if (features != null) {
      config.set(ADDRESS, OFF_FEATURES, arena.allocateFrom(features));
    }
    config.set(JAVA_INT, OFF_OPEN_FLAGS, 0);
    return config;
  }

  public static long connect(long database) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment connSlot = arena.allocate(ADDRESS);
      connSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = errorSlot(arena);
      int status = (int) H_CONNECT.invokeExact(MemorySegment.ofAddress(database), connSlot, err);
      if (status != OK) {
        throw error("turso_database_connect", status, err);
      }
      return connSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to connect to Turso database", t);
    }
  }

  public static long prepare(long connection, String sql) throws SQLException {
    if (sql == null) {
      throw new SQLException("SQL is null");
    }
    if (sql.indexOf('\0') >= 0) {
      throw new SQLException("SQL contains a NUL character");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment stmtSlot = arena.allocate(ADDRESS);
      stmtSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = errorSlot(arena);
      int status =
          (int)
              H_PREPARE.invokeExact(
                  MemorySegment.ofAddress(connection), arena.allocateFrom(sql), stmtSlot, err);
      if (status != OK) {
        throw error("turso_connection_prepare_single", status, err);
      }
      return stmtSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to prepare Turso statement", t);
    }
  }

  /**
   * Prepares the first statement in {@code sql}.
   *
   * <p>{@code tail} is the char offset in {@code sql} immediately after the parsed statement.
   * {@code statement} is zero when nothing parseable remains.
   */
  public static PreparedFirst prepareFirst(long connection, String sql) throws SQLException {
    if (sql == null) {
      throw new SQLException("SQL is null");
    }
    if (sql.indexOf('\0') >= 0) {
      throw new SQLException("SQL contains a NUL character");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment stmtSlot = arena.allocate(ADDRESS);
      stmtSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment tailSlot = arena.allocate(JAVA_LONG);
      tailSlot.set(JAVA_LONG, 0, 0L);
      MemorySegment err = errorSlot(arena);
      int status =
          (int)
              H_PREPARE_FIRST.invokeExact(
                  MemorySegment.ofAddress(connection),
                  arena.allocateFrom(sql),
                  stmtSlot,
                  tailSlot,
                  err);
      if (status != OK) {
        throw error("turso_connection_prepare_first", status, err);
      }
      long statement = stmtSlot.get(ADDRESS, 0).address();
      if (statement == 0) {
        return new PreparedFirst(0, 0);
      }
      return new PreparedFirst(statement, tailChars(sql, tailSlot.get(JAVA_LONG, 0)));
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to prepare Turso statement", t);
    }
  }

  private static int tailChars(String sql, long tailBytes) throws SQLException {
    byte[] utf8 = sql.getBytes(StandardCharsets.UTF_8);
    if (tailBytes < 0 || tailBytes > utf8.length) {
      throw new SQLException(
          "turso_connection_prepare_first returned tail "
              + tailBytes
              + " for SQL of "
              + utf8.length
              + " bytes");
    }
    return new String(utf8, 0, (int) tailBytes, StandardCharsets.UTF_8).length();
  }

  /** A statement parsed from the head of a SQL string, plus the char offset of its tail. */
  public record PreparedFirst(long statement, int tail) {}

  /** Returns {@link #ROW} or {@link #DONE}. Throws on an engine error. */
  /** Serves one engine IO step, for example the sync engine's IO queue. */
  @FunctionalInterface
  public interface IoDriver {
    void run() throws SQLException;
  }

  public static int step(long statement) throws SQLException {
    return step(statement, null);
  }

  public static int step(long statement, IoDriver ioDriver) throws SQLException {
    MemorySegment stmt = MemorySegment.ofAddress(statement);
    for (int hops = 0; hops < 1_000_000; hops++) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment err = errorSlot(arena);
        int status = (int) H_STEP.invokeExact(stmt, err);
        if (status == ROW || status == DONE) {
          return status;
        }
        if (status == IO) {
          if (ioDriver != null) {
            ioDriver.run();
          }
          int ioStatus = (int) H_RUN_IO.invokeExact(stmt, err);
          if (ioStatus != OK) {
            throw error("turso_statement_run_io", ioStatus, err);
          }
          continue;
        }
        throw error("turso_statement_step", status, err);
      } catch (SQLException e) {
        throw e;
      } catch (Throwable t) {
        throw new SQLException("Unable to step Turso statement", t);
      }
    }
    throw new SQLException("turso_statement_step IO loop exceeded the bound");
  }

  public static void finalizeStatement(long statement) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment stmt = MemorySegment.ofAddress(statement);
      MemorySegment err = errorSlot(arena);
      int status = (int) H_FINALIZE.invokeExact(stmt, err);
      if (status == IO) {
        int ioStatus = (int) H_RUN_IO.invokeExact(stmt, err);
        if (ioStatus != OK) {
          throw error("turso_statement_run_io", ioStatus, err);
        }
        err.set(ADDRESS, 0, MemorySegment.NULL);
        status = (int) H_FINALIZE.invokeExact(stmt, err);
      }
      if (status != DONE && status != OK) {
        throw error("turso_statement_finalize", status, err);
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to finalize Turso statement", t);
    }
  }

  public static void deinitStatement(long statement) throws SQLException {
    try {
      H_STMT_DEINIT.invokeExact(MemorySegment.ofAddress(statement));
    } catch (Throwable t) {
      throw new SQLException("Unable to deinitialize Turso statement", t);
    }
  }

  public static void reset(long statement) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = errorSlot(arena);
      int status = (int) H_RESET.invokeExact(MemorySegment.ofAddress(statement), err);
      if (status != OK) {
        throw error("turso_statement_reset", status, err);
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to reset Turso statement", t);
    }
  }

  public static int columnCount(long statement) throws SQLException {
    try {
      return (int) (long) H_COL_COUNT.invokeExact(MemorySegment.ofAddress(statement));
    } catch (Throwable t) {
      throw new SQLException("Unable to read Turso column count", t);
    }
  }

  public static String[] columnNames(long statement) throws SQLException {
    int count = columnCount(statement);
    String[] names = new String[count];
    for (int i = 0; i < count; i++) {
      names[i] = ownedString("turso_statement_column_name", H_COL_NAME, statement, i);
    }
    return names;
  }

  public static String[] columnDeclaredTypes(long statement) throws SQLException {
    int count = columnCount(statement);
    String[] types = new String[count];
    for (int i = 0; i < count; i++) {
      types[i] = ownedString("turso_statement_column_decltype", H_COL_DECLTYPE, statement, i);
    }
    return types;
  }

  public static long changes(long statement) throws SQLException {
    try {
      return (long) H_N_CHANGE.invokeExact(MemorySegment.ofAddress(statement));
    } catch (Throwable t) {
      throw new SQLException("Unable to read Turso change count", t);
    }
  }

  public static int parameterCount(long statement) throws SQLException {
    try {
      return (int) (long) H_PARAM_COUNT.invokeExact(MemorySegment.ofAddress(statement));
    } catch (Throwable t) {
      throw new SQLException("Unable to read Turso parameter count", t);
    }
  }

  public static void bindNull(long statement, int position) throws SQLException {
    int status;
    try {
      status = (int) H_BIND_NULL.invokeExact(MemorySegment.ofAddress(statement), (long) position);
    } catch (Throwable t) {
      throw new SQLException("Unable to bind NULL", t);
    }
    if (status != OK) {
      throw new SQLException("bind_positional_null failed: " + statusName(status));
    }
  }

  public static void bindLong(long statement, int position, long value) throws SQLException {
    int status;
    try {
      status =
          (int) H_BIND_INT.invokeExact(MemorySegment.ofAddress(statement), (long) position, value);
    } catch (Throwable t) {
      throw new SQLException("Unable to bind long", t);
    }
    if (status != OK) {
      throw new SQLException("bind_positional_int failed: " + statusName(status));
    }
  }

  public static void bindDouble(long statement, int position, double value) throws SQLException {
    int status;
    try {
      status =
          (int)
              H_BIND_DOUBLE.invokeExact(MemorySegment.ofAddress(statement), (long) position, value);
    } catch (Throwable t) {
      throw new SQLException("Unable to bind double", t);
    }
    if (status != OK) {
      throw new SQLException("bind_positional_double failed: " + statusName(status));
    }
  }

  public static void bindText(long statement, int position, String value) throws SQLException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment data = arena.allocate(Math.max(1, bytes.length));
      if (bytes.length > 0) {
        MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
      }
      int status =
          (int)
              H_BIND_TEXT.invokeExact(
                  MemorySegment.ofAddress(statement), (long) position, data, (long) bytes.length);
      if (status != OK) {
        throw new SQLException("bind_positional_text failed: " + statusName(status));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to bind text", t);
    }
  }

  public static void bindBlob(long statement, int position, byte[] value) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment data = arena.allocate(Math.max(1, value.length));
      if (value.length > 0) {
        MemorySegment.copy(value, 0, data, JAVA_BYTE, 0, value.length);
      }
      int status =
          (int)
              H_BIND_BLOB.invokeExact(
                  MemorySegment.ofAddress(statement), (long) position, data, (long) value.length);
      if (status != OK) {
        throw new SQLException("bind_positional_blob failed: " + statusName(status));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to bind blob", t);
    }
  }

  public static Object[] row(long statement, int columnCount) throws SQLException {
    MemorySegment stmt = MemorySegment.ofAddress(statement);
    Object[] values = new Object[columnCount];
    try {
      for (int i = 0; i < columnCount; i++) {
        int kind = (int) H_ROW_KIND.invokeExact(stmt, (long) i);
        switch (kind) {
          case TYPE_INTEGER:
            values[i] = (long) H_ROW_INT.invokeExact(stmt, (long) i);
            break;
          case TYPE_REAL:
            values[i] = (double) H_ROW_DOUBLE.invokeExact(stmt, (long) i);
            break;
          case TYPE_TEXT:
            values[i] = readText(stmt, i);
            break;
          case TYPE_BLOB:
            values[i] = readBlob(stmt, i);
            break;
          case TYPE_NULL:
          default:
            values[i] = null;
            break;
        }
      }
    } catch (Throwable t) {
      throw new SQLException("Unable to read Turso row", t);
    }
    return values;
  }

  public static long lastInsertRowid(long connection) throws SQLException {
    try {
      return (long) H_LAST_ROWID.invokeExact(MemorySegment.ofAddress(connection));
    } catch (Throwable t) {
      throw new SQLException("Unable to read last insert rowid", t);
    }
  }

  public static boolean autocommit(long connection) throws SQLException {
    try {
      return (boolean) H_AUTOCOMMIT.invokeExact(MemorySegment.ofAddress(connection));
    } catch (Throwable t) {
      throw new SQLException("Unable to read autocommit", t);
    }
  }

  public static void setBusyTimeout(long connection, long millis) throws SQLException {
    try {
      H_BUSY_TIMEOUT.invokeExact(MemorySegment.ofAddress(connection), millis);
    } catch (Throwable t) {
      throw new SQLException("Unable to set busy timeout", t);
    }
  }

  public static void deinitConnection(long connection) throws SQLException {
    try {
      H_CONN_DEINIT.invokeExact(MemorySegment.ofAddress(connection));
    } catch (Throwable t) {
      throw new SQLException("Unable to deinitialize Turso connection", t);
    }
  }

  public static void deinitDatabase(long database) throws SQLException {
    try {
      H_DB_DEINIT.invokeExact(MemorySegment.ofAddress(database));
    } catch (Throwable t) {
      throw new SQLException("Unable to deinitialize Turso database", t);
    }
  }

  public static SQLException error(String operation, int status, MemorySegment errSlot)
      throws SQLException {
    String detail = takeError(errSlot);
    String message = operation + " failed: " + statusName(status);
    if (detail != null && !detail.isEmpty()) {
      message += ": " + detail;
    }
    String lower = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
    return switch (status) {
      case CONSTRAINT -> new SQLIntegrityConstraintViolationException(message, "23000", 19);
      case BUSY, BUSY_SNAPSHOT -> new SQLTransientConnectionException(message, "08001", 5);
      case ERROR -> errorDetail(message, lower);
      case MISUSE -> new SQLException(message, "HY000", 21);
      case READONLY -> new SQLException(message, "HY000", 8);
      case DATABASE_FULL -> new SQLException(message, "HY000", 13);
      case NOTADB -> new SQLException(message, "HY000", 26);
      case CORRUPT -> new SQLException(message, "HY000", 11);
      case IOERR -> new SQLException(message, "HY000", 10);
      case INTERRUPT -> new SQLTransientException(message, "57014");
      default -> new SQLException(message, "HY000", status & 0xff);
    };
  }

  private static SQLException errorDetail(String message, String lowerDetail) {
    if (lowerDetail.contains("without rowid tables are an experimental feature")
        || lowerDetail.contains("stored generated columns are not supported")) {
      return new SQLFeatureNotSupportedException(message, "0A000");
    }
    return lowerDetail.contains("syntax") || lowerDetail.contains("near")
        ? new SQLSyntaxErrorException(message, "42000", 1)
        : new SQLException(message, "HY000", 1);
  }

  private static String readText(MemorySegment stmt, int index) throws Throwable {
    long length = (long) H_BYTES_COUNT.invokeExact(stmt, (long) index);
    if (length < 0) {
      return null;
    }
    if (length == 0) {
      return "";
    }
    MemorySegment ptr = (MemorySegment) H_BYTES_PTR.invokeExact(stmt, (long) index);
    return new String(ptr.reinterpret(length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
  }

  private static byte[] readBlob(MemorySegment stmt, int index) throws Throwable {
    long length = (long) H_BYTES_COUNT.invokeExact(stmt, (long) index);
    if (length < 0) {
      return null;
    }
    if (length == 0) {
      return new byte[0];
    }
    MemorySegment ptr = (MemorySegment) H_BYTES_PTR.invokeExact(stmt, (long) index);
    return ptr.reinterpret(length).toArray(JAVA_BYTE);
  }

  private static String ownedString(
      String operation, MethodHandle handle, long statement, int index) throws SQLException {
    try {
      MemorySegment ptr =
          (MemorySegment) handle.invokeExact(MemorySegment.ofAddress(statement), (long) index);
      if (ptr.equals(MemorySegment.NULL)) {
        return null;
      }
      String value;
      try {
        value = ptr.reinterpret(Long.MAX_VALUE).getString(0);
      } finally {
        H_STR_DEINIT.invokeExact(ptr);
      }
      return value;
    } catch (Throwable t) {
      throw new SQLException("Unable to read " + operation, t);
    }
  }

  private static String takeError(MemorySegment errSlot) {
    MemorySegment ptr = errSlot.get(ADDRESS, 0);
    if (ptr.equals(MemorySegment.NULL)) {
      return null;
    }
    try {
      return ptr.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      return null;
    } finally {
      try {
        H_STR_DEINIT.invokeExact(ptr);
      } catch (Throwable ignored) {
        // best effort
      }
      errSlot.set(ADDRESS, 0, MemorySegment.NULL);
    }
  }

  static MemorySegment errorSlot(Arena arena) {
    MemorySegment err = arena.allocate(ADDRESS);
    err.set(ADDRESS, 0, MemorySegment.NULL);
    return err;
  }

  static String statusName(int status) {
    return switch (status) {
      case OK -> "TURSO_OK(0)";
      case DONE -> "TURSO_DONE(1)";
      case ROW -> "TURSO_ROW(2)";
      case IO -> "TURSO_IO(3)";
      case BUSY -> "TURSO_BUSY(4)";
      case INTERRUPT -> "TURSO_INTERRUPT(5)";
      case BUSY_SNAPSHOT -> "TURSO_BUSY_SNAPSHOT(6)";
      case ERROR -> "TURSO_ERROR(127)";
      case MISUSE -> "TURSO_MISUSE(128)";
      case CONSTRAINT -> "TURSO_CONSTRAINT(129)";
      case READONLY -> "TURSO_READONLY(130)";
      case DATABASE_FULL -> "TURSO_DATABASE_FULL(131)";
      case NOTADB -> "TURSO_NOTADB(132)";
      case CORRUPT -> "TURSO_CORRUPT(133)";
      case IOERR -> "TURSO_IOERR(134)";
      default -> "UNKNOWN(" + status + ")";
    };
  }

  private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(name)
            .orElseThrow(() -> new ExceptionInInitializerError("Missing Turso symbol: " + name)),
        descriptor);
  }

  private static NativeLibrary.Loaded load() {
    if (!"linux-x86_64".equals(MANIFEST.platform())) {
      throw new InternalError(
          "Unsupported native manifest "
              + NativeManifest.RESOURCE_PATH
              + ": PLATFORM="
              + MANIFEST.platform()
              + ", expected linux-x86_64.");
    }
    if (!LIB_NAME.equals(MANIFEST.soFile())) {
      throw new InternalError(
          "Malformed native manifest "
              + NativeManifest.RESOURCE_PATH
              + ": SO_FILE="
              + MANIFEST.soFile()
              + " does not match the bundled library "
              + LIB_NAME
              + ".");
    }
    return NativeLibrary.load(
        new NativeLibrary.Spec(
            NativeManifest.NATIVE_DIR + "/" + MANIFEST.soFile(),
            MANIFEST.soFile(),
            MANIFEST.soSize(),
            MANIFEST.soSha256(),
            MANIFEST.soGlibcMax(),
            MANIFEST.buildImage(),
            MANIFEST.buildGlibc(),
            "turso.native.path",
            "turso.lib.path"));
  }

  private static String readVersion() {
    try {
      MemorySegment ptr = (MemorySegment) H_VERSION.invokeExact();
      if (ptr.equals(MemorySegment.NULL)) {
        throw new IllegalStateException("turso_version() returned NULL");
      }
      return ptr.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
