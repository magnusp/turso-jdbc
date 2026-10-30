package io.github.magnusp.turso.core;

import io.github.magnusp.turso.TursoSyncStats;
import io.github.magnusp.turso.annotations.Nullable;
import io.github.magnusp.turso.utils.Logger;
import io.github.magnusp.turso.utils.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Locale;

/**
 * One embedded replica: a local Turso file plus a remote sync server.
 *
 * <p>The sync engine performs network and sidecar-file I/O through a queue. Every operation drives
 * that queue on the calling thread, and statement execution on a sync connection serves one queue
 * item per engine IO step. Operations are serialized by this object.
 */
public final class TursoSyncDatabase implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TursoSyncDatabase.class);
  private static final int IO_BUFFER_SIZE = 64 * 1024;
  private static final String USER_AGENT = "turso-jdbc";

  private final String localPath;
  private final String remoteUrl;
  private final @Nullable String authToken;
  private final HttpClient http;
  private final Object lock = new Object();

  private long databasePointer;

  /**
   * Opens or creates the local replica and bootstraps it from the remote when it is empty.
   *
   * @param clientName prefix for the generated sync client id
   * @param longPollTimeoutMs long-poll timeout for pulls, or zero
   * @param bootstrapIfEmpty pull the initial state when the local database is empty
   */
  public static TursoSyncDatabase create(
      String localPath,
      String remoteUrl,
      String clientName,
      long longPollTimeoutMs,
      boolean bootstrapIfEmpty,
      @Nullable String authToken)
      throws SQLException {
    TursoSyncNative.ensureLoaded();
    String normalized = normalizeRemoteUrl(remoteUrl);

    long database;
    try {
      database =
          TursoSyncNative.newDatabase(
              localPath, normalized, clientName, longPollTimeoutMs, bootstrapIfEmpty, null);
    } catch (SQLException e) {
      throw failure(localPath, normalized, "open", e);
    }

    TursoSyncDatabase sync = new TursoSyncDatabase(localPath, normalized, authToken, database);
    long operation = 0;
    try {
      operation = TursoSyncNative.startCreate(database);
      sync.drive(operation);
      return sync;
    } catch (SQLException e) {
      sync.close();
      throw failure(localPath, normalized, "open", e);
    } finally {
      if (operation != 0) {
        TursoSyncNative.deinitOperation(operation);
      }
    }
  }

  private TursoSyncDatabase(
      String localPath, String remoteUrl, @Nullable String authToken, long databasePointer) {
    this.localPath = localPath;
    this.remoteUrl = remoteUrl;
    this.authToken = authToken;
    this.databasePointer = databasePointer;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public String localPath() {
    return localPath;
  }

  public String remoteUrl() {
    return remoteUrl;
  }

  /** Opens a JDBC-usable connection on this replica. */
  public long connect() throws SQLException {
    synchronized (lock) {
      checkOpen();
      long operation = 0;
      try {
        operation = TursoSyncNative.startConnect(databasePointer);
        drive(operation);
        if (TursoSyncNative.resultKind(operation) != TursoSyncNative.RESULT_CONNECTION) {
          throw new SQLException("Turso sync connect returned an unexpected result kind");
        }
        long connection = TursoSyncNative.extractConnection(operation);
        if (connection == 0) {
          throw new SQLException("Turso sync connect returned a null connection");
        }
        return connection;
      } catch (SQLException e) {
        throw failure("connect", e);
      } finally {
        if (operation != 0) {
          TursoSyncNative.deinitOperation(operation);
        }
      }
    }
  }

  /** Sends local changes to the remote. */
  public void push() throws SQLException {
    synchronized (lock) {
      checkOpen();
      runVoid("push", TursoSyncNative::startPush);
    }
  }

  /**
   * Fetches remote changes and applies them locally, rebasing local changes on top.
   *
   * @return true if the remote had changes to apply
   */
  public boolean pull() throws SQLException {
    synchronized (lock) {
      checkOpen();
      long wait = 0;
      try {
        wait = TursoSyncNative.startWaitChanges(databasePointer);
        drive(wait);
        if (TursoSyncNative.resultKind(wait) != TursoSyncNative.RESULT_CHANGES) {
          throw new SQLException("Turso sync pull returned an unexpected result kind");
        }
        long changes = TursoSyncNative.extractChanges(wait);
        TursoSyncNative.deinitOperation(wait);
        wait = 0;
        if (changes == 0) {
          return false;
        }

        long apply = 0;
        boolean consumed = false;
        try {
          apply = TursoSyncNative.startApplyChanges(databasePointer, changes);
          consumed = true;
          drive(apply);
          return true;
        } finally {
          if (!consumed) {
            TursoSyncNative.deinitChanges(changes);
          }
          if (apply != 0) {
            TursoSyncNative.deinitOperation(apply);
          }
        }
      } catch (SQLException e) {
        throw failure("pull", e);
      } finally {
        if (wait != 0) {
          TursoSyncNative.deinitOperation(wait);
        }
      }
    }
  }

  /** Pulls remote changes, then pushes local changes. */
  public void sync() throws SQLException {
    pull();
    push();
  }

  /** Returns the sync engine statistics. */
  public TursoSyncStats stats() throws SQLException {
    synchronized (lock) {
      checkOpen();
      long operation = 0;
      try {
        operation = TursoSyncNative.startStats(databasePointer);
        drive(operation);
        if (TursoSyncNative.resultKind(operation) != TursoSyncNative.RESULT_STATS) {
          throw new SQLException("Turso sync stats returned an unexpected result kind");
        }
        TursoSyncNative.Stats stats = TursoSyncNative.extractStats(operation);
        return new TursoSyncStats(
            stats.cdcOperations(),
            stats.mainWalSize(),
            stats.revertWalSize(),
            stats.lastPullUnixTime(),
            stats.lastPushUnixTime(),
            stats.networkSentBytes(),
            stats.networkReceivedBytes(),
            stats.revision());
      } catch (SQLException e) {
        throw failure("stats", e);
      } finally {
        if (operation != 0) {
          TursoSyncNative.deinitOperation(operation);
        }
      }
    }
  }

  /** Checkpoints the local WAL without changing the remote revision. */
  public void checkpoint() throws SQLException {
    synchronized (lock) {
      checkOpen();
      runVoid("checkpoint", TursoSyncNative::startCheckpoint);
    }
  }

  /** Serves at most one sync IO item. Used while a statement on this replica is stepping. */
  public void processOneIo() throws SQLException {
    synchronized (lock) {
      checkOpen();
      long item = TursoSyncNative.takeIoItem(databasePointer);
      SQLException failure = null;
      try {
        if (item != 0) {
          handleIoItem(item);
        }
      } catch (SQLException e) {
        failure = e;
      } finally {
        if (item != 0) {
          TursoSyncNative.deinitIoItem(item);
        }
      }
      try {
        TursoSyncNative.stepCallbacks(databasePointer);
      } catch (SQLException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
      if (failure != null) {
        throw failure;
      }
    }
  }

  public boolean isClosed() {
    synchronized (lock) {
      return databasePointer == 0;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (databasePointer == 0) {
        return;
      }
      long pointer = databasePointer;
      databasePointer = 0;
      TursoSyncNative.deinitDatabase(pointer);
    }
  }

  private void runVoid(String action, OperationStarter starter) throws SQLException {
    long operation = 0;
    try {
      operation = starter.start(databasePointer);
      drive(operation);
    } catch (SQLException e) {
      throw failure(action, e);
    } finally {
      if (operation != 0) {
        TursoSyncNative.deinitOperation(operation);
      }
    }
  }

  private void drive(long operation) throws SQLException {
    while (true) {
      int status = TursoSyncNative.resume(operation);
      if (status == TursoNative.DONE) {
        return;
      }
      if (status == TursoNative.OK) {
        continue;
      }
      if (status == TursoNative.IO) {
        processIoQueue();
        continue;
      }
      throw new SQLException("Unexpected Turso sync operation status: " + status);
    }
  }

  private void processIoQueue() throws SQLException {
    SQLException failure = null;
    while (true) {
      long item = TursoSyncNative.takeIoItem(databasePointer);
      if (item == 0) {
        break;
      }
      try {
        if (failure == null) {
          handleIoItem(item);
        } else {
          poisonAndComplete(item, failure);
        }
      } catch (SQLException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      } finally {
        TursoSyncNative.deinitIoItem(item);
      }
    }

    try {
      TursoSyncNative.stepCallbacks(databasePointer);
    } catch (SQLException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void handleIoItem(long item) throws SQLException {
    try {
      switch (TursoSyncNative.ioKind(item)) {
        case TursoSyncNative.IO_HTTP -> handleHttp(item);
        case TursoSyncNative.IO_FULL_READ -> handleFullRead(item);
        case TursoSyncNative.IO_FULL_WRITE -> handleFullWrite(item);
        default -> TursoSyncNative.ioDone(item);
      }
    } catch (SQLException e) {
      poisonAndComplete(item, e);
      throw e;
    } catch (RuntimeException e) {
      SQLException wrapped = new SQLException("Turso sync IO failed: " + e, e);
      poisonAndComplete(item, wrapped);
      throw wrapped;
    }
  }

  private void handleHttp(long item) throws SQLException {
    TursoSyncNative.HttpRequest request = TursoSyncNative.ioHttp(item);
    URI uri;
    try {
      uri = URI.create(joinUrl(remoteUrl, request.path()));
    } catch (IllegalArgumentException e) {
      throw new SQLException("Invalid Turso sync request URL: " + remoteUrl + request.path(), e);
    }

    HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
    boolean hasUserAgent = false;
    for (int i = 0; i < request.headerCount(); i++) {
      TursoSyncNative.Header header = TursoSyncNative.ioHttpHeader(item, i);
      if (!header.key().isEmpty() && !restrictedHeader(header.key())) {
        builder.header(header.key(), header.value());
        hasUserAgent |= "user-agent".equalsIgnoreCase(header.key());
      }
    }
    if (authToken != null && !authToken.isBlank()) {
      builder.header("Authorization", "Bearer " + authToken);
    }
    if (!hasUserAgent) {
      builder.header("User-Agent", USER_AGENT);
    }
    if (request.body().length == 0) {
      builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
    } else {
      builder.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
    }

    HttpResponse<InputStream> response;
    try {
      response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      throw new SQLTransientConnectionException(
          "Turso sync request to " + uri + " failed: " + e.getMessage(), "08001", 0, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted while sending a Turso sync request to " + uri, e);
    }

    TursoSyncNative.ioStatus(item, response.statusCode());
    try (InputStream body = response.body()) {
      byte[] buffer = new byte[IO_BUFFER_SIZE];
      int read;
      while ((read = body.read(buffer)) != -1) {
        if (read > 0) {
          TursoSyncNative.ioPushBuffer(item, buffer, read);
        }
      }
    } catch (IOException e) {
      throw new SQLException("Unable to read the Turso sync response from " + uri, e);
    }
    TursoSyncNative.ioDone(item);
  }

  private void handleFullRead(long item) throws SQLException {
    Path file = Path.of(TursoSyncNative.ioFullRead(item));
    if (Files.notExists(file)) {
      // The sync engine treats a missing sidecar file as an empty file.
      TursoSyncNative.ioDone(item);
      return;
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[IO_BUFFER_SIZE];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read > 0) {
          TursoSyncNative.ioPushBuffer(item, buffer, read);
        }
      }
    } catch (IOException e) {
      throw new SQLException("Unable to read the Turso sync file " + file, e);
    }
    TursoSyncNative.ioDone(item);
  }

  private void handleFullWrite(long item) throws SQLException {
    TursoSyncNative.FullWrite write = TursoSyncNative.ioFullWrite(item);
    Path file = Path.of(write.path());
    Path parent = file.getParent();
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    boolean moved = false;
    try {
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (OutputStream output = Files.newOutputStream(temp)) {
        output.write(write.content());
      }
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } catch (IOException e) {
      throw new SQLException("Unable to write the Turso sync file " + file, e);
    } finally {
      if (!moved) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException e) {
          log.warn("Failed to remove temporary sync file " + temp, e);
        }
      }
    }
    TursoSyncNative.ioDone(item);
  }

  private void poisonAndComplete(long item, SQLException failure) {
    String message = failure.getMessage();
    try {
      TursoSyncNative.ioPoison(item, message == null ? "Turso sync IO failed" : message);
    } catch (SQLException e) {
      log.warn("Failed to poison a Turso sync IO request", e);
    }
    try {
      TursoSyncNative.ioDone(item);
    } catch (SQLException e) {
      log.warn("Failed to complete a poisoned Turso sync IO request", e);
    }
  }

  private void checkOpen() throws SQLException {
    if (databasePointer == 0) {
      throw new SQLException("Turso sync database is closed");
    }
  }

  private SQLException failure(String action, SQLException cause) {
    return failure(localPath, remoteUrl, action, cause);
  }

  private static SQLException failure(
      String localPath, String remoteUrl, String action, SQLException cause) {
    String message =
        "Turso sync "
            + action
            + " failed for local "
            + localPath
            + " against "
            + remoteUrl
            + ": "
            + cause.getMessage();
    // TURSO_IOERR is the engine's generic I/O code. Treat it as a connection failure so callers
    // see the URL and the server state instead of a bare file-oriented code.
    if (cause.getErrorCode() == 10 || cause instanceof SQLTransientConnectionException) {
      return new SQLTransientConnectionException(message, "08001", 0, cause);
    }
    return new SQLException(message, cause.getSQLState(), cause.getErrorCode(), cause);
  }

  private static String normalizeRemoteUrl(String remoteUrl) throws SQLException {
    if (remoteUrl == null || remoteUrl.isBlank()) {
      throw new SQLException("sync_url is empty", "22023");
    }
    String trimmed = remoteUrl.trim();
    if (startsWithIgnoreCase(trimmed, "libsql://")) {
      return "https://" + trimmed.substring("libsql://".length());
    }
    if (startsWithIgnoreCase(trimmed, "turso://")) {
      return "https://" + trimmed.substring("turso://".length());
    }
    return trimmed;
  }

  private static boolean startsWithIgnoreCase(String value, String prefix) {
    return value.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  private static String joinUrl(String base, String path) {
    String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return trimmed + (path.startsWith("/") ? path : "/" + path);
  }

  private static boolean restrictedHeader(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "host", "content-length", "connection", "upgrade", "expect" -> true;
      default -> false;
    };
  }

  @Override
  public String toString() {
    return "tursoSyncDatabase{local='" + localPath + "', remote='" + remoteUrl + "'}";
  }

  @FunctionalInterface
  private interface OperationStarter {
    long start(long database) throws SQLException;
  }
}
