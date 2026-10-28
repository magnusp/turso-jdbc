#!/usr/bin/env bash
set -euo pipefail

TURSO="${1:-${TURSO_CHECKOUT:-../turso}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/src/main/resources/tech/turso/native/linux-x86_64"

if [[ ! -f "$TURSO/Cargo.toml" ]]; then
  echo "usage: $0 /path/to/turso-checkout" >&2
  exit 2
fi

mkdir -p "$OUT"
cargo build -p turso_sdk_kit --release --manifest-path "$TURSO/Cargo.toml"
cp "$TURSO/target/release/libturso_sdk_kit.so" "$OUT/libturso_sdk_kit.so"
strip --strip-all "$OUT/libturso_sdk_kit.so"

echo "built $OUT/libturso_sdk_kit.so"
ls -lh "$OUT/libturso_sdk_kit.so"
sha256sum "$OUT/libturso_sdk_kit.so"
