#!/usr/bin/env bash
# Fetches the compiled Phonon runtime (PhononCore.xcframework) that the vendored
# PhononTTS sources link against.
#
# It is not committed for the same reason llama.cpp and stable-diffusion.cpp are
# not: it is a ~50 MB prebuilt static library per platform slice, and this
# repository already keeps its native dependencies out of git. The checksum is
# the exact one the upstream Swift package pins for this release, so a downloaded
# archive is byte-identical to what SwiftPM would have resolved.
#
# Run from anywhere; writes ios-app/PhononCore/{lib,include}. Safe to re-run.
set -euo pipefail

VERSION="v0.6.0"
URL="https://github.com/gradium-ai/xn-ptts/releases/download/${VERSION}/PhononCore.xcframework.zip"
# From Package.swift of the same tag (binaryTarget checksum).
SHA256="1c7e5a77d4f051f064eebe73b39ff1025ee41e1263503d14cabdd927d90514aa"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${HERE}/PhononCore"
LIB="${DEST}/lib/libptts_ffi.a"

if [[ -f "${LIB}" ]]; then
    echo "fetch-phonon-core: already present at ${LIB}"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "fetch-phonon-core: downloading ${VERSION}…"
curl -sSL --fail -o "${TMP}/core.zip" "${URL}"

ACTUAL="$(shasum -a 256 "${TMP}/core.zip" 2>/dev/null || sha256sum "${TMP}/core.zip")"
ACTUAL="${ACTUAL%% *}"
if [[ "${ACTUAL}" != "${SHA256}" ]]; then
    echo "fetch-phonon-core: checksum mismatch" >&2
    echo "  expected ${SHA256}" >&2
    echo "  actual   ${ACTUAL}" >&2
    exit 1
fi

unzip -q "${TMP}/core.zip" -d "${TMP}/x"
SLICE="${TMP}/x/PhononCore.xcframework/ios-arm64"
if [[ ! -f "${SLICE}/libptts_ffi.a" ]]; then
    echo "fetch-phonon-core: archive has no ios-arm64 slice" >&2
    exit 1
fi

mkdir -p "${DEST}/lib" "${DEST}/include"
cp "${SLICE}/libptts_ffi.a" "${LIB}"
cp "${SLICE}/Headers/"* "${DEST}/include/"
echo "fetch-phonon-core: installed $(du -h "${LIB}" | cut -f1) runtime into ${DEST}"
