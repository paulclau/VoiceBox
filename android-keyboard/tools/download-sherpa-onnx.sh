#!/usr/bin/env bash
# Fetches the sherpa-onnx Android prebuilts that libbark_asr.so is built and packaged against.
#
#   ./tools/download-sherpa-onnx.sh
#
# They are not committed: the release tarball carries ~26 MB of arm64 binaries, and pinning the
# release here keeps the C shim, the header and the .so in step with each other. Re-running is a
# no-op once the files are present and verified.
set -euo pipefail

VERSION="2.0.1"
ABI="arm64-v8a" # the only packaged ABI; no 32-bit address space hosts a 1.2 GB model
TARBALL_SHA256="39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330"
HEADER_SHA256="5698f38ba09ad2bdf085c56a6cbbd7741acf15e25f4ce5c7097b9702cadf437f"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST_LIBS="$ROOT/app/src/main/jniLibs/$ABI"
DEST_INC="$ROOT/app/src/main/cpp/sherpa-onnx/include"
CACHE="${CACHE:-${TMPDIR:-/tmp}/barkboard-sherpa}"
RELEASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION"
TARBALL="$CACHE/sherpa-onnx-v$VERSION-android.tar.bz2"

log() { printf '=== %s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
sha_of() { sha256sum "$1" | cut -d' ' -f1; }
matches() { [ -f "$1" ] && [ "$(sha_of "$1")" = "$2" ]; }

mkdir -p "$CACHE" "$DEST_LIBS" "$DEST_INC"

# --- header: the C API of this exact release --------------------------------
if matches "$DEST_INC/c-api.h" "$HEADER_SHA256"; then
  log "c-api.h present"
else
  log "Fetching c-api.h ($VERSION)"
  curl -fsSL --retry 3 -o "$CACHE/c-api.h" \
    "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/sherpa-onnx/c-api/c-api.h"
  matches "$CACHE/c-api.h" "$HEADER_SHA256" || fail "c-api.h checksum mismatch"
  install -m 644 "$CACHE/c-api.h" "$DEST_INC/c-api.h"
fi

# --- libraries --------------------------------------------------------------
if [ -f "$DEST_LIBS/libonnxruntime.so" ] && [ -f "$DEST_LIBS/libsherpa-onnx-c-api.so" ]; then
  log "Libraries present"
else
  if matches "$TARBALL" "$TARBALL_SHA256"; then
    log "Reusing cached $TARBALL"
  else
    log "Downloading sherpa-onnx v$VERSION android prebuilts (~44 MB)"
    curl -fL --retry 3 -o "$TARBALL" "$RELEASE/sherpa-onnx-v$VERSION-android.tar.bz2"
    matches "$TARBALL" "$TARBALL_SHA256" || fail "android tarball checksum mismatch"
  fi

  log "Extracting $ABI"
  tar -xjf "$TARBALL" -C "$CACHE" "./jniLibs/$ABI"
  install -m 644 "$CACHE/jniLibs/$ABI/libonnxruntime.so" "$DEST_LIBS/libonnxruntime.so"
  install -m 644 "$CACHE/jniLibs/$ABI/libsherpa-onnx-c-api.so" "$DEST_LIBS/libsherpa-onnx-c-api.so"
fi

log "Done"
ls -lh "$DEST_LIBS" "$DEST_INC"
