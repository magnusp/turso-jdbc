package tech.turso.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.util.Locale;

/**
 * Panama FFM bridge over the {@code turso_sdk_kit} C ABI.
 *
 * <p>Only the operations needed by the JDBC core are bound. The driver uses {@code async_io=0}, so
 * the engine drives I/O internally and step returns ROW or DONE.
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

  private static final String LIB_RESOURCE =
      "/tech/turso/native/linux-x86_64/libturso_sdk_kit.so";
  private static final String LIB_NAME = "libturso_sdk_kit.so";

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

  private static final long OFF_ASYNC_IO = DB_CONFIG.byteOffset(PathElement.groupElement("async_io"));
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

  private static final SymbolLookup LOOKUP = load();

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
      handle(
          "turso_statement_column_decltype",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
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
      handle(
          "turso_statement_row_value_kind",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BYTES_COUNT =
      handle(
          "turso_statement_row_value_bytes_count",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_BYTES_PTR =
      handle(
          "turso_statement_row_value_bytes_ptr",
          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_ROW_INT =
      handle(
          "turso_statement_row_value_int",
          FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_ROW_DOUBLE =
      handle(
          "turso_statement_row_value_double",
          FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, JAVA_LONG));
  private static final MethodHandle H_LAST_ROWID =
      handle("turso_connection_last_insert_rowid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
  private static final MethodHandle H_BUSY_TIMEOUT =
      handle(
          "turso_connection_set_busy_timeout_ms",
          FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
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

  private TursoNative() {}

  /** Forces the class initializer to run and load the library. */
  public static void ensureLoaded() {
    // The static initializer does the work.
  }

  public static String version() {
    return VERSION;
  }

  public static long openDatabase(
      String path, String features, String cipher, String hexkey) throws SQLException {
    if (path == null || path.isEmpty()) {
      throw new SQLException("database path is empty");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment config = arena.allocate(DB_CONFIG);
      config.set(JAVA_LONG, OFF_ASYNC_IO, 0L);
      config.set(ADDRESS, OFF_PATH, arena.allocateFrom(path));
      if (features != null) {
        config.set(ADDRESS, OFF_FEATURES, arena.allocateFrom(features));
      }
      if (cipher != null) {
        config.set(ADDRESS, OFF_CIPHER, arena.allocateFrom(cipher));
      }
      if (hexkey != null) {
        config.set(ADDRESS, OFF_HEXKEY, arena.allocateFrom(hexkey));
      }
      config.set(JAVA_INT, OFF_OPEN_FLAGS, 0);

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

  /** Returns {@link #ROW} or {@link #DONE}. Throws on an engine error. */
  public static int step(long statement) throws SQLException {
    MemorySegment stmt = MemorySegment.ofAddress(statement);
    for (int hops = 0; hops < 1_000_000; hops++) {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment err = errorSlot(arena);
        int status = (int) H_STEP.invokeExact(stmt, err);
        if (status == ROW || status == DONE) {
          return status;
        }
        if (status == IO) {
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
          (int)
              H_BIND_INT.invokeExact(
                  MemorySegment.ofAddress(statement), (long) position, value);
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
              H_BIND_DOUBLE.invokeExact(
                  MemorySegment.ofAddress(statement), (long) position, value);
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
                  MemorySegment.ofAddress(statement),
                  (long) position,
                  data,
                  (long) bytes.length);
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
                  MemorySegment.ofAddress(statement),
                  (long) position,
                  data,
                  (long) value.length);
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
      case CONSTRAINT ->
          new SQLIntegrityConstraintViolationException(message, "23000", 19);
      case BUSY, BUSY_SNAPSHOT ->
          new SQLTransientConnectionException(message, "08001", 5);
      case ERROR ->
          lower.contains("syntax") || lower.contains("near")
              ? new SQLSyntaxErrorException(message, "42000", 1)
              : new SQLException(message, "HY000", 1);
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

  private static MemorySegment errorSlot(Arena arena) {
    MemorySegment err = arena.allocate(ADDRESS);
    err.set(ADDRESS, 0, MemorySegment.NULL);
    return err;
  }

  private static String statusName(int status) {
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

  private static SymbolLookup load() {
    Path path;
    String explicit = System.getProperty("turso.native.path");
    String directory = System.getProperty("turso.lib.path");
    if (explicit != null && !explicit.isBlank()) {
      path = Path.of(explicit).toAbsolutePath();
    } else if (directory != null && !directory.isBlank()) {
      path = Path.of(directory).resolve(LIB_NAME).toAbsolutePath();
    } else {
      path = extractLibrary();
    }
    return SymbolLookup.libraryLookup(path, Arena.global());
  }

  private static Path extractLibrary() {
    checkPlatform();
    try (InputStream input = TursoNative.class.getResourceAsStream(LIB_RESOURCE)) {
      if (input == null) {
        throw new InternalError(
            "Missing native library resource "
                + LIB_RESOURCE
                + ". Build it with scripts/build-native.sh or set -Dturso.native.path.");
      }
      Path temp = Files.createTempFile("libturso_sdk_kit-", ".so");
      Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      temp.toFile().deleteOnExit();
      temp.toFile().setReadable(true, true);
      temp.toFile().setWritable(true, true);
      temp.toFile().setExecutable(true, true);
      if (Files.size(temp) == 0) {
        throw new IOException("extracted " + LIB_NAME + " is empty");
      }
      return temp.toAbsolutePath();
    } catch (IOException e) {
      throw new InternalError("Unable to extract " + LIB_NAME, e);
    }
  }

  private static void checkPlatform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    boolean linux = os.contains("linux");
    boolean x86 = arch.contains("x86_64") || arch.contains("amd64");
    if (!linux || !x86) {
      throw new UnsupportedOperationException(
          "This build of turso-jdbc ships only Linux x86_64. Detected "
              + os
              + "/"
              + arch
              + ".");
    }
  }

  private static String readVersion() {
    try {
      MemorySegment ptr = (MemorySegment) H_VERSION.invokeExact();
      return ptr.reinterpret(Long.MAX_VALUE).getString(0);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(t);
    }
  }
}
