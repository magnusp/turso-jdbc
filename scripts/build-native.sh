#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_DIR="$ROOT/src/main/resources/io/github/magnusp/turso/native/linux-x86_64"
MANIFEST="$NATIVE_DIR/native.properties"
DEST="$NATIVE_DIR/libturso_sdk_kit.so"
SYNC_DEST="$NATIVE_DIR/libturso_sync_sdk_kit.so"

MODE=build
ALLOW_GLIBC_BUMP=0
TURSO_ARG=""

usage() {
  cat <<'EOF'
usage: scripts/build-native.sh [--check | --update [--allow-glibc-bump]] [TURSO_CHECKOUT]

  (no mode)              Build turso_sdk_kit and turso_sync_sdk_kit, verify them against the
                         manifest, and install them only when the results are bit-for-bit identical
                         to the committed binaries.
  --check                Verify the committed .so files against the manifest. Does not build.
  --update               Build, install, and atomically rewrite the manifest with the fresh
                         versions, sizes, SHA-256s, glibc ceilings, and build metadata.
  --allow-glibc-bump     Permit --update to raise SO_GLIBC_MAX.
  TURSO_CHECKOUT         Path to the Turso git checkout. Required for build and --update. It must
                         be at TURSO_COMMIT and clean. May also be supplied via TURSO_CHECKOUT.
EOF
}

fail() {
  echo "error: $*" >&2
  exit 1
}

while (($#)); do
  case "$1" in
    --check)
      [[ "$MODE" == build ]] || fail "--check and --update are mutually exclusive"
      MODE=check
      ;;
    --update)
      [[ "$MODE" == build ]] || fail "--check and --update are mutually exclusive"
      MODE=update
      ;;
    --allow-glibc-bump)
      ALLOW_GLIBC_BUMP=1
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    --*)
      echo "error: unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      [[ -z "$TURSO_ARG" ]] || fail "only one Turso checkout may be given"
      TURSO_ARG="$1"
      ;;
  esac
  shift
done

if ((ALLOW_GLIBC_BUMP)) && [[ "$MODE" != update ]]; then
  fail "--allow-glibc-bump is only valid with --update"
fi

TURSO="${TURSO_ARG:-${TURSO_CHECKOUT:-}}"

command -v objdump >/dev/null 2>&1 || fail "objdump is required (binutils)"
command -v nm >/dev/null 2>&1 || fail "nm is required (binutils)"
command -v python3 >/dev/null 2>&1 || fail "python3 is required to call turso_version()"

[[ -f "$MANIFEST" ]] || fail "missing manifest $MANIFEST"

manifest_get() {
  local key="$1"
  local value
  value="$(sed -n "s/^${key}=//p" "$MANIFEST" | head -n 1 | tr -d '\r')"
  [[ -n "$value" ]] || fail "manifest $MANIFEST is missing $key"
  printf '%s' "$value"
}

PLATFORM="$(manifest_get PLATFORM)"
TURSO_COMMIT="$(manifest_get TURSO_COMMIT)"
TURSO_SDK_KIT_VERSION="$(manifest_get TURSO_SDK_KIT_VERSION)"
SO_FILE="$(manifest_get SO_FILE)"
SO_SIZE="$(manifest_get SO_SIZE)"
SO_SHA256="$(manifest_get SO_SHA256)"
SO_GLIBC_MAX="$(manifest_get SO_GLIBC_MAX)"
SYNC_SO_FILE="$(manifest_get SYNC_SO_FILE)"
SYNC_SO_SIZE="$(manifest_get SYNC_SO_SIZE)"
SYNC_SO_SHA256="$(manifest_get SYNC_SO_SHA256)"
SYNC_SO_GLIBC_MAX="$(manifest_get SYNC_SO_GLIBC_MAX)"
BUILD_IMAGE="$(manifest_get BUILD_IMAGE)"
BUILD_GLIBC="$(manifest_get BUILD_GLIBC)"
RUST_VERSION="$(manifest_get RUST_VERSION)"
TURSO_HEADER_SHA256="$(manifest_get TURSO_HEADER_SHA256)"
TURSO_SYNC_HEADER_SHA256="$(manifest_get TURSO_SYNC_HEADER_SHA256)"

