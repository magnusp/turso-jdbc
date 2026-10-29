# turso-jdbc

A JDBC driver for embedded [Turso](https://github.com/tursodatabase/turso), backed by Java 25
Panama FFM over Turso's `turso_sdk_kit` C ABI.

The `java.sql` layer is forked from Turso's `bindings/java` JDBC 4 driver. The JNI core is replaced
by a direct FFM core in `io.github.magnusp.turso.core`. There is no Hrana, no libSQL client, and
no JNI.

## Compatibility stance

JDBC 4.3 is the contract. Embedded Turso is the target. The driver follows Turso's engine behavior
and the JDBC specification.

[xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) is a reference implementation and the
source of the selected test tree. It is guidance and steering, not ground truth. A passing test is
evidence that the driver behaves sensibly, not a requirement to reproduce the reference driver's
choices. When a test encodes a SQLite or xerial behavior that conflicts with Turso or with JDBC,
the driver follows Turso and JDBC and records the difference in the quarantine list. The
`xerial-control` run is a reference probe, not a parity gate.

Some metadata values still follow the reference suite because the selected tests assert them, such
as the default column size and the FLOAT mapping for NUMERIC and DECIMAL. Those are compatibility
choices, not JDBC requirements.

## Status

This is a 0.1.0 prototype. It ships Linux x86_64 only and targets Java 25. It implements the part
of JDBC 4.3 that its tests exercise, not the whole API.

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

Connection properties go in the URL query string or in the `Properties` passed to
`DriverManager.getConnection`. A query string value wins over the same key in `Properties`.

- `foreign_keys`: `on` or `off`. Default `off`, the engine default. `on` enforces foreign keys.
  `DatabaseMetaData.supportsIntegrityEnhancementFacility()` returns `true` because the engine
  supports foreign keys. The default stays off, so orphan rows are accepted until `foreign_keys=on`.
- `query_only`: `on` or `off`. Default `off`. `on` rejects writes on this connection.
- `busy_timeout`: milliseconds to wait on a locked database. Default `0`.

Settings apply after the database opens and before any user SQL runs. When a key is absent, the
engine default stays in effect. An invalid value throws `SQLException` with SQLState `22023` that
names the key. Unknown keys are ignored. Everything after the first `?` in the URL is read as
properties, so a database path cannot contain `?`.

```java
DriverManager.getConnection("jdbc:turso:/path/to/app.db?foreign_keys=on&busy_timeout=5000");
```

## Build and test

```sh
mvn test                      # fast fit and smoke suite
mvn verify                    # fast suite plus the reference conformance suite
mvn -Pxerial-control verify   # reference probe with sqlite-jdbc 3.53.4.0
mvn package
scripts/build-native.sh --check
```

The jar is self-contained for Linux x86_64. It contains the stripped `libturso_sdk_kit.so` at
`io/github/magnusp/turso/native/linux-x86_64/libturso_sdk_kit.so` and extracts it to a temporary
file on first use.

`mvn test` runs 117 tests with 0 failures and 0 skips:

- 17 embedded Turso fit tests (`io.github.magnusp.turso.EmbeddedTursoTest`)
- 53 metadata tests (`io.github.magnusp.turso.DatabaseMetaDataTest`)
- 12 multi-statement tests (`io.github.magnusp.turso.MultiStatementTest`)
- 8 parameter metadata tests (`io.github.magnusp.turso.ParameterMetaDataTest`)
- 4 experimental feature tests (`io.github.magnusp.turso.ExperimentalFeatureTest`)
- 3 smoke tests (`io.github.magnusp.turso.SmokeTest`)
- 2 native manifest tests (`io.github.magnusp.turso.NativeManifestTest`)
- 6 stored procedure rejection tests (`io.github.magnusp.turso.CallableStatementUnsupportedTest`)

`mvn verify` adds the conformance source set under `src/conformance`. Failsafe runs 242 tests
with 0 failures, 3 skips, and 18 methods. The report accounts for 260 total
(`total = executed + quarantined`):

- 13 classes from the exported reference test tree
- 6 conformance harness tests (`TursoHarnessTest`)
- 3 quarantine guard tests (`TursoQuarantineTest`)

The three skips are two `@Disabled` methods in the exported tree and one assumption that the
engine build was compiled with `JDBC_EXTENSIONS`.

The `xerial-control` profile is a reference probe. It skips the fast suite and runs the same 13
classes against `org.xerial:sqlite-jdbc:3.53.4.0`: 251 executed, 249 pass, 2 `@Disabled` skips, 0
quarantined, `control=true`. The numbers describe the reference driver, not a target for Turso.

### Reference conformance harness

The test tree is a harness, not a specification. It exists so the same scenarios can run against
this driver and against the reference driver, and so behavior changes stay visible. The driver is
not required to copy a reference result when JDBC or Turso says otherwise.

The 13 test classes and `Utils` come from a pinned export of the reference implementation,
`xerial/sqlite-jdbc` tag `3.53.4.0`, commit
`cab7981c19ce04d691f0675f0b2586afc2bbf803`. `target/generated-test-sources/xerial`
is populated from `upstream-xerial_sqlite-jdbc.manifest`; the committed reference tree is checked
on every build:

```sh
tools/export-upstream --verify
tools/export-upstream --self-test
```

The test-only `TursoConformanceDriver` maps the exported tests' `jdbc:sqlite:` URLs onto embedded
Turso:

- `jdbc:sqlite:` and `jdbc:sqlite::memory:` map to `jdbc:turso::memory:`.
- `file::memory:?cache=shared` and URLs with `mode=memory` map to memory.
- `jdbc:sqlite:<path>` and `jdbc:sqlite:file:<path>` map to a normalized local file.
- `jdbc:sqlite:http://` and `jdbc:sqlite:https://` are rejected.
- Connection `Properties` are passed through. URL pragmas, `shared_cache`, and read-only open
  modes are not applied.

Before discovery, `TursoLauncherSessionListener` registers the shim and `TursoConformanceGuard`
checks the driver name (`turso-jdbc`), product name (`Turso`), URL prefix (`jdbc:turso:`),
non-blank `TursoNative.version()`, and a live `sqlite_version()`. `TursoQuarantine` excludes 17
explicit reference-test methods with a reason, the difference from the reference driver, and an
issue link. The entries cover folded identifier case (#23), two experimental engine schema
features (#19), the deliberate stored-procedure contract (#21), and BIT read as Boolean (#20).
`TursoQuarantineTest` rejects
a blank or stale entry, and the filter is inactive in the reference probe. Pass
`-Dconformance.quarantine.enabled=false` to run the quarantined methods against Turso while
developing. `TursoConformanceReport` writes
`target/conformance-summary.txt` with `control`, total, executed, passed, failed, skipped, and
quarantined counts (`total = executed + quarantined`). Failsafe reports are under
`target/failsafe-reports`.

The `quarantine-ratchet` CI job runs the suite once with the filter off and then
`tools/check-quarantine`, which fails and names the entry to remove when a quarantined method
passes, aborts, or no longer runs. Reproduce the gate locally with:

```sh
mvn -B verify -Dconformance.quarantine.enabled=false -Dmaven.test.failure.ignore=true
tools/check-quarantine
```

The run writes `target/quarantine-ratchet.tsv`, one row per entry. `tools/check-quarantine` prints a
per-status count summary when every row is `failed`, and `tools/check-quarantine --self-test`
exercises the gate on temporary fixtures. All 17 entries still fail as expected (12 for #23, 4 for
#19 and #21, 1 for #20), so the job is green today.

Surefire and Failsafe both pass `--enable-native-access=ALL-UNNAMED`. Use the same flag when
running the conformance tests from an IDE.

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
- No LOB streaming, updatable result sets, XA, or connection pooling.
- No stored procedures or `CallableStatement`. All `prepareCall` overloads throw
  `SQLFeatureNotSupportedException` with SQLState `0A000`. `DatabaseMetaData.getProcedures` and
  `getProcedureColumns` throw the same exception, and `supportsStoredProcedures()` and
  `allProceduresAreCallable()` return `false`.
- `WITHOUT ROWID` tables and stored generated columns are rejected by this engine build. The
  driver reports both as `SQLFeatureNotSupportedException` with SQLState `0A000` instead of a
  parse error. Virtual generated columns work.
- `Statement.executeUpdate` accepts semicolon-separated statements and returns the combined
  affected-row count. `Statement.execute` and `executeQuery` accept one statement. A second
  statement throws `SQLException` with SQLState `42000` before anything runs. Only whitespace,
  semicolons, and comments may follow the first statement.
- The engine folds every identifier to lower case, including quoted names. Metadata reports the
  canonical stored name, so a table created as `MyTable` appears as `mytable`.
- `Connection.setReadOnly` sets `PRAGMA query_only` on that connection. It is a hint against this
  connection, not physical isolation: another connection can still write the file.
- Encryption entry points exist in the core but are untested.
- Metadata is best effort. Column types and precision come from the declared type in
  `sqlite_schema.sql`; expressions fall back to the value's storage class.
- The `turso_sdk_kit` ABI is pre-1.0; the driver checks `turso_version()` only.

## License

MIT. The JDBC layer is derived from Turso, which is MIT. The vendored xerial tests are Apache-2.0
and Zentus BSD. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
