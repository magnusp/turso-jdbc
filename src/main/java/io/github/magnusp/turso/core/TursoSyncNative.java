package io.github.magnusp.turso.core;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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

/** Panama FFM bridge over the {@code turso_sync_sdk_kit} C ABI. */
final class TursoSyncNative {

  static final int RESULT_NONE = 0;
  static final int RESULT_CONNECTION = 1;
  static final int RESULT_CHANGES = 2;
  static final int RESULT_STATS = 3;

  static final int IO_NONE = 0;
  static final int IO_HTTP = 1;
  static final int IO_FULL_READ = 2;
  static final int IO_FULL_WRITE = 3;

  private static final String LIB_NAME = "libturso_sync_sdk_kit.so";

  private static final NativeManifest MANIFEST = TursoNative.MANIFEST;

  private static final StructLayout SLICE_REF =
      MemoryLayout.structLayout(ADDRESS.withName("ptr"), JAVA_LONG.withName("len"))
          .withName("turso_slice_ref_t");

  private static final StructLayout SYNC_CONFIG =
      MemoryLayout.structLayout(
              ADDRESS.withName("path"),
              ADDRESS.withName("remote_url"),
              ADDRESS.withName("client_name"),
              JAVA_INT.withName("long_poll_timeout_ms"),
              JAVA_BOOLEAN.withName("bootstrap_if_empty"),
              MemoryLayout.paddingLayout(3),
              JAVA_INT.withName("reserved_bytes"),
              JAVA_INT.withName("partial_bootstrap_strategy_prefix"),
              ADDRESS.withName("partial_bootstrap_strategy_query"),
              JAVA_LONG.withName("partial_bootstrap_segment_size"),
              JAVA_BOOLEAN.withName("partial_bootstrap_prefetch"),
              MemoryLayout.paddingLayout(7),
              ADDRESS.withName("remote_encryption_key"),
              ADDRESS.withName("remote_encryption_cipher"),
              JAVA_LONG.withName("push_operations_threshold"),
              JAVA_LONG.withName("pull_bytes_threshold"),
              JAVA_BOOLEAN.withName("logical_mvcc_pull"),
              MemoryLayout.paddingLayout(7))
          .withName("turso_sync_database_config_t");

  private static final StructLayout HTTP_REQUEST =
      MemoryLayout.structLayout(
              SLICE_REF.withName("url"),
              SLICE_REF.withName("method"),
              SLICE_REF.withName("path"),
              SLICE_REF.withName("body"),
              JAVA_INT.withName("headers"),
              MemoryLayout.paddingLayout(4))
          .withName("turso_sync_io_http_request_t");

  private static final StructLayout HTTP_HEADER =
      MemoryLayout.structLayout(SLICE_REF.withName("key"), SLICE_REF.withName("value"))
          .withName("turso_sync_io_http_header_t");

  private static final StructLayout FULL_READ =
      MemoryLayout.structLayout(SLICE_REF.withName("path"))
          .withName("turso_sync_io_full_read_request_t");

  private static final StructLayout FULL_WRITE =
      MemoryLayout.structLayout(SLICE_REF.withName("path"), SLICE_REF.withName("content"))
          .withName("turso_sync_io_full_write_request_t");

  private static final StructLayout STATS =
      MemoryLayout.structLayout(
              JAVA_LONG.withName("cdc_operations"),
              JAVA_LONG.withName("main_wal_size"),
              JAVA_LONG.withName("revert_wal_size"),
              JAVA_LONG.withName("last_pull_unix_time"),
              JAVA_LONG.withName("last_push_unix_time"),
              JAVA_LONG.withName("network_sent_bytes"),
              JAVA_LONG.withName("network_received_bytes"),
              SLICE_REF.withName("revision"))
          .withName("turso_sync_stats_t");