[[ "$PLATFORM" == "linux-x86_64" ]] || fail "PLATFORM must be linux-x86_64"
[[ "$TURSO_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail "TURSO_COMMIT must be a 40-character git SHA-1"
[[ "$SO_FILE" == "libturso_sdk_kit.so" ]] || fail "SO_FILE must be libturso_sdk_kit.so"
[[ "$SO_SIZE" =~ ^[0-9]+$ ]] || fail "SO_SIZE must be a byte count"
[[ "$SO_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "SO_SHA256 must be a lower-case SHA-256"
[[ "$TURSO_HEADER_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "TURSO_HEADER_SHA256 must be a lower-case SHA-256"
[[ "$SO_GLIBC_MAX" =~ ^[0-9]+\.[0-9]+$ ]] || fail "SO_GLIBC_MAX must look like 2.35"
[[ "$SYNC_SO_FILE" == "libturso_sync_sdk_kit.so" ]] || fail "SYNC_SO_FILE must be libturso_sync_sdk_kit.so"
[[ "$SYNC_SO_SIZE" =~ ^[0-9]+$ ]] || fail "SYNC_SO_SIZE must be a byte count"
[[ "$SYNC_SO_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "SYNC_SO_SHA256 must be a lower-case SHA-256"
[[ "$TURSO_SYNC_HEADER_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail "TURSO_SYNC_HEADER_SHA256 must be a lower-case SHA-256"
[[ "$SYNC_SO_GLIBC_MAX" =~ ^[0-9]+\.[0-9]+$ ]] || fail "SYNC_SO_GLIBC_MAX must look like 2.35"
[[ "$BUILD_GLIBC" =~ ^[0-9]+\.[0-9]+$ ]] || fail "BUILD_GLIBC must look like 2.36"

file_size() {
  if stat -c %s "$1" >/dev/null 2>&1; then
    stat -c %s "$1"
  else
    stat -f %z "$1"
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

glibc_max() {
  local max
  max="$(objdump -T "$1" | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sed 's/^GLIBC_//' | sort -V | tail -n 1 || true)"
  [[ -n "$max" ]] || fail "no versioned GLIBC symbols found in $1"
  printf '%s' "$max"
}

turso_version() {
  python3 - "$1" <<'PY'
import ctypes
import sys

lib = ctypes.CDLL(sys.argv[1])
lib.turso_version.restype = ctypes.c_char_p
version = lib.turso_version()
if not version:
    raise SystemExit("turso_version() returned NULL")
print(version.decode("utf-8"))
PY
}

version_gt() {
  [[ "$1" != "$2" ]] || return 1
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

verify_committed() {
  verify_committed_library "embedded" "$DEST" "$SO_SIZE" "$SO_SHA256" "$SO_GLIBC_MAX" "$TURSO_SDK_KIT_VERSION"
  verify_committed_library "sync" "$SYNC_DEST" "$SYNC_SO_SIZE" "$SYNC_SO_SHA256" "$SYNC_SO_GLIBC_MAX" ""
}

verify_committed_library() {
  local label="$1" file="$2" size="$3" hash="$4" glibc="$5" expected_version="$6"

  [[ -f "$file" ]] || fail "missing committed $label native library $file"

  local actual_size actual_hash actual_version="" actual_glibc
  actual_size="$(file_size "$file")"
  actual_hash="$(sha256_file "$file")"
  actual_glibc="$(glibc_max "$file")"
  if [[ -n "$expected_version" ]]; then
    actual_version="$(turso_version "$file")"
  fi

  printf '%s library: %s\n' "$label" "$file"
  printf '  size:      %s (expected %s)\n' "$actual_size" "$size"
  printf '  sha256:    %s (expected %s)\n' "$actual_hash" "$hash"
  if [[ -n "$expected_version" ]]; then
    printf '  version:   %s (expected %s)\n' "$actual_version" "$expected_version"
  fi
  printf '  glibc max: %s (expected %s)\n' "$actual_glibc" "$glibc"

  [[ "$actual_size" == "$size" ]] || fail "committed $label .so size $actual_size does not match the manifest ($size)"
  [[ "$actual_hash" == "$hash" ]] || fail "committed $label .so SHA-256 $actual_hash does not match the manifest ($hash)"
  [[ "$actual_glibc" == "$glibc" ]] || fail "committed $label .so highest GLIBC symbol $actual_glibc does not match the manifest ($glibc)"
  if [[ -n "$expected_version" ]]; then
    [[ "$actual_version" == "$expected_version" ]] ||
      fail "turso_version()=$actual_version does not match TURSO_SDK_KIT_VERSION=$expected_version"
  else
    nm -D "$file" | grep -q ' turso_sync_database_new$' ||
      fail "committed $label .so does not export turso_sync_database_new"
  fi
  printf '\n'
}

verify_checkout_identity() {
  local checkout="$1"
  [[ -d "$checkout" ]] || fail "Turso checkout not found at $checkout"
  [[ -f "$checkout/Cargo.toml" ]] || fail "$checkout does not look like the Turso checkout"

  local head dirty header header_hash sync_header sync_header_hash
  head="$(git -C "$checkout" rev-parse HEAD 2>/dev/null)" || fail "$checkout is not a git checkout"
  [[ "$head" == "$TURSO_COMMIT" ]] ||
    fail "Turso checkout HEAD $head does not match manifest TURSO_COMMIT=$TURSO_COMMIT"

  dirty="$(git -C "$checkout" status --porcelain)"
  [[ -z "$dirty" ]] || fail "Turso checkout $checkout is dirty; commit, stash, or clean it first"

  header="$checkout/sdk-kit/turso.h"
  sync_header="$checkout/sync/sdk-kit/turso_sync.h"
  [[ -f "$header" ]] || fail "missing $header"
  [[ -f "$sync_header" ]] || fail "missing $sync_header"
  if [[ "$MODE" != update ]]; then
    header_hash="$(sha256_file "$header")"
    [[ "$header_hash" == "$TURSO_HEADER_SHA256" ]] ||
      fail "sdk-kit/turso.h SHA-256 $header_hash does not match TURSO_HEADER_SHA256=$TURSO_HEADER_SHA256"
    sync_header_hash="$(sha256_file "$sync_header")"
    [[ "$sync_header_hash" == "$TURSO_SYNC_HEADER_SHA256" ]] ||
      fail "sync/sdk-kit/turso_sync.h SHA-256 $sync_header_hash does not match TURSO_SYNC_HEADER_SHA256=$TURSO_SYNC_HEADER_SHA256"
  fi
}

verify_toolchain() {
  local checkout="$1"
  local version
  version="$(cd "$checkout" && rustc --version 2>/dev/null | awk '{print $2}')" ||
    fail "rustc is not available; install the Rust toolchain pinned by the checkout"
  [[ "$version" == "$RUST_VERSION" ]] ||
    fail "rustc $version does not match manifest RUST_VERSION=$RUST_VERSION; install the pinned toolchain (rustup honors rust-toolchain.toml from inside the checkout)"
}

detect_build_image() {
  local id version
  if [[ -r /etc/os-release ]]; then
    id="$(sed -n 's/^ID=//p' /etc/os-release | tr -d '"' | head -n 1)"
    version="$(sed -n 's/^VERSION_ID=//p' /etc/os-release | tr -d '"' | head -n 1)"
    if [[ -n "$id" && -n "$version" ]]; then
      printf '%s:%s' "$id" "$version"
      return
    fi
  fi
  printf 'unknown'
}

detect_build_glibc() {
  local version
  version="$(getconf GNU_LIBC_VERSION 2>/dev/null | awk '{print $2}')"
  if [[ -z "$version" ]]; then
    version="$(ldd --version 2>/dev/null | head -n 1 | grep -oE '[0-9]+\.[0-9]+$' || true)"
  fi
  printf '%s' "$version"
}

install_binary() {
  local source="$1" dest="$2" file="$3"
  local tmp="$NATIVE_DIR/.$file.tmp.$$"
  install -m 0755 "$source" "$tmp"
  mv -f "$tmp" "$dest"
}

write_manifest() {
  local version="$1" size="$2" hash="$3" glibc="$4"
  local sync_size="$5" sync_hash="$6" sync_glibc="$7"
  local image="$8" image_glibc="$9" rust="${10}" header_hash="${11}" sync_header_hash="${12}"
  local tmp="$MANIFEST.tmp.$$"
  {
    cat <<'EOF'
# Identity of the native libraries in this directory.
#
# This file is the single source of truth for the native pin. TursoNative and TursoSyncNative
# read it from the classpath before loading a library, and scripts/build-native.sh verifies the
# committed binaries against it. Do not duplicate these values in Java, CI, or docs.
#
# Format: KEY=VALUE, one per line. No quoting, escaping, inline comments, or trailing spaces.
EOF
    printf 'PLATFORM=%s\n' "$PLATFORM"
    printf 'TURSO_COMMIT=%s\n' "$TURSO_COMMIT"
    printf 'TURSO_SDK_KIT_VERSION=%s\n' "$version"
    printf 'SO_FILE=%s\n' "$SO_FILE"
    printf 'SO_SIZE=%s\n' "$size"
    printf 'SO_SHA256=%s\n' "$hash"
    printf 'SO_GLIBC_MAX=%s\n' "$glibc"
    printf 'SYNC_SO_FILE=%s\n' "$SYNC_SO_FILE"
    printf 'SYNC_SO_SIZE=%s\n' "$sync_size"
    printf 'SYNC_SO_SHA256=%s\n' "$sync_hash"
    printf 'SYNC_SO_GLIBC_MAX=%s\n' "$sync_glibc"
    printf 'BUILD_IMAGE=%s\n' "$image"
    printf 'BUILD_GLIBC=%s\n' "$image_glibc"
    printf 'RUST_VERSION=%s\n' "$rust"
    printf 'TURSO_HEADER_SHA256=%s\n' "$header_hash"
    printf 'TURSO_SYNC_HEADER_SHA256=%s\n' "$sync_header_hash"
  } >"$tmp"
  mv -f "$tmp" "$MANIFEST"
}

check_glibc_ceiling() {
  local label="$1" actual="$2" ceiling="$3"
  if version_gt "$actual" "$ceiling"; then
    if [[ "$MODE" == update && $ALLOW_GLIBC_BUMP -eq 1 ]]; then
      echo "  $label glibc ceiling raised: $ceiling -> $actual (allowed)"
    else
      fail "fresh $label build requires glibc $actual but manifest ceiling is $ceiling; rebuild on $BUILD_IMAGE, or pass --update --allow-glibc-bump for an intentional bump"
    fi
  elif [[ "$actual" != "$ceiling" ]]; then
    echo "  $label glibc ceiling lowered: $ceiling -> $actual"
  fi
}

build_and_install() {
  local checkout="$1"
  verify_checkout_identity "$checkout"
  verify_toolchain "$checkout"

  echo "building turso_sdk_kit and turso_sync_sdk_kit from $checkout at $TURSO_COMMIT"
  local target_dir="${CARGO_TARGET_DIR:-$checkout/target}"
  [[ "$target_dir" == /* ]] || target_dir="$checkout/$target_dir"
  (cd "$checkout" && cargo build -p turso_sdk_kit -p turso_sync_sdk_kit --release)

  local built="$target_dir/release/$SO_FILE"
  local sync_built="$target_dir/release/$SYNC_SO_FILE"
  [[ -f "$built" ]] || fail "build did not produce $built"
  [[ -f "$sync_built" ]] || fail "build did not produce $sync_built"

  STAGING="$(mktemp -d "${TMPDIR:-/tmp}/turso-native.XXXXXX")"
  trap 'rm -rf "$STAGING"' EXIT
  STAGED="$STAGING/$SO_FILE"
  SYNC_STAGED="$STAGING/$SYNC_SO_FILE"
  cp "$built" "$STAGED"
  cp "$sync_built" "$SYNC_STAGED"
  strip --strip-all "$STAGED"
  strip --strip-all "$SYNC_STAGED"

  local actual_size actual_hash actual_version actual_glibc
  actual_size="$(file_size "$STAGED")"
  actual_hash="$(sha256_file "$STAGED")"
  actual_version="$(turso_version "$STAGED")"
  actual_glibc="$(glibc_max "$STAGED")"

  local sync_actual_size sync_actual_hash sync_actual_glibc
  sync_actual_size="$(file_size "$SYNC_STAGED")"
  sync_actual_hash="$(sha256_file "$SYNC_STAGED")"
  sync_actual_glibc="$(glibc_max "$SYNC_STAGED")"

  local rust_version header_hash sync_header_hash
  rust_version="$(cd "$checkout" && rustc --version | awk '{print $2}')"
  header_hash="$(sha256_file "$checkout/sdk-kit/turso.h")"
  sync_header_hash="$(sha256_file "$checkout/sync/sdk-kit/turso_sync.h")"

  local build_image build_glibc
  build_image="$(detect_build_image)"
  build_glibc="$(detect_build_glibc)"
  [[ "$build_glibc" =~ ^[0-9]+\.[0-9]+$ ]] || fail "unable to detect the build environment glibc"

  echo "fresh native libraries:"
  printf '  embedded size:      %s\n' "$actual_size"
  printf '  embedded sha256:    %s\n' "$actual_hash"
  printf '  embedded version:   %s\n' "$actual_version"
  printf '  embedded glibc max: %s\n' "$actual_glibc"
  printf '  sync size:          %s\n' "$sync_actual_size"
  printf '  sync sha256:        %s\n' "$sync_actual_hash"
  printf '  sync glibc max:     %s\n' "$sync_actual_glibc"
  printf '  built on:           %s (glibc %s, rustc %s)\n' "$build_image" "$build_glibc" "$rust_version"

  if [[ "$actual_version" != "$TURSO_SDK_KIT_VERSION" ]]; then
    if [[ "$MODE" == update ]]; then
      echo "  version changes: $TURSO_SDK_KIT_VERSION -> $actual_version"
    else
      fail "fresh build reports turso_version()=$actual_version but manifest TURSO_SDK_KIT_VERSION=$TURSO_SDK_KIT_VERSION; run --update to adopt it"
    fi
  fi

  check_glibc_ceiling "embedded" "$actual_glibc" "$SO_GLIBC_MAX"
  check_glibc_ceiling "sync" "$sync_actual_glibc" "$SYNC_SO_GLIBC_MAX"

  if [[ "$MODE" == build ]]; then
    [[ "$actual_size" == "$SO_SIZE" ]] ||
      fail "fresh embedded build size $actual_size does not match manifest SO_SIZE=$SO_SIZE; the committed binary was left untouched (use --update to adopt a different build)"
    [[ "$actual_hash" == "$SO_SHA256" ]] ||
      fail "fresh embedded build SHA-256 $actual_hash does not match manifest SO_SHA256=$SO_SHA256; the build is not bit-identical (use --update to adopt it)"
    [[ "$sync_actual_size" == "$SYNC_SO_SIZE" ]] ||
      fail "fresh sync build size $sync_actual_size does not match manifest SYNC_SO_SIZE=$SYNC_SO_SIZE; the committed binary was left untouched (use --update to adopt a different build)"
    [[ "$sync_actual_hash" == "$SYNC_SO_SHA256" ]] ||
      fail "fresh sync build SHA-256 $sync_actual_hash does not match manifest SYNC_SO_SHA256=$SYNC_SO_SHA256; the build is not bit-identical (use --update to adopt it)"
    install_binary "$STAGED" "$DEST" "$SO_FILE"
    install_binary "$SYNC_STAGED" "$SYNC_DEST" "$SYNC_SO_FILE"
    echo "installed verified $DEST and $SYNC_DEST"
    return
  fi

  install_binary "$STAGED" "$DEST" "$SO_FILE"
  install_binary "$SYNC_STAGED" "$SYNC_DEST" "$SYNC_SO_FILE"
  write_manifest "$actual_version" "$actual_size" "$actual_hash" "$actual_glibc" \
    "$sync_actual_size" "$sync_actual_hash" "$sync_actual_glibc" \
    "$build_image" "$build_glibc" "$rust_version" "$header_hash" "$sync_header_hash"
  echo "updated $MANIFEST and installed $DEST and $SYNC_DEST"
  git -C "$ROOT" diff --stat
}

case "$MODE" in
  check)
    verify_committed
    if [[ -n "$TURSO" ]]; then
      verify_checkout_identity "$TURSO"
      echo "checkout:  $TURSO is at TURSO_COMMIT and clean"
    fi
    echo "check passed"
    ;;
  build | update)
    [[ -n "$TURSO" ]] || {
      usage >&2
      fail "a Turso checkout is required"
    }
    build_and_install "$TURSO"
    ;;
esac
