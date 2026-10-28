# turso-jdbc

A JDBC driver for embedded [Turso](https://github.com/tursodatabase/turso), backed by Java 25
Panama FFM over Turso's `turso_sdk_kit` C ABI.

The `java.sql` layer is forked from Turso's `bindings/java` JDBC 4 driver. The JNI core is replaced
by a direct FFM core in `io.github.magnusp.turso.core`. There is no Hrana, no libSQL client, and
no JNI.

## Status

This is a 0.1.0 prototype. It ships Linux x86_64 only and targets Java 25. It implements the JDBC
surface exercised by the test suite, not the whole JDBC 4.3 API.

## Usage

```java
import java.sql.Connection;
import java.sql.DriverManager;

Class.forName("io.github.magnusp.turso.JDBC");

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
scripts/build-native.sh --check
```

The jar is self-contained for Linux x86_64. It contains the stripped `libturso_sdk_kit.so` at
`io/github/magnusp/turso/native/linux-x86_64/libturso_sdk_kit.so` and extracts it to a temporary
file on first use.

The test suite currently runs 56 tests with 0 failures and 1 upstream-disabled test:

- 14 embedded Turso fit tests (`io.github.magnusp.turso.EmbeddedTursoTest`)
- 3 smoke tests (`io.github.magnusp.turso.SmokeTest`)
- 2 native manifest tests (`io.github.magnusp.turso.NativeManifestTest`)
- 21 vendored xerial `ResultSetTest`
- 3 vendored xerial `InsertQueryTest` (1 disabled upstream)
- 13 vendored xerial `RSMetaDataTest`

## Native library

The jar bundles a stripped Linux x86_64 `libturso_sdk_kit.so`. Its identity is recorded next to
the binary in
`src/main/resources/io/github/magnusp/turso/native/linux-x86_64/native.properties`.
That manifest is the single source of truth for the Turso commit, `turso_sdk_kit` version, size,
SHA-256, glibc ceiling, build image, and Rust version. Do not duplicate those values in Java, CI,
or this README.

The committed build requires glibc 2.35 or newer; 2.35 is the minimum supported glibc. It was
built on Debian 12 (Bookworm, glibc 2.36) with Rust 1.88.0 and stripped with
`strip --strip-all`. Building on a newer distribution can raise the required glibc version and
break older runners and users.

`TursoNative` loads the manifest from the classpath before it looks up the library. At class
initialization it always compares `turso_version()` with `TURSO_SDK_KIT_VERSION`. For the
bundled resource it also checks `SO_SIZE` and `SO_SHA256` while extracting, and deletes the
temporary file when either differs. For `-Dturso.native.path` or `-Dturso.lib.path` it always
checks the version, and checks size and SHA-256 only when `-Dturso.native.verifyHash=true`.
A mismatch fails fast with the expected and actual values instead of failing later at query time.

### Verifying and rebuilding

```sh
scripts/build-native.sh --check
scripts/build-native.sh /path/to/turso-checkout
scripts/build-native.sh --update /path/to/turso-checkout
scripts/build-native.sh --update --allow-glibc-bump /path/to/turso-checkout
```

`--check` verifies the committed `.so` against the manifest without building. It prints the
version, SHA-256, and glibc ceiling. The default build mode checks the checkout, builds into a
staging directory, strips, and verifies the version, size, SHA-256, and highest `GLIBC_*` symbol.
It installs the result only when it matches the manifest exactly; a non-identical build leaves
the committed binary untouched. `--update` installs the fresh binary and atomically rewrites the
manifest. Raising `SO_GLIBC_MAX` requires `--allow-glibc-bump`.

Every build requires a Turso checkout at `TURSO_COMMIT` with a clean worktree. The script builds
from inside the checkout so Turso's `rust-toolchain.toml` is honored, and it checks the
`sdk-kit/turso.h` hash and the Rust version from the manifest. Use a build environment with glibc
at or below the manifest ceiling; the current pin is built on Debian 12.

To upgrade the pin:

1. Check out the new Turso commit.
2. Set `TURSO_COMMIT` in `native.properties` to that commit. If the checkout's
   `rust-toolchain.toml` requires a different compiler, set `RUST_VERSION` as well.
3. Run `scripts/build-native.sh --update /path/to/turso-checkout` from the pinned build image.
   Add `--allow-glibc-bump` only when the new binary intentionally requires a newer glibc.
4. Run `scripts/build-native.sh --check` and `mvn test`.

### Overriding the bundled library

The loader uses `-Dturso.native.path` when set, then `-Dturso.lib.path`, then the resource inside
the jar:

```sh
java --enable-native-access=ALL-UNNAMED \
  -Dturso.native.path=/path/to/libturso_sdk_kit.so \
  -cp turso-jdbc.jar:app.jar com.example.Main
```

Add `-Dturso.native.verifyHash=true` to also check the explicit library's size and SHA-256
against the manifest. The C ABI has no handshake beyond `turso_version()`; pin the native build
and the driver together.

## Architecture

```text
java.sql
  -> io.github.magnusp.turso.jdbc4.*   forked JDBC 4 layer
    -> io.github.magnusp.turso.core.*  FFM core
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
- The `turso_sdk_kit` ABI is pre-1.0; the driver checks `turso_version()` only.

## License

MIT. The JDBC layer is derived from Turso, which is MIT. The vendored xerial tests are Apache-2.0
and Zentus BSD. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