  private static final long OFF_PATH = offset(SYNC_CONFIG, "path");
  private static final long OFF_REMOTE_URL = offset(SYNC_CONFIG, "remote_url");
  private static final long OFF_CLIENT_NAME = offset(SYNC_CONFIG, "client_name");
  private static final long OFF_LONG_POLL = offset(SYNC_CONFIG, "long_poll_timeout_ms");
  private static final long OFF_BOOTSTRAP = offset(SYNC_CONFIG, "bootstrap_if_empty");

  private static final long OFF_URL = offset(HTTP_REQUEST, "url");
  private static final long OFF_METHOD = offset(HTTP_REQUEST, "method");
  private static final long OFF_REQUEST_PATH = offset(HTTP_REQUEST, "path");
  private static final long OFF_BODY = offset(HTTP_REQUEST, "body");
  private static final long OFF_HEADERS = offset(HTTP_REQUEST, "headers");
  private static final long OFF_HEADER_KEY = offset(HTTP_HEADER, "key");
  private static final long OFF_HEADER_VALUE = offset(HTTP_HEADER, "value");
  private static final long OFF_READ_PATH = offset(FULL_READ, "path");
  private static final long OFF_WRITE_PATH = offset(FULL_WRITE, "path");
  private static final long OFF_WRITE_CONTENT = offset(FULL_WRITE, "content");

  private static final long OFF_CDC_OPERATIONS = offset(STATS, "cdc_operations");
  private static final long OFF_MAIN_WAL_SIZE = offset(STATS, "main_wal_size");
  private static final long OFF_REVERT_WAL_SIZE = offset(STATS, "revert_wal_size");
  private static final long OFF_LAST_PULL = offset(STATS, "last_pull_unix_time");
  private static final long OFF_LAST_PUSH = offset(STATS, "last_push_unix_time");
  private static final long OFF_SENT_BYTES = offset(STATS, "network_sent_bytes");
  private static final long OFF_RECEIVED_BYTES = offset(STATS, "network_received_bytes");
  private static final long OFF_REVISION = offset(STATS, "revision");

  private static final Linker LINKER = Linker.nativeLinker();

  private static final NativeLibrary.Loaded LOADED = load();
  private static final SymbolLookup LOOKUP = LOADED.lookup();

