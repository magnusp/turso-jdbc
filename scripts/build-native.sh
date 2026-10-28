#!/usr/bin/env bash
set -euo pipefail

TURSO="${1:-${TURSO_CHECKOUT:-../turso}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/src/main/resources/io/github/magnusp/turso/native/linux-x86_64"

if [[ ! -f "$TURSO/Cargo.toml" ]]; then
  echo "usage: $0 /path/to/turso-checkout" >&2
  exit 2
fi

# The committed Linux x86_64 binary targets glibc 2.35 (Debian Bookworm). Building on a newer
# distribution raises the required glibc version. Issue #5 tracks a pinned native build.
mkdir -p "$OUT"
cargo build -p turso_sdk_kit --release --manifest-path "$TURSO/Cargo.toml"
cp "$TURSO/target/release/libturso_sdk_kit.so" "$OUT/libturso_sdk_kit.so"
strip --strip-all "$OUT/libturso_sdk_kit.so"

echo "built $OUT/libturso_sdk_kit.so"
ls -lh "$OUT/libturso_sdk_kit.so"
sha256sum "$OUT/libturso_sdk_kit.so"
