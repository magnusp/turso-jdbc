package tech.turso.core;

import java.sql.SQLException;
import tech.turso.annotations.Nullable;

/** Owns the native Turso database handle for one JDBC database path. */
public final class TursoDB implements AutoCloseable {

  private long dbPointer;

  private final String url;
  private final String filePath;

  public static TursoDB create(String url, String filePath) throws SQLException {
    return new TursoDB(url, filePath, null, null);
  }

  public static TursoDB createWithEncryption(
      String url, String filePath, TursoEncryptionCipher cipher, String hexkey)
      throws SQLException {
    return new TursoDB(url, filePath, cipher, hexkey);
  }

  private TursoDB(
      String url, String filePath, @Nullable TursoEncryptionCipher cipher, @Nullable String hexkey)
      throws SQLException {
    this.url = url;
    this.filePath = filePath;
    TursoNative.ensureLoaded();
    open(cipher, hexkey);
  }

  public synchronized boolean isClosed() {
    return !isOpen();
  }

  public synchronized boolean isOpen() {
    return dbPointer != 0;
  }

  public synchronized long connect() throws SQLException {
    if (!isOpen()) {
      throw new SQLException("database is closed");
    }
    return TursoNative.connect(dbPointer);
  }

  /** Turso's C ABI has no interrupt entry point. */
  public synchronized void interrupt() {
    // no-op
  }

  public synchronized void setBusyTimeout(long millis) throws SQLException {
    if (isOpen()) {
      TursoNative.setBusyTimeout(dbPointer, millis);
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    if (!isOpen()) {
      return;
    }
    long pointer = dbPointer;
    dbPointer = 0;
    TursoNative.deinitDatabase(pointer);
  }

  private void open(@Nullable TursoEncryptionCipher cipher, @Nullable String hexkey)
      throws SQLException {
    String features = cipher == null ? null : "encryption";
    String cipherName = cipher == null ? null : cipher.getValue();
    dbPointer = TursoNative.openDatabase(filePath, features, cipherName, hexkey);
  }

  @Override
  public String toString() {
    return "tursoDB{url='" + url + "', filePath='" + filePath + "', open=" + isOpen() + '}';
  }
}