  private static final MethodHandle H_NEW =
      handle(
          "turso_sync_database_new",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_OPEN =
      handle(
          "turso_sync_database_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_CREATE =
      handle(
          "turso_sync_database_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_CONNECT =
      handle(
          "turso_sync_database_connect",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_STATS =
      handle(
          "turso_sync_database_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_CHECKPOINT =
      handle(
          "turso_sync_database_checkpoint",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_PUSH =
      handle(
          "turso_sync_database_push_changes",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_WAIT =
      handle(
          "turso_sync_database_wait_changes",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_APPLY =
      handle(
          "turso_sync_database_apply_changes",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_RESUME =
      handle("turso_sync_operation_resume", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_RESULT_KIND =
      handle("turso_sync_operation_result_kind", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle H_EXTRACT_CONNECTION =
      handle(
          "turso_sync_operation_result_extract_connection",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_EXTRACT_CHANGES =
      handle(
          "turso_sync_operation_result_extract_changes",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_EXTRACT_STATS =
      handle(
          "turso_sync_operation_result_extract_stats",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_TAKE_IO =
      handle(
          "turso_sync_database_io_take_item",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
  private static final MethodHandle H_STEP_CALLBACKS =
      handle(
          "turso_sync_database_io_step_callbacks",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_KIND =
      handle("turso_sync_database_io_request_kind", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle H_IO_HTTP =
      handle(
          "turso_sync_database_io_request_http", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_HTTP_HEADER =
      handle(
          "turso_sync_database_io_request_http_header",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
  private static final MethodHandle H_IO_FULL_READ =
      handle(
          "turso_sync_database_io_request_full_read",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_FULL_WRITE =
      handle(
          "turso_sync_database_io_request_full_write",
          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_POISON =
      handle("turso_sync_database_io_poison", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_STATUS =
      handle("turso_sync_database_io_status", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
  private static final MethodHandle H_IO_PUSH_BUFFER =
      handle(
          "turso_sync_database_io_push_buffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle H_IO_DONE =
      handle("turso_sync_database_io_done", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle H_DB_DEINIT =
      handle("turso_sync_database_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_OP_DEINIT =
      handle("turso_sync_operation_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_IO_ITEM_DEINIT =
      handle("turso_sync_database_io_item_deinit", FunctionDescriptor.ofVoid(ADDRESS));
  private static final MethodHandle H_CHANGES_DEINIT =
      handle("turso_sync_changes_deinit", FunctionDescriptor.ofVoid(ADDRESS));

  static {
    checkOffsets();
  }

  private TursoSyncNative() {}

  /** Forces the class initializer to run and load the sync library. */
  static void ensureLoaded() {
    // The static initializer does the work.
  }

  static long newDatabase(
      String path,
      String remoteUrl,
      String clientName,
      long longPollTimeoutMs,
      boolean bootstrapIfEmpty,
      String features)
      throws SQLException {
    if (remoteUrl == null || remoteUrl.isEmpty()) {
      throw new SQLException("sync remote url is empty");
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment dbConfig = TursoNative.databaseConfig(arena, path, 1L, features);
      MemorySegment syncConfig = arena.allocate(SYNC_CONFIG);
      syncConfig.set(ADDRESS, OFF_PATH, arena.allocateFrom(path));
      syncConfig.set(ADDRESS, OFF_REMOTE_URL, arena.allocateFrom(remoteUrl));
      if (clientName != null) {
        syncConfig.set(ADDRESS, OFF_CLIENT_NAME, arena.allocateFrom(clientName));
      }
      syncConfig.set(JAVA_INT, OFF_LONG_POLL, (int) longPollTimeoutMs);
      syncConfig.set(JAVA_BOOLEAN, OFF_BOOTSTRAP, bootstrapIfEmpty);

      MemorySegment dbSlot = arena.allocate(ADDRESS);
      dbSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = TursoNative.errorSlot(arena);
      int status = (int) H_NEW.invokeExact(dbConfig, syncConfig, dbSlot, err);
      if (status != TursoNative.OK) {
        throw TursoNative.error("turso_sync_database_new", status, err);
      }
      return dbSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to create the Turso sync database", t);
    }
  }

  static long startCreate(long database) throws SQLException {
    return start("turso_sync_database_create", H_CREATE, database);
  }

  static long startOpen(long database) throws SQLException {
    return start("turso_sync_database_open", H_OPEN, database);
  }

  static long startConnect(long database) throws SQLException {
    return start("turso_sync_database_connect", H_CONNECT, database);
  }

  static long startStats(long database) throws SQLException {
    return start("turso_sync_database_stats", H_STATS, database);
  }

  static long startCheckpoint(long database) throws SQLException {
    return start("turso_sync_database_checkpoint", H_CHECKPOINT, database);
  }

  static long startPush(long database) throws SQLException {
    return start("turso_sync_database_push_changes", H_PUSH, database);
  }

  static long startWaitChanges(long database) throws SQLException {
    return start("turso_sync_database_wait_changes", H_WAIT, database);
  }

  static long startApplyChanges(long database, long changes) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment opSlot = arena.allocate(ADDRESS);
      opSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = TursoNative.errorSlot(arena);
      int status =
          (int)
              H_APPLY.invokeExact(
                  MemorySegment.ofAddress(database), MemorySegment.ofAddress(changes), opSlot, err);
      if (status != TursoNative.OK) {
        throw TursoNative.error("turso_sync_database_apply_changes", status, err);
      }
      return opSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to apply Turso sync changes", t);
    }
  }

  private static long start(String operation, MethodHandle handle, long database)
      throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment opSlot = arena.allocate(ADDRESS);
      opSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = TursoNative.errorSlot(arena);
      int status = (int) handle.invokeExact(MemorySegment.ofAddress(database), opSlot, err);
      if (status != TursoNative.OK) {
        throw TursoNative.error(operation, status, err);
      }
      return opSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to start " + operation, t);
    }
  }

  static int resume(long operation) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = TursoNative.errorSlot(arena);
      int status = (int) H_RESUME.invokeExact(MemorySegment.ofAddress(operation), err);
      if (status == TursoNative.OK || status == TursoNative.DONE || status == TursoNative.IO) {
        return status;
      }
      throw TursoNative.error("turso_sync_operation_resume", status, err);
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to resume the Turso sync operation", t);
    }
  }

  static int resultKind(long operation) throws SQLException {
    try {
      return (int) H_RESULT_KIND.invokeExact(MemorySegment.ofAddress(operation));
    } catch (Throwable t) {
      throw new SQLException("Unable to read the Turso sync operation result kind", t);
    }
  }

  static long extractConnection(long operation) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment connSlot = arena.allocate(ADDRESS);
      connSlot.set(ADDRESS, 0, MemorySegment.NULL);
      int status =
          (int) H_EXTRACT_CONNECTION.invokeExact(MemorySegment.ofAddress(operation), connSlot);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_operation_result_extract_connection failed: "
                + TursoNative.statusName(status));
      }
      return connSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to extract the Turso sync connection", t);
    }
  }

  static long extractChanges(long operation) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment changesSlot = arena.allocate(ADDRESS);
      changesSlot.set(ADDRESS, 0, MemorySegment.NULL);
      int status =
          (int) H_EXTRACT_CHANGES.invokeExact(MemorySegment.ofAddress(operation), changesSlot);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_operation_result_extract_changes failed: "
                + TursoNative.statusName(status));
      }
      return changesSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to extract the Turso sync changes", t);
    }
  }

  static Stats extractStats(long operation) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment stats = arena.allocate(STATS);
      int status = (int) H_EXTRACT_STATS.invokeExact(MemorySegment.ofAddress(operation), stats);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_operation_result_extract_stats failed: " + TursoNative.statusName(status));
      }
      return new Stats(
          stats.get(JAVA_LONG, OFF_CDC_OPERATIONS),
          stats.get(JAVA_LONG, OFF_MAIN_WAL_SIZE),
          stats.get(JAVA_LONG, OFF_REVERT_WAL_SIZE),
          stats.get(JAVA_LONG, OFF_LAST_PULL),
          stats.get(JAVA_LONG, OFF_LAST_PUSH),
          stats.get(JAVA_LONG, OFF_SENT_BYTES),
          stats.get(JAVA_LONG, OFF_RECEIVED_BYTES),
          readString(stats, OFF_REVISION));
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to extract the Turso sync statistics", t);
    }
  }

  static long takeIoItem(long database) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment itemSlot = arena.allocate(ADDRESS);
      itemSlot.set(ADDRESS, 0, MemorySegment.NULL);
      MemorySegment err = TursoNative.errorSlot(arena);
      int status = (int) H_TAKE_IO.invokeExact(MemorySegment.ofAddress(database), itemSlot, err);
      if (status != TursoNative.OK) {
        throw TursoNative.error("turso_sync_database_io_take_item", status, err);
      }
      return itemSlot.get(ADDRESS, 0).address();
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to take a Turso sync IO item", t);
    }
  }

  static void stepCallbacks(long database) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment err = TursoNative.errorSlot(arena);
      int status = (int) H_STEP_CALLBACKS.invokeExact(MemorySegment.ofAddress(database), err);
      if (status != TursoNative.OK) {
        throw TursoNative.error("turso_sync_database_io_step_callbacks", status, err);
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to run the Turso sync IO callbacks", t);
    }
  }

  static int ioKind(long item) throws SQLException {
    try {
      return (int) H_IO_KIND.invokeExact(MemorySegment.ofAddress(item));
    } catch (Throwable t) {
      throw new SQLException("Unable to read the Turso sync IO request kind", t);
    }
  }

  static HttpRequest ioHttp(long item) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment request = arena.allocate(HTTP_REQUEST);
      int status = (int) H_IO_HTTP.invokeExact(MemorySegment.ofAddress(item), request);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_request_http failed: " + TursoNative.statusName(status));
      }
      return new HttpRequest(
          readString(request, OFF_URL),
          readString(request, OFF_METHOD),
          readString(request, OFF_REQUEST_PATH),
          readBytes(request, OFF_BODY),
          request.get(JAVA_INT, OFF_HEADERS));
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to read the Turso sync HTTP request", t);
    }
  }

  static Header ioHttpHeader(long item, int index) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment header = arena.allocate(HTTP_HEADER);
      int status =
          (int) H_IO_HTTP_HEADER.invokeExact(MemorySegment.ofAddress(item), (long) index, header);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_request_http_header failed: " + TursoNative.statusName(status));
      }
      return new Header(readString(header, OFF_HEADER_KEY), readString(header, OFF_HEADER_VALUE));
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to read a Turso sync HTTP header", t);
    }
  }

  static String ioFullRead(long item) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment request = arena.allocate(FULL_READ);
      int status = (int) H_IO_FULL_READ.invokeExact(MemorySegment.ofAddress(item), request);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_request_full_read failed: " + TursoNative.statusName(status));
      }
      return readString(request, OFF_READ_PATH);
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to read the Turso sync file read request", t);
    }
  }

  static FullWrite ioFullWrite(long item) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment request = arena.allocate(FULL_WRITE);
      int status = (int) H_IO_FULL_WRITE.invokeExact(MemorySegment.ofAddress(item), request);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_request_full_write failed: " + TursoNative.statusName(status));
      }
      return new FullWrite(
          readString(request, OFF_WRITE_PATH), readBytes(request, OFF_WRITE_CONTENT));
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to read the Turso sync file write request", t);
    }
  }

  static void ioStatus(long item, int status) throws SQLException {
    try {
      int code = (int) H_IO_STATUS.invokeExact(MemorySegment.ofAddress(item), status);
      if (code != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_status failed: " + TursoNative.statusName(code));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to set the Turso sync HTTP status", t);
    }
  }

  static void ioPushBuffer(long item, byte[] bytes, int length) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment slice = slice(arena, bytes, length);
      int status = (int) H_IO_PUSH_BUFFER.invokeExact(MemorySegment.ofAddress(item), slice);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_push_buffer failed: " + TursoNative.statusName(status));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to push a Turso sync IO buffer", t);
    }
  }

  static void ioPoison(long item, String error) throws SQLException {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment slice = slice(arena, error.getBytes(StandardCharsets.UTF_8));
      int status = (int) H_IO_POISON.invokeExact(MemorySegment.ofAddress(item), slice);
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_poison failed: " + TursoNative.statusName(status));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to poison a Turso sync IO request", t);
    }
  }

  static void ioDone(long item) throws SQLException {
    try {
      int status = (int) H_IO_DONE.invokeExact(MemorySegment.ofAddress(item));
      if (status != TursoNative.OK) {
        throw new SQLException(
            "turso_sync_database_io_done failed: " + TursoNative.statusName(status));
      }
    } catch (SQLException e) {
      throw e;
    } catch (Throwable t) {
      throw new SQLException("Unable to complete a Turso sync IO request", t);
    }
  }

  static void deinitDatabase(long database) {
    try {
      H_DB_DEINIT.invokeExact(MemorySegment.ofAddress(database));
    } catch (Throwable ignored) {
      // Deinit is best effort.
    }
  }

  static void deinitOperation(long operation) {
    try {
      H_OP_DEINIT.invokeExact(MemorySegment.ofAddress(operation));
    } catch (Throwable ignored) {
      // Deinit is best effort.
    }
  }

  static void deinitIoItem(long item) {
    try {
      H_IO_ITEM_DEINIT.invokeExact(MemorySegment.ofAddress(item));
    } catch (Throwable ignored) {
      // Deinit is best effort.
    }
  }

  static void deinitChanges(long changes) {
    try {
      H_CHANGES_DEINIT.invokeExact(MemorySegment.ofAddress(changes));
    } catch (Throwable ignored) {
      // Deinit is best effort.
    }
  }

  private static MemorySegment slice(Arena arena, byte[] bytes) {
    return slice(arena, bytes, bytes.length);
  }

  private static MemorySegment slice(Arena arena, byte[] bytes, int length) {
    MemorySegment data = arena.allocate(JAVA_BYTE, Math.max(1, length));
    MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, length);
    MemorySegment slice = arena.allocate(SLICE_REF);
    slice.set(ADDRESS, 0, data);
    slice.set(JAVA_LONG, SLICE_REF.byteSize() - JAVA_LONG.byteSize(), length);
    return slice;
  }

  private static byte[] readBytes(MemorySegment base, long offset) {
    MemorySegment ptr = base.get(ADDRESS, offset);
    long length = base.get(JAVA_LONG, offset + SLICE_REF.byteSize() - JAVA_LONG.byteSize());
    if (length == 0) {
      return new byte[0];
    }
    if (ptr.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("Turso sync returned a null slice with length " + length);
    }
    return ptr.reinterpret(length).toArray(JAVA_BYTE);
  }

  private static String readString(MemorySegment base, long offset) {
    byte[] bytes = readBytes(base, offset);
    return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
  }

  private static long offset(StructLayout layout, String name) {
    return layout.byteOffset(PathElement.groupElement(name));
  }

  private static void checkOffsets() {
    requireOffset("long_poll_timeout_ms", OFF_LONG_POLL, 24);
    requireOffset("bootstrap_if_empty", OFF_BOOTSTRAP, 28);
    requireOffset("reserved_bytes", offset(SYNC_CONFIG, "reserved_bytes"), 32);
    requireOffset(
        "partial_bootstrap_segment_size",
        offset(SYNC_CONFIG, "partial_bootstrap_segment_size"),
        48);
    requireOffset("remote_encryption_key", offset(SYNC_CONFIG, "remote_encryption_key"), 64);
    requireOffset("logical_mvcc_pull", offset(SYNC_CONFIG, "logical_mvcc_pull"), 96);
    requireOffset("headers", OFF_HEADERS, 64);
    requireOffset("revision", OFF_REVISION, 56);
  }

  private static void requireOffset(String field, long actual, long expected) {
    if (actual != expected) {
      throw new ExceptionInInitializerError(
          "Turso sync struct layout mismatch at "
              + field
              + ": offset "
              + actual
              + ", expected "
              + expected);
    }
  }

  private static MethodHandle handle(String name, FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(name)
            .orElseThrow(
                () -> new ExceptionInInitializerError("Missing Turso sync symbol: " + name)),
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
    if (!LIB_NAME.equals(MANIFEST.syncSoFile())) {
      throw new InternalError(
          "Malformed native manifest "
              + NativeManifest.RESOURCE_PATH
              + ": SYNC_SO_FILE="
              + MANIFEST.syncSoFile()
              + " does not match the bundled library "
              + LIB_NAME
              + ".");
    }
    return NativeLibrary.load(
        new NativeLibrary.Spec(
            NativeManifest.NATIVE_DIR + "/" + MANIFEST.syncSoFile(),
            MANIFEST.syncSoFile(),
            MANIFEST.syncSoSize(),
            MANIFEST.syncSoSha256(),
            MANIFEST.syncSoGlibcMax(),
            MANIFEST.buildImage(),
            MANIFEST.buildGlibc(),
            "turso.sync.native.path",
            "turso.lib.path"));
  }

  record HttpRequest(String url, String method, String path, byte[] body, int headerCount) {}

  record Header(String key, String value) {}

  record FullWrite(String path, byte[] content) {}

  record Stats(
      long cdcOperations,
      long mainWalSize,
      long revertWalSize,
      long lastPullUnixTime,
      long lastPushUnixTime,
      long networkSentBytes,
      long networkReceivedBytes,
      String revision) {}
}
