# turso-jdbc

A JDBC driver for embedded [Turso](https://github.com/tursodatabase/turso), backed by Java 25
Panama FFM over Turso's `turso_sdk_kit` C ABI.

The `java.sql` layer is forked from Turso's `bindings/java` JDBC 4 driver. The JNI core is replaced
by a direct FFM core in `tech.turso.core`. There is no Hrana, no libSQL client, and no JNI.

## Status

This is a 0.1.0 prototype. It ships Linux x86_64 only and targets Java 25. It implements the JDBC
surface exercised by the test suite, not the whole JDBC 4.3 API.

## Usage

```java
import java.sql.Connection;
import java.sql.DriverManager;

try (Connection connection = DriverManager.getConnection("jdbc:turso:/path/to/app.db")) {
    // ...
}
```

The driver also accepts the libSQL file form:

```java
DriverManager.getConnection("jdbc:libsql:file:/path/to/app.db");
```

`:memory:` is a real in-memory database:

```java
DriverManager.getConnection("jdbc:turso::memory:");
```

Java 25 restricts native access by default. Start the JVM with:

```text
--enable-native-access=ALL-UNNAMED
```

The driver registers itself through `META-INF/services/java.sql.Driver`.

## Build and test

```sh
mvn test
mvn package
```

The jar is self-contained for Linux x86_64. It contains the stripped `libturso_sdk_kit.so` at
`tech/turso/native/linux-x86_64/libturso_sdk_kit.so` and extracts it to a temporary file on first
use.

The test suite currently runs 54 tests with 0 failures and 1 upstream-disabled test:

- 14 embedded Turso fit tests (`tech.turso.EmbeddedTursoTest`)
- 3 smoke tests (`tech.turso.SmokeTest`)
- 21 vendored xerial `ResultSetTest`
- 3 vendored xerial `InsertQueryTest` (1 disabled upstream)
- 13 vendored xerial `RSMetaDataTest`

## Native library

The committed library was built from Turso tag `v0.8.3`, commit
`607bff9fd0e547a25a97b3bf06e83bcc5d99d53c` (`turso_sdk_kit` version `0.8.3`), on Debian
Bookworm (glibc 2.35) and stripped with `strip --strip-all`. It reports `turso_version() = 0.8.3`. Building on a newer distribution can
raise the required glibc version, which breaks older runners and users. Issue #5 tracks a pinned
and verified native build.

Rebuild it with:

```sh
scripts/build-native.sh /path/to/turso-checkout
```

The script runs `cargo build -p turso_sdk_kit --release`, copies the cdylib into the resource
directory, and strips it. The build needs the Rust toolchain pinned by the Turso checkout.

The loader uses `-Dturso.native.path` when set, then `-Dturso.lib.path`, then the resource inside
the jar:

```sh
java --enable-native-access=ALL-UNNAMED \
  -Dturso.native.path=/path/to/libturso_sdk_kit.so \
  -cp turso-jdbc.jar:app.jar com.example.Main
```

The C ABI has no version handshake. Pin the native build and the driver together.

## Architecture

```text
java.sql
  -> tech.turso.jdbc4.*            forked JDBC 4 layer
    -> tech.turso.core.*           FFM core
      -> TursoNative               Panama FFM downcalls
        -> libturso_sdk_kit.so     turso_* C ABI, async_io=0
```

The driver uses `async_io=0` and the default syscall VFS. The engine drives I/O internally, so one
JDBC call maps to one blocking step loop. `async_io=1` only helps with a deferring backend and a
caller that multiplexes statements across threads, which the synchronous JDBC surface does not
provide.

## Known limitations

- Linux x86_64 only.
- No `Statement.cancel()` or query timeout. The C ABI has no interrupt entry point.
- No LOB streaming, updatable result sets, `CallableStatement`, XA, or connection pooling.
- `DatabaseMetaData.getImportedKeys`, `getExportedKeys`, `getCrossReference`, and `getTypeInfo`
  are not implemented.
- Encryption entry points exist in the core but are untested.
- Metadata is best effort. Column types and precision come from the declared type in
  `sqlite_schema.sql`; expressions fall back to the value's storage class.
- The `turso_sdk_kit` ABI is pre-1.0 and has no version handshake.

## License

MIT. The JDBC layer is derived from Turso, which is MIT. The vendored xerial tests are Apache-2.0
and Zentus BSD. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
